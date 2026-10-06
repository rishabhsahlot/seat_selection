package com.paytm.seat_selection.exception;

import org.springframework.http.HttpStatus;

/** A domain outcome that maps to a 4xx response with a machine-readable reason. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String reason;

	public ApiException(HttpStatus status, String reason, String message) {
		super(message);
		this.status = status;
		this.reason = reason;
	}

	public HttpStatus status() {
		return this.status;
	}

	public String reason() {
		return this.reason;
	}

	public static ApiException notFound(String what) {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", what + " not found");
	}

	/** A clean domain decline (seat taken, over limit, key reused): 409, never a 5xx. */
	public static ApiException conflict(String reason, String message) {
		return new ApiException(HttpStatus.CONFLICT, reason, message);
	}

	public static ApiException invalid(String message) {
		return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "validation", message);
	}

}
