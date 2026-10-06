package com.paytm.seat_selection.event;

import java.util.List;
import java.util.UUID;

import com.paytm.seat_selection.exception.DeclineReason;

/** A reserve was declined; its transaction is rolled back. */
public record ReservationDeclined(DeclineReason reason, UUID showId, String userId, List<String> seats) {
}
