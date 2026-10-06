package com.paytm.seat_selection.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.paytm.seat_selection.repository.ReservationRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@code reservations_holds_overdue}: holds still HELD more than 10s past their deadline.
 * The sweeper expires holds every second, so this should always read 0; anything above
 * means expired seats are not coming back. Alert on it rather than restarting the app:
 * during a database outage the sweeper fails for a good reason.
 */
@Component
public class HoldExpiryGauge {

	private static final Logger log = LoggerFactory.getLogger(HoldExpiryGauge.class);

	private static final Duration GRACE = Duration.ofSeconds(10);

	private final ReservationRepository reservations;

	private final AtomicInteger overdue = new AtomicInteger();

	public HoldExpiryGauge(ReservationRepository reservations, MeterRegistry registry) {
		this.reservations = reservations;
		Gauge.builder("reservations.holds.overdue", this.overdue, AtomicInteger::get)
			.description("Holds still HELD more than " + GRACE.toSeconds() + "s past expiry (should be 0)")
			.register(registry);
	}

	@Scheduled(fixedDelayString = "${app.overdue-holds-refresh-interval:5s}")
	public void refresh() {
		try {
			this.overdue.set(this.reservations.countOverdueHolds(GRACE));
		}
		catch (DataAccessException ex) {
			// Readiness reports the outage; keep the last value rather than failing the scheduler.
			log.warn("overdue-holds refresh failed: {}", ex.getMessage());
		}
	}

}
