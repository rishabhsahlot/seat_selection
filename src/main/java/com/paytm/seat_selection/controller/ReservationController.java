package com.paytm.seat_selection.controller;

import java.util.UUID;

import jakarta.validation.Valid;

import com.paytm.seat_selection.dto.request.ReserveRequest;
import com.paytm.seat_selection.dto.response.ReservationResponse;
import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.jooq.enums.ReservationMode;
import com.paytm.seat_selection.service.ReservationService;
import com.paytm.seat_selection.service.ReservationService.ReserveOutcome;
import com.paytm.seat_selection.service.ReserveCommand;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The acting user is always the token subject; nothing in a request body can
 * change it.
 */
@RestController
public class ReservationController {

	public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

	public static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private final ReservationService reservations;

	public ReservationController(ReservationService reservations) {
		this.reservations = reservations;
	}

	/**
	 * 201 for a new reservation; 200 with {@code Idempotent-Replayed: true} for a
	 * retry.
	 */
	@PostMapping("/shows/{showId}/reserve")
	public ResponseEntity<ReservationResponse> reserve(@PathVariable UUID showId, @AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String headerKey,
			@Valid @RequestBody ReserveRequest request) {
		String key = idempotencyKey(headerKey, request.idempotencyKey());
		ReservationMode mode = "hold".equalsIgnoreCase(request.mode()) ? ReservationMode.HOLD : ReservationMode.CONFIRM;
		ReserveOutcome outcome = this.reservations
				.reserve(new ReserveCommand(showId, jwt.getSubject(), request.seats(), key, mode));
		if (outcome.replay()) {
			return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(outcome.reservation());
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(outcome.reservation());
	}

	@PostMapping("/reservations/{id}/confirm")
	public ReservationResponse confirm(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return this.reservations.confirm(id, jwt.getSubject());
	}

	@PostMapping("/reservations/{id}/cancel")
	public ReservationResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return this.reservations.cancel(id, jwt.getSubject());
	}

	@GetMapping("/reservations/{id}")
	public ReservationResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		return this.reservations.get(id, jwt.getSubject());
	}

	private static String idempotencyKey(String header, String body) {
		if (header != null && body != null && !header.equals(body)) {
			throw ApiException.invalid("Idempotency-Key header and idempotency_key body field differ");
		}
		String key = (header != null) ? header : body;
		if (key == null || key.isBlank() || key.length() > 128) {
			throw ApiException.invalid("idempotency_key is required (1-128 chars), as a header or body field");
		}
		return key;
	}

}
