package com.paytm.seat_selection.event;

import java.util.UUID;

/** A hold was turned into a confirmed reservation before it expired. */
public record HoldConfirmed(UUID reservationId, String userId) {
}
