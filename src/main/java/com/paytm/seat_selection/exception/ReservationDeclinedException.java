package com.paytm.seat_selection.exception;


/** A clean domain decline: a 4xx with a machine-readable reason, never a 5xx. */
public class ReservationDeclinedException extends ApiException {

	private final DeclineReason declineReason;

	public ReservationDeclinedException(DeclineReason reason, String message) {
		super(reason.status(), reason.code(), message);
		this.declineReason = reason;
	}

	public DeclineReason declineReason() {
		return this.declineReason;
	}

}
