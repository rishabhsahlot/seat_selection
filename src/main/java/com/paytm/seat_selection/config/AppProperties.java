package com.paytm.seat_selection.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param metricsPassword when set, /actuator/prometheus requires HTTP Basic auth with
 * {@code metricsUsername}/{@code metricsPassword}; when empty, it is open (local development)
 */
@ConfigurationProperties("app")
public record AppProperties(String jwtSecret, String adminApiKey, Duration holdTtl, int defaultPerUserLimit,
		String metricsUsername, String metricsPassword) {
}
