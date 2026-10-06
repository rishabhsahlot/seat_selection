package com.paytm.seat_selection;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.paytm.seat_selection.service.ReservationService;
import com.paytm.seat_selection.support.IntegrationTest;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static com.paytm.seat_selection.jooq.Tables.RESERVATIONS;
import static org.assertj.core.api.Assertions.assertThat;

/** The functional rules of the API, one behaviour per test. */
class ReservationApiTest extends IntegrationTest {

	@Autowired
	private DSLContext dsl;

	@Autowired
	private ReservationService reservations;

	// ---------------------------------------------------------------- reserve

	@Test
	void reserveConfirmsTheSeatsAndChargesPricePerSeat() {
		String show = createShow(4, "A1", "A2");
		Response r = reserve(token("alice"), show, "k1", "A2", "A1");

		assertThat(r.status()).isEqualTo(201);
		assertThat(r.field("status")).isEqualTo("confirmed");
		assertThat(r.field("user_id")).isEqualTo(userId("alice"));
		assertThat(r.body().get("amount_paise").asLong()).isEqualTo(50_000);
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
	}

	@Test
	void aRequestWithoutATokenIsRejected() {
		String show = createShow(4, "A1");
		Response r = reserve(null, show, "k1", "A1");

		assertThat(r.status()).isEqualTo(401);
		assertThat(r.field("reason")).isEqualTo("unauthorized");
	}

	@Test
	void identityComesFromTheTokenNotTheBody() {
		String show = createShow(4, "A1");
		Response r = send("POST", "/shows/" + show + "/reserve", token("bob"),
				Map.of("seats", new String[] { "A1" }, "idempotency_key", "k1", "user_id", userId("alice")), Map.of());

		assertThat(r.status()).isEqualTo(201);
		assertThat(r.field("user_id")).isEqualTo(userId("bob"));
	}

