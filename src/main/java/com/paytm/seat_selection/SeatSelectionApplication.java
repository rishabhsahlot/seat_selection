package com.paytm.seat_selection;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SeatSelectionApplication {

	public static void main(String[] args) {
		configureSystemProperties();
		SpringApplication.run(SeatSelectionApplication.class, args);
	}

	/**
	 * JVM system properties needed before Spring starts.
	 */
	private static void configureSystemProperties() {
		// Keep jOOQ's startup banner and tips out of the structured logs.
		System.setProperty("org.jooq.no-logo", "true");
		System.setProperty("org.jooq.no-tips", "true");
	}

}
