package com.paytm.seat_selection.service;

import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.jooq.enums.ReservationMode;
import com.paytm.seat_selection.jooq.enums.ReservationStatus;
import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.jooq.tables.records.ReservationsRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReserveCommandTest {

	private static final UUID SHOW = UUID.randomUUID();

	@Test
	void seatsAreTrimmedAndSortedSoLocksAreTakenInOneGlobalOrder() {
		ReserveCommand command = new ReserveCommand(SHOW, "alice", List.of(" B2", "A10 ", "A1"), "k", ReservationMode.CONFIRM);
		assertThat(command.seats()).containsExactly("A1", "A10", "B2");
	}

	@Test
	void duplicateSeatsAreRejected() {
		assertThatThrownBy(() -> new ReserveCommand(SHOW, "alice", List.of("A1", " A1"), "k", ReservationMode.CONFIRM))
			.isInstanceOf(ApiException.class)
			.extracting("reason")
			.isEqualTo("validation");
	}

	@Test
	void modeDecidesTheInitialStatuses() {
		ReserveCommand hold = new ReserveCommand(SHOW, "alice", List.of("A1"), "k", ReservationMode.HOLD);
		ReserveCommand confirm = new ReserveCommand(SHOW, "alice", List.of("A1"), "k", ReservationMode.CONFIRM);
		assertThat(hold.initialStatus()).isEqualTo(ReservationStatus.HELD);
		assertThat(hold.seatStatus()).isEqualTo(SeatStatus.HELD);
		assertThat(confirm.initialStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(confirm.seatStatus()).isEqualTo(SeatStatus.CONFIRMED);
	}

	@Test
	void aRetryMustMatchShowSeatsAndMode() {
		ReservationsRecord original = new ReservationsRecord();
		original.setShowId(SHOW);
		original.setSeats(new String[] { "A1", "A2" });
		original.setMode(ReservationMode.CONFIRM);

		assertThat(command(SHOW, List.of("A2", "A1"), ReservationMode.CONFIRM).isRetryOf(original)).isTrue();
		assertThat(command(SHOW, List.of("A1"), ReservationMode.CONFIRM).isRetryOf(original)).isFalse();
		assertThat(command(SHOW, List.of("A1", "A2"), ReservationMode.HOLD).isRetryOf(original)).isFalse();
		assertThat(command(UUID.randomUUID(), List.of("A1", "A2"), ReservationMode.CONFIRM).isRetryOf(original)).isFalse();
	}

	private static ReserveCommand command(UUID show, List<String> seats, ReservationMode mode) {
		return new ReserveCommand(show, "alice", seats, "k", mode);
	}

}
