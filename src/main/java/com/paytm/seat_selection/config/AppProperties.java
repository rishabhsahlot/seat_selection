package com.paytm.seat_selection.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(String adminApiKey, Duration holdTtl, int defaultPerUserLimit) {
}
