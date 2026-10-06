package com.paytm.seat_selection.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Returns expired holds to available. Each batch is its own short transaction. */
@Component
public class HoldSweeper {

	private static final int BATCH = 200;

	private final ReservationService reservations;

	public HoldSweeper(ReservationService reservations) {
		this.reservations = reservations;
	}

	@Scheduled(fixedDelayString = "${app.sweep-interval:1s}")
	public void sweep() {
		while (this.reservations.expireDueHolds(BATCH) == BATCH) {
			// keep draining while full batches come back
		}
	}

}
