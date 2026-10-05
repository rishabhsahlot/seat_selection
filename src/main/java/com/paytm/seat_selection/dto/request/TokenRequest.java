package com.paytm.seat_selection.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TokenRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.@-]{1,64}") String userId) {
}
