package com.paytm.seat_selection.service;

import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.config.AppProperties;
import com.paytm.seat_selection.dto.response.ReservationResponse;
import com.paytm.seat_selection.event.HoldConfirmed;
import com.paytm.seat_selection.event.ReservationCreated;
import com.paytm.seat_selection.event.ReservationDeclined;
import com.paytm.seat_selection.event.ReservationReleased;
import com.paytm.seat_selection.event.ReservationReplayed;
import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.exception.DeclineReason;
import com.paytm.seat_selection.exception.ReservationDeclinedException;
import com.paytm.seat_selection.jooq.tables.pojos.Shows;
import com.paytm.seat_selection.jooq.tables.records.ReservationsRecord;
import com.paytm.seat_selection.repository.ReservationRepository;
import com.paytm.seat_selection.repository.SeatRepository;
import com.paytm.seat_selection.repository.UserSeatCountRepository;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides who gets each seat. Each public method is one transaction: it commits
 * as a
 * whole, or a decline (an exception) rolls every step back.
 * <p>
 * Lock order is the same on every path - reservation row, then the user's
 * seat-count row,
 * then seat rows in name order - so concurrent requests cannot deadlock. In
 * {@link #reserve} the order of the steps is that lock order.
 * <p>
 * Logging and metrics are not done here: the service publishes what happened,
 * and
 * listeners record it once the transaction has committed (or rolled back).
 */
@Service
public class ReservationService {

	private final ShowService shows;

	private final ReservationRepository reservations;

	private final UserSeatCountRepository seatCounts;

	private final SeatRepository seats;

	private final ApplicationEventPublisher events;

	private final AppProperties props;

	public ReservationService(ShowService shows, ReservationRepository reservations,
			UserSeatCountRepository seatCounts, SeatRepository seats, ApplicationEventPublisher events,
			AppProperties props) {
		this.shows = shows;
		this.reservations = reservations;
		this.seatCounts = seatCounts;
		this.seats = seats;
		this.events = events;
		this.props = props;
	}

	public record ReserveOutcome(ReservationResponse reservation, boolean replay) {
	}

	/**
	 * All-or-nothing reserve. A declined request consumes neither its key nor any
	 * limit.
	 */
	@Transactional
	public ReserveOutcome reserve(ReserveCommand command) {
		Shows show = this.shows.require(command.showId());
		if (command.seatCount() > show.perUserLimit()) {
			throw decline(DeclineReason.PER_USER_LIMIT, command, limitMessage(show));
		}

		// 1. Claim the idempotency key. A concurrent duplicate waits here, then
		// replays.
		UUID id = UUID.randomUUID();
		long amount = show.pricePaise() * command.seatCount();
		if (!this.reservations.insertIfKeyUnused(id, command, amount, this.props.holdTtl())) {
			return replay(command);
		}

		// 2. Per-user limit, atomic and serialised per (show, user).
		if (!this.seatCounts.tryAdd(command.showId(), command.userId(), command.seatCount(), show.perUserLimit())) {
			throw decline(DeclineReason.PER_USER_LIMIT, command, limitMessage(show));
		}

		// 3. Seats: lock in name order, then take only those still available.
		if (this.seats.lockInOrder(command.showId(), command.seats()) != command.seatCount()) {
			throw decline(DeclineReason.UNKNOWN_SEAT, command, DeclineReason.UNKNOWN_SEAT.defaultMessage());
		}
		int assigned = this.seats.assignIfAvailable(command.showId(), command.seats(), id, command.userId(),
				command.seatStatus());
		if (assigned != command.seatCount()) {
			throw decline(DeclineReason.SEAT_TAKEN, command, DeclineReason.SEAT_TAKEN.defaultMessage());
		}

		ReservationsRecord created = requireOwned(id, command.userId());
		this.events.publishEvent(new ReservationCreated(id, command.showId(), command.userId(), command.seats(),
				created.getStatus()));
		return new ReserveOutcome(ReservationResponse.from(created), false);
	}

	/** Turns the caller's own unexpired hold into a confirmed reservation. */
	@Transactional
	public ReservationResponse confirm(UUID id, String userId) {
		ReservationsRecord reservation = lockOwned(id, userId);
		if (!ReservationTransition.CONFIRM.appliesTo(reservation.getStatus())) {
			return ReservationResponse.from(reservation);
		}
		if (!this.reservations.isHoldLive(id)) {
			// The sweeper will release it; confirming after the deadline is never allowed.
			throw ApiException.conflict("hold_expired", "hold has expired");
		}
		List<String> seatNames = List.of(reservation.getSeats());
		this.seats.lockInOrder(reservation.getShowId(), seatNames);
		this.seats.confirmFor(reservation.getShowId(), seatNames, id);
		this.reservations.updateStatus(id, ReservationTransition.CONFIRM.target());
		this.events.publishEvent(new HoldConfirmed(id, userId));
		return ReservationResponse.from(requireOwned(id, userId));
	}

	/** Releases the caller's own hold or confirmed reservation. */
	@Transactional
	public ReservationResponse cancel(UUID id, String userId) {
		ReservationsRecord reservation = lockOwned(id, userId);
		if (ReservationTransition.CANCEL.appliesTo(reservation.getStatus())) {
			release(reservation, ReservationTransition.CANCEL);
		}
		return ReservationResponse.from(requireOwned(id, userId));
	}

	public ReservationResponse get(UUID id, String userId) {
		return ReservationResponse.from(requireOwned(id, userId));
	}

	/**
	 * Returns the reservation's seats to available and gives the user back their
	 * quota.
	 */
	private void release(ReservationsRecord reservation, ReservationTransition transition) {
		List<String> seatNames = List.of(reservation.getSeats());
		this.reservations.updateStatus(reservation.getId(), transition.target());
		this.seatCounts.subtract(reservation.getShowId(), reservation.getUserId(), seatNames.size());
		this.seats.lockInOrder(reservation.getShowId(), seatNames);
		this.seats.freeFor(reservation.getShowId(), seatNames, reservation.getId());
		this.events.publishEvent(
				new ReservationReleased(reservation.getId(), reservation.getUserId(), transition.target()));
	}

	private ReserveOutcome replay(ReserveCommand command) {
		ReservationsRecord original = this.reservations.findByKey(command.userId(), command.idempotencyKey())
				.orElseThrow();
		if (!command.isRetryOf(original)) {
			throw decline(DeclineReason.IDEMPOTENCY_KEY_REUSED, command,
					DeclineReason.IDEMPOTENCY_KEY_REUSED.defaultMessage());
		}
		this.events.publishEvent(new ReservationReplayed(original.getId(), command.userId()));
		return new ReserveOutcome(ReservationResponse.from(original), true);
	}

	private ReservationDeclinedException decline(DeclineReason reason, ReserveCommand command, String message) {
		this.events.publishEvent(
				new ReservationDeclined(reason, command.showId(), command.userId(), command.seats()));
		return new ReservationDeclinedException(reason, message);
	}

	/**
	 * Another user's reservation is reported as not found, so ownership is not
	 * leaked.
	 */
	private ReservationsRecord lockOwned(UUID id, String userId) {
		return this.reservations.lockOwned(id, userId).orElseThrow(() -> ApiException.notFound("reservation"));
	}

	private ReservationsRecord requireOwned(UUID id, String userId) {
		return this.reservations.findOwned(id, userId).orElseThrow(() -> ApiException.notFound("reservation"));
	}

	private static String limitMessage(Shows show) {
		return "a user may hold at most " + show.perUserLimit() + " seats for this show";
	}

}
