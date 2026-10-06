package com.paytm.seat_selection.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.jooq.tables.records.ReservationsRecord;

public record ReservationResponse(UUID reservationId, UUID showId, String userId, List<String> seats, long amountPaise,
		String status, String mode, OffsetDateTime expiresAt) {

	public static ReservationResponse from(ReservationsRecord r) {
		return new ReservationResponse(r.getId(), r.getShowId(), r.getUserId(), List.of(r.getSeats()), r.getAmountPaise(),
				r.getStatus().getLiteral().toLowerCase(), r.getMode().getLiteral().toLowerCase(), r.getExpiresAt());
	}

}
