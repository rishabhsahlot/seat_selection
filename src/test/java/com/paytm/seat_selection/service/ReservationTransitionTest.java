package com.paytm.seat_selection.service;

import com.paytm.seat_selection.exception.ApiException;
import org.junit.jupiter.api.Test;

import static com.paytm.seat_selection.jooq.enums.ReservationStatus.CANCELLED;
import static com.paytm.seat_selection.jooq.enums.ReservationStatus.CONFIRMED;
import static com.paytm.seat_selection.jooq.enums.ReservationStatus.EXPIRED;
import static com.paytm.seat_selection.jooq.enums.ReservationStatus.HELD;
import static com.paytm.seat_selection.service.ReservationTransition.CANCEL;
import static com.paytm.seat_selection.service.ReservationTransition.CONFIRM;
import static com.paytm.seat_selection.service.ReservationTransition.EXPIRE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTransitionTest {

	@Test
	void allowedMovesApply() {
		assertThat(CONFIRM.appliesTo(HELD)).isTrue();
		assertThat(CANCEL.appliesTo(HELD)).isTrue();
		assertThat(CANCEL.appliesTo(CONFIRMED)).isTrue();
		assertThat(EXPIRE.appliesTo(HELD)).isTrue();
	}

	@Test
	void repeatingAMoveIsANoOp() {
		assertThat(CONFIRM.appliesTo(CONFIRMED)).isFalse();
		assertThat(CANCEL.appliesTo(CANCELLED)).isFalse();
	}

	@Test
	void anyOtherMoveIsNotActive() {
		assertNotActive(() -> CONFIRM.appliesTo(CANCELLED));
		assertNotActive(() -> CONFIRM.appliesTo(EXPIRED));
		assertNotActive(() -> CANCEL.appliesTo(EXPIRED));
		assertNotActive(() -> EXPIRE.appliesTo(CONFIRMED));
	}

	private static void assertNotActive(Runnable move) {
		assertThatThrownBy(move::run).isInstanceOf(ApiException.class).extracting("reason").isEqualTo("not_active");
	}

}
