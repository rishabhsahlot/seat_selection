package com.paytm.seat_selection.dto.response;

import java.util.List;
import java.util.UUID;

public record ShowResponse(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Counts counts,
		List<SeatResponse> seats) {

	public record Counts(int available, int held, int confirmed, int total) {
	}

	public record SeatResponse(String seatName, String status) {
	}

}
