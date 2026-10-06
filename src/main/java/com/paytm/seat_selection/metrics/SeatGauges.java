package com.paytm.seat_selection.metrics;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.repository.SeatRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-show seat gauges ({@code seats_available}, {@code seats_held}, {@code seats_confirmed},
 * {@code seats_capacity}, labelled by {@code show_id}), refreshed from the database every
 * second. Each refresh is one GROUP BY snapshot, so the four gauges always satisfy
 * available + held + confirmed == capacity. (Not {@code seats_total}: Prometheus reserves the
 * {@code _total} suffix for counters and strips it from gauges.) Only recent shows are exported to bound label
 * cardinality.
 */
@Component
public class SeatGauges {

	private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

	private static final int RECENT_HOURS = 24;

	private final SeatRepository seats;

	private final Map<SeatStatus, MultiGauge> byStatus = new EnumMap<>(SeatStatus.class);

	private final MultiGauge capacity;

	public SeatGauges(SeatRepository seats, MeterRegistry registry) {
		this.seats = seats;
		for (SeatStatus status : SeatStatus.values()) {
			String name = "seats." + status.getLiteral().toLowerCase();
			this.byStatus.put(status,
					MultiGauge.builder(name).description("Seats currently " + status.getLiteral().toLowerCase())
						.register(registry));
		}
		this.capacity = MultiGauge.builder("seats.capacity").description("Seats in the show").register(registry);
	}

	@Scheduled(fixedDelayString = "${app.gauge-refresh-interval:1s}")
	public void refresh() {
		Map<UUID, Map<SeatStatus, Integer>> counts;
		try {
			counts = this.seats.countByStatusForShowsCreatedAfter(OffsetDateTime.now().minusHours(RECENT_HOURS));
		}
		catch (DataAccessException ex) {
			// Readiness reports the outage; keep the last values rather than failing the scheduler.
			log.warn("seat gauge refresh failed: {}", ex.getMessage());
			return;
		}
		for (SeatStatus status : SeatStatus.values()) {
			List<MultiGauge.Row<?>> rows = new ArrayList<>();
			counts.forEach((show, byStatus) -> rows
				.add(MultiGauge.Row.of(Tags.of("show_id", show.toString()), byStatus.getOrDefault(status, 0))));
			this.byStatus.get(status).register(rows, true);
		}
		List<MultiGauge.Row<?>> totals = new ArrayList<>();
		counts.forEach((show, byStatus) -> totals.add(MultiGauge.Row.of(Tags.of("show_id", show.toString()),
				byStatus.values().stream().mapToInt(Integer::intValue).sum())));
		this.capacity.register(totals, true);
	}

}
