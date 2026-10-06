package com.paytm.seat_selection.service;

import java.util.EnumSet;
import java.util.Set;

import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.jooq.enums.ReservationStatus;

/**
 * The reservation lifecycle as a transition table:
 *
 * <pre>
 * HELD      --confirm--> CONFIRMED
 * HELD      --cancel---> CANCELLED      CONFIRMED --cancel--> CANCELLED
 * HELD      --expire---> EXPIRED
 * CANCELLED, EXPIRED: terminal
 * </pre>
 *
 * Repeating a transition that already happened (confirm twice, cancel twice) is a no-op,
 * so retries are safe. Anything else is a 409 {@code not_active}.
 */
public enum ReservationTransition {

	CONFIRM(ReservationStatus.CONFIRMED, EnumSet.of(ReservationStatus.HELD)),

	CANCEL(ReservationStatus.CANCELLED, EnumSet.of(ReservationStatus.HELD, ReservationStatus.CONFIRMED)),

	EXPIRE(ReservationStatus.EXPIRED, EnumSet.of(ReservationStatus.HELD));

	private final ReservationStatus target;

	private final Set<ReservationStatus> allowedFrom;

	ReservationTransition(ReservationStatus target, Set<ReservationStatus> allowedFrom) {
		this.target = target;
		this.allowedFrom = allowedFrom;
	}

	public ReservationStatus target() {
		return this.target;
	}

	/**
	 * @return {@code true} if the transition should be applied, {@code false} if the
	 * reservation is already in the target state
	 * @throws ApiException 409 {@code not_active} if the transition is not allowed
	 */
	public boolean appliesTo(ReservationStatus current) {
		if (current == this.target) {
			return false;
		}
		if (this.allowedFrom.contains(current)) {
			return true;
		}
		throw ApiException.conflict("not_active", "reservation is " + current.getLiteral().toLowerCase());
	}

}
