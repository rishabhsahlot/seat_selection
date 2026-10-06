package com.paytm.seat_selection.service;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.jooq.enums.ReservationMode;
import com.paytm.seat_selection.jooq.enums.ReservationStatus;
import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.jooq.tables.records.ReservationsRecord;

/**
 * A validated reserve request. Seat names are trimmed, de-duplicated (duplicates are
 * rejected) and sorted on construction, so every later step can rely on a clean list,
 * and locking them in list order is locking them in a global order.
 */
public record ReserveCommand(UUID showId, String userId, List<String> seats, String idempotencyKey,
		ReservationMode mode) {

	public ReserveCommand {
		seats = seats.stream().map(String::trim).sorted().toList();
		if (seats.stream().distinct().count() != seats.size()) {
			throw ApiException.invalid("seat names must be unique");
		}
	}

	public int seatCount() {
		return this.seats.size();
	}

	public ReservationStatus initialStatus() {
		return (this.mode == ReservationMode.HOLD) ? ReservationStatus.HELD : ReservationStatus.CONFIRMED;
	}

	public SeatStatus seatStatus() {
		return (this.mode == ReservationMode.HOLD) ? SeatStatus.HELD : SeatStatus.CONFIRMED;
	}

	/** Same key, same request: a retry. Same key, anything different: a reused key. */
	public boolean isRetryOf(ReservationsRecord original) {
		return original.getShowId().equals(this.showId) && Arrays.asList(original.getSeats()).equals(this.seats)
				&& original.getMode() == this.mode;
	}

}
