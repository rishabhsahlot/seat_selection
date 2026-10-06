package com.paytm.seat_selection.dto.request;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /shows/{id}/reserve}. There is deliberately no user field: identity
 * comes only from the bearer token, and unknown JSON fields are ignored.
 *
 * @param seats seat names wanted; all-or-nothing
 * @param idempotencyKey may instead be sent as the {@code Idempotency-Key} header
 * @param mode {@code confirm} (default) or {@code hold} for a time-boxed hold
 */
public record ReserveRequest(@NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 16) String> seats,
		@Size(min = 1, max = 128) String idempotencyKey, @Pattern(regexp = "(?i)hold|confirm") String mode) {
}