	@Test
	void aMultiSeatRequestIsAllOrNothing() {
		String show = createShow(4, "A1", "A2");
		reserve(token("bob"), show, "b1", "A1");

		Response r = reserve(token("alice"), show, "a1", "A1", "A2");

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.field("reason")).isEqualTo("seat_taken");
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
	}

	@Test
	void aDeclineDoesNotUseUpTheUsersLimit() {
		String show = createShow(2, "A1", "A2", "A3");
		String alice = token("alice");
		reserve(token("bob"), show, "b1", "A1");
		assertThat(reserve(alice, show, "a1", "A1", "A2").status()).isEqualTo(409);

		assertThat(reserve(alice, show, "a2", "A2", "A3").status()).isEqualTo(201);
	}

	@Test
	void moreSeatsThanTheLimitIsDeclined() {
		String show = createShow(2, "A1", "A2", "A3");
		Response r = reserve(token("alice"), show, "k1", "A1", "A2", "A3");

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.field("reason")).isEqualTo("per_user_limit");
	}

	@Test
	void anUnknownSeatIsRejected() {
		String show = createShow(4, "A1");
		Response r = reserve(token("alice"), show, "k1", "Z9");

		assertThat(r.status()).isEqualTo(422);
		assertThat(r.field("reason")).isEqualTo("unknown_seat");
	}

	// ---------------------------------------------------------------- idempotency

	@Test
	void aRetryReturnsTheOriginalReservation() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		Response first = reserve(alice, show, "k1", "A1");
		Response retry = reserve(alice, show, "k1", "A1");

		assertThat(retry.status()).isEqualTo(200);
		assertThat(retry.header("Idempotent-Replayed")).isEqualTo("true");
		assertThat(retry.field("reservation_id")).isEqualTo(first.field("reservation_id"));
	}

	@Test
	void theSameKeyForDifferentSeatsIsRejected() {
		String show = createShow(4, "A1", "A2");
		String alice = token("alice");
		reserve(alice, show, "k1", "A1");
		Response r = reserve(alice, show, "k1", "A2");

		assertThat(r.status()).isEqualTo(409);
		assertThat(r.field("reason")).isEqualTo("idempotency_key_reused");
		assertThat(seatStatus(show, "A2")).isEqualTo("available");
	}

	@Test
	void twoUsersCanUseTheSameKeyIndependently() {
		String show = createShow(4, "A1", "A2");
		assertThat(reserve(token("alice"), show, "shared", "A1").status()).isEqualTo(201);
		assertThat(reserve(token("bob"), show, "shared", "A2").status()).isEqualTo(201);
	}

	// ---------------------------------------------------------------- cancel

	@Test
	void cancellingReturnsTheSeatAndCancellingAgainIsHarmless() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		String id = reserve(alice, show, "k1", "A1").field("reservation_id");

		assertThat(cancel(alice, id).field("status")).isEqualTo("cancelled");
		assertThat(cancel(alice, id).status()).isEqualTo(200);
		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		assertThat(reserve(token("bob"), show, "b1", "A1").status()).isEqualTo(201);
	}

	@Test
	void anotherUserCannotSeeOrCancelYourReservation() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		String bob = token("bob");
		String id = reserve(alice, show, "k1", "A1").field("reservation_id");

		assertThat(cancel(bob, id).status()).isEqualTo(404);
		assertThat(send("GET", "/reservations/" + id, bob, null, Map.of()).status()).isEqualTo(404);
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
	}

	// ---------------------------------------------------------------- holds

	@Test
	void aHoldCanBeConfirmed() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		Response held = hold(alice, show, "k1", "A1");
		assertThat(held.field("status")).isEqualTo("held");
		assertThat(seatStatus(show, "A1")).isEqualTo("held");

		Response confirmed = confirm(alice, held.field("reservation_id"));

		assertThat(confirmed.field("status")).isEqualTo("confirmed");
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
	}

	@Test
	void anExpiredHoldReturnsItsSeatAndCannotBeConfirmed() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		String id = hold(alice, show, "k1", "A1").field("reservation_id");

		expireNow(id);

		assertThat(seatStatus(show, "A1")).isEqualTo("available");
		Response late = confirm(alice, id);
		assertThat(late.status()).isEqualTo(409);
		assertThat(late.field("reason")).isEqualTo("not_active");
	}

	@Test
	void releasingAnOldHoldNeverFreesASeatSomeoneElseNowHas() {
		String show = createShow(4, "A1");
		String alice = token("alice");
		String aliceHold = hold(alice, show, "k1", "A1").field("reservation_id");
		expireNow(aliceHold);
		assertThat(reserve(token("bob"), show, "b1", "A1").status()).isEqualTo(201);

		assertThat(cancel(alice, aliceHold).status()).isEqualTo(409);
		assertThat(seatStatus(show, "A1")).isEqualTo("confirmed");
	}

	// ---------------------------------------------------------------- shows

	@Test
	void creatingAShowNeedsTheAdminKey() {
		Response r = send("POST", "/shows", null,
				Map.of("name", "x", "seats", new String[] { "A1" }, "price_paise", 100), Map.of());
		assertThat(r.status()).isEqualTo(403);
	}

	@Test
	void showCountsAlwaysAddUp() {
		String show = createShow(4, "A1", "A2", "A3");
		String alice = token("alice");
		reserve(alice, show, "k1", "A1");
		hold(alice, show, "k2", "A2");

		var counts = show(show).body().get("counts");
		assertThat(counts.get("available").asInt()).isEqualTo(1);
		assertThat(counts.get("held").asInt()).isEqualTo(1);
		assertThat(counts.get("confirmed").asInt()).isEqualTo(1);
		assertThat(counts.get("total").asInt()).isEqualTo(3);
	}

	private Response confirm(String token, String id) {
		return send("POST", "/reservations/" + id + "/confirm", token, null, Map.of());
	}

	private Response cancel(String token, String id) {
		return send("POST", "/reservations/" + id + "/cancel", token, null, Map.of());
	}

	/** Moves the hold's deadline into the past and runs the sweeper's expiry step. */
	private void expireNow(String reservationId) {
		this.dsl.update(RESERVATIONS)
			.set(RESERVATIONS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
			.where(RESERVATIONS.ID.eq(UUID.fromString(reservationId)))
			.execute();
		this.reservations.expireDueHolds(100);
	}

}
