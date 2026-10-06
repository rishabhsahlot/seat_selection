package com.paytm.seat_selection.repository;

import static com.paytm.seat_selection.jooq.Tables.USER_SHOW_HOLDS;

import java.util.UUID;

import org.jooq.DSLContext;

import org.springframework.stereotype.Repository;

/** Seats each user currently holds or has confirmed per show ({@code user_show_holds}). */
@Repository
public class UserSeatCountRepository {

	private final DSLContext dsl;

	public UserSeatCountRepository(DSLContext dsl) {
		this.dsl = dsl;
	}

	/**
	 * Guarded upsert: {@code INSERT ... ON CONFLICT DO UPDATE SET seat_count = seat_count + n
	 * WHERE seat_count + n <= limit}. Atomic, and serialised per (show, user) by the row
	 * lock, so parallel requests from one user cannot overshoot the limit.
	 * @return {@code false} if adding {@code n} seats would exceed the limit
	 */
	public boolean tryAdd(UUID showId, String userId, int n, int limit) {
		return this.dsl.insertInto(USER_SHOW_HOLDS)
			.set(USER_SHOW_HOLDS.SHOW_ID, showId)
			.set(USER_SHOW_HOLDS.USER_ID, userId)
			.set(USER_SHOW_HOLDS.SEAT_COUNT, n)
			.onConflict(USER_SHOW_HOLDS.SHOW_ID, USER_SHOW_HOLDS.USER_ID)
			.doUpdate()
			.set(USER_SHOW_HOLDS.SEAT_COUNT, USER_SHOW_HOLDS.SEAT_COUNT.plus(n))
			.where(USER_SHOW_HOLDS.SEAT_COUNT.plus(n).le(limit))
			.execute() == 1;
	}

	public void subtract(UUID showId, String userId, int n) {
		this.dsl.update(USER_SHOW_HOLDS)
			.set(USER_SHOW_HOLDS.SEAT_COUNT, USER_SHOW_HOLDS.SEAT_COUNT.minus(n))
			.where(USER_SHOW_HOLDS.SHOW_ID.eq(showId), USER_SHOW_HOLDS.USER_ID.eq(userId))
			.execute();
	}

}
