package com.paytm.seat_selection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import com.paytm.seat_selection.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Races many requests at the same instant over real HTTP against real Postgres. Kept under
 * ~100 parallel requests so the test is not limited by the OS listen backlog on a laptop.
 */
class ReservationConcurrencyTest extends IntegrationTest {

	@Test
	void manyUsersRacingForOneSeatProduceExactlyOneWinner() throws Exception {
		String show = createShow(4, "A1", "A2");
		List<Callable<Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			String token = token("racer-" + i);
			tasks.add(() -> reserve(token, show, "k", "A1"));
		}

		List<Response> results = concurrently(tasks);

		assertThat(countStatus(results, 201)).isEqualTo(1);
		assertThat(countReason(results, "seat_taken")).isEqualTo(99);
		assertThat(results).noneMatch(r -> r.status() >= 500);
		assertInvariant(show, 1);
	}

	@Test
	void parallelReservesFromOneUserStayWithinTheLimit() throws Exception {
		String show = createShow(4, "L1", "L2", "L3", "L4", "L5", "L6", "L7", "L8", "L9", "L10");
		String token = token("greedy");
		List<Callable<Response>> tasks = new ArrayList<>();
		for (int i = 1; i <= 10; i++) {
			String seat = "L" + i;
			tasks.add(() -> reserve(token, show, "lim-" + seat, seat));
		}

		List<Response> results = concurrently(tasks);

		assertThat(countStatus(results, 201)).isEqualTo(4);
		assertThat(countReason(results, "per_user_limit")).isEqualTo(6);
		assertInvariant(show, 4);
	}

	@Test
	void parallelRetriesWithOneKeyCreateOneReservation() throws Exception {
		String show = createShow(4, "R1");
		String token = token("retrier");
		List<Callable<Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			tasks.add(() -> reserve(token, show, "same-key", "R1"));
		}

		List<Response> results = concurrently(tasks);

		assertThat(countStatus(results, 201)).isEqualTo(1);
		assertThat(results).filteredOn(r -> r.status() == 200)
			.hasSize(49)
			.allMatch(r -> "true".equals(r.header("Idempotent-Replayed")));
		assertThat(results.stream().map(r -> r.field("reservation_id")).distinct()).hasSize(1);
		assertInvariant(show, 1);
	}

	@Test
	void overlappingMultiSeatRequestsInOppositeOrderNeitherDeadlockNorSplit() throws Exception {
		String show = createShow(4, "B1", "B2");
		List<Callable<Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			String token = token("pair-" + i);
			String[] seats = (i % 2 == 0) ? new String[] { "B1", "B2" } : new String[] { "B2", "B1" };
			tasks.add(() -> reserve(token, show, "k", seats));
		}

		List<Response> results = concurrently(tasks);

		assertThat(countStatus(results, 201)).isEqualTo(1);
		assertThat(countReason(results, "seat_taken")).isEqualTo(59);
		// All-or-nothing: both seats went to the one winning reservation.
		Response winner = results.stream().filter(r -> r.status() == 201).findFirst().orElseThrow();
		assertThat(winner.body().get("seats").valueStream().map(JsonNode::asString)).containsExactly("B1", "B2");
		assertInvariant(show, 2);
	}

	private void assertInvariant(String show, int confirmed) {
		JsonNode counts = show(show).body().get("counts");
		int total = counts.get("total").asInt();
		assertThat(counts.get("available").asInt() + counts.get("held").asInt() + counts.get("confirmed").asInt())
			.isEqualTo(total);
		assertThat(counts.get("confirmed").asInt()).isEqualTo(confirmed);
	}

}
