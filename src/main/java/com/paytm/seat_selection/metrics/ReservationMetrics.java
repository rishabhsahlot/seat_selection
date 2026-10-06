package com.paytm.seat_selection.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.paytm.seat_selection.event.HoldConfirmed;
import com.paytm.seat_selection.event.ReservationCreated;
import com.paytm.seat_selection.event.ReservationDeclined;
import com.paytm.seat_selection.event.ReservationReleased;
import com.paytm.seat_selection.event.ReservationReplayed;
import com.paytm.seat_selection.exception.DeclineReason;
import com.paytm.seat_selection.jooq.enums.ReservationStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Reservation outcome counters, exported at /actuator/prometheus as
 * {@code reservations_confirmed_total}, {@code reservations_held_total},
 * {@code reservations_declined_total{reason}} and {@code reservations_released_total{reason}}.
 * <p>
 * Driven by domain events delivered after the transaction's outcome is known: successes
 * after commit (a counter never runs ahead of the database), declines after rollback.
 */
@Component
public class ReservationMetrics {

	static final String IDEMPOTENT_REPLAY = "idempotent_replay";

	private final MeterRegistry registry;

	private final Counter confirmed;

	private final Counter held;

	private final Map<String, Counter> declined = new ConcurrentHashMap<>();

	private final Map<String, Counter> released = new ConcurrentHashMap<>();

	public ReservationMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.confirmed = Counter.builder("reservations.confirmed")
			.description("Reservations that became confirmed (direct confirm or hold -> confirm)")
			.register(registry);
		this.held = Counter.builder("reservations.held").description("Time-boxed holds created").register(registry);
		// Register every reason up front so each series reads 0 rather than being absent.
		for (DeclineReason reason : DeclineReason.values()) {
			declined(reason.code());
		}
		declined(IDEMPOTENT_REPLAY);
		released(ReservationStatus.CANCELLED);
		released(ReservationStatus.EXPIRED);
	}

	@TransactionalEventListener
	void on(ReservationCreated event) {
		(event.status() == ReservationStatus.HELD ? this.held : this.confirmed).increment();
	}

	@TransactionalEventListener
	void on(HoldConfirmed event) {
		this.confirmed.increment();
	}

	/** The brief counts an idempotent replay as a decline reason: it creates nothing new. */
	@TransactionalEventListener
	void on(ReservationReplayed event) {
		declined(IDEMPOTENT_REPLAY).increment();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
	void on(ReservationDeclined event) {
		declined(event.reason().code()).increment();
	}

	@TransactionalEventListener
	void on(ReservationReleased event) {
		released(event.outcome()).increment();
	}

	private Counter declined(String reason) {
		return this.declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined")
			.description("Reserve requests that did not create a reservation, by reason")
			.tag("reason", r)
			.register(this.registry));
	}

	private Counter released(ReservationStatus outcome) {
		return this.released.computeIfAbsent(outcome.getLiteral().toLowerCase(),
				r -> Counter.builder("reservations.released")
					.description("Reservations whose seats went back to available, by reason")
					.tag("reason", r)
					.register(this.registry));
	}

}
