package com.paytm.seat_selection.dto.request;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateShowRequest(@NotBlank @Size(max = 200) String name,
		@NotEmpty @Size(max = 50_000) List<@NotBlank @Size(max = 16) String> seats, @PositiveOrZero long pricePaise,
		@Positive @Max(100) Integer perUserLimit) {
}
