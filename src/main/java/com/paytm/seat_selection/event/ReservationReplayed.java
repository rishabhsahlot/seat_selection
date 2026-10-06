package com.paytm.seat_selection.event;

import java.util.UUID;

/** A retry with an already-used idempotency key returned the original reservation. */
public record ReservationReplayed(UUID reservationId, String userId) {
}
