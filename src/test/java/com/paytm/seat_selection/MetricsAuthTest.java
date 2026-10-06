package com.paytm.seat_selection;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.paytm.seat_selection.support.IntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With METRICS_PASSWORD set, only the scraper's credentials can read /actuator/prometheus.
 * Spring Boot turns metrics export off in tests; {@code @AutoConfigureMetrics} turns it back on.
 */
@AutoConfigureMetrics
@TestPropertySource(properties = { "app.metrics-username=grafana", "app.metrics-password=test-metrics" })
class MetricsAuthTest extends IntegrationTest {

	@Test
	void metricsNeedTheScraperCredentials() {
		assertThat(metrics(null).status()).isEqualTo(401);
		assertThat(metrics(basic("grafana", "wrong")).status()).isEqualTo(401);

		Response ok = metrics(basic("grafana", "test-metrics"));
		assertThat(ok.status()).isEqualTo(200);
		assertThat(ok.raw().body()).contains("reservations_confirmed_total");
	}

	@Test
	void theRestOfTheApiIsUnaffected() {
		assertThat(send("GET", "/actuator/health/readiness", null, null, Map.of()).status()).isEqualTo(200);
		String show = createShow(4, "A1");
		assertThat(reserve(token("alice"), show, "k1", "A1").status()).isEqualTo(201);
	}

	private Response metrics(String authorization) {
		return send("GET", "/actuator/prometheus", null, null,
				(authorization != null) ? Map.of("Authorization", authorization) : Map.of());
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

}
