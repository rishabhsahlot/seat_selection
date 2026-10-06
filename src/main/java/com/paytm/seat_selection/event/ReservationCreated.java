package com.paytm.seat_selection.event;

import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.jooq.enums.ReservationStatus;

/** A reserve created a reservation: {@code status} is CONFIRMED, or HELD for a hold. */
public record ReservationCreated(UUID reservationId, UUID showId, String userId, List<String> seats,
		ReservationStatus status) {
}
