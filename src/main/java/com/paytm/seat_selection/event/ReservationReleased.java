package com.paytm.seat_selection.event;

import java.util.UUID;

import com.paytm.seat_selection.jooq.enums.ReservationStatus;

/** A reservation's seats went back to available; {@code outcome} is CANCELLED or EXPIRED. */
public record ReservationReleased(UUID reservationId, String userId, ReservationStatus outcome) {
}
