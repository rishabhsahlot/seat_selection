package com.paytm.seat_selection;

import com.paytm.seat_selection.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeatSelectionApplicationTests extends IntegrationTest {

	@Test
	void startsAndIsReady() {
		assertThat(send("GET", "/actuator/health/readiness", null, null, java.util.Map.of()).status()).isEqualTo(200);
	}

}
