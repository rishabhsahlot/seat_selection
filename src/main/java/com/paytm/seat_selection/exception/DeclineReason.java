package com.paytm.seat_selection.exception;

import org.springframework.http.HttpStatus;

/**
 * Why a reserve request did not create a reservation. The wire name ({@link #code()}) is
 * used in the response {@code reason}, the {@code reservations_declined_total{reason}}
 * metric and the logs, so all three always agree.
 */
public enum DeclineReason {

	SEAT_TAKEN(HttpStatus.CONFLICT, "one or more seats are already taken"),

	PER_USER_LIMIT(HttpStatus.CONFLICT, "too many seats for one user on this show"),

	IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "idempotency key was already used for a different request"),

	UNKNOWN_SEAT(HttpStatus.UNPROCESSABLE_CONTENT, "one or more seats do not exist in this show");

	private final HttpStatus status;

	private final String defaultMessage;

	DeclineReason(HttpStatus status, String defaultMessage) {
		this.status = status;
		this.defaultMessage = defaultMessage;
	}

	public String code() {
		return name().toLowerCase();
	}

	public HttpStatus status() {
		return this.status;
	}

	public String defaultMessage() {
		return this.defaultMessage;
	}

}
