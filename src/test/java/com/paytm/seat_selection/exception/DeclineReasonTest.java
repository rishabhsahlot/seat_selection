package com.paytm.seat_selection.exception;

import org.junit.jupiter.api.Test;

import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class DeclineReasonTest {

	@Test
	void wireNamesMatchTheApiAndMetrics() {
		assertThat(DeclineReason.SEAT_TAKEN.code()).isEqualTo("seat_taken");
		assertThat(DeclineReason.PER_USER_LIMIT.code()).isEqualTo("per_user_limit");
		assertThat(DeclineReason.IDEMPOTENCY_KEY_REUSED.code()).isEqualTo("idempotency_key_reused");
		assertThat(DeclineReason.UNKNOWN_SEAT.code()).isEqualTo("unknown_seat");
	}

	@Test
	void declinesAreClientErrorsNeverServerErrors() {
		for (DeclineReason reason : DeclineReason.values()) {
			assertThat(reason.status().is4xxClientError()).as(reason.name()).isTrue();
		}
		assertThat(new ReservationDeclinedException(DeclineReason.SEAT_TAKEN, "taken").status())
			.isEqualTo(HttpStatus.CONFLICT);
	}

}
