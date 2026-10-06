package com.paytm.seat_selection.repository;

import static com.paytm.seat_selection.jooq.Tables.RESERVATIONS;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.paytm.seat_selection.jooq.enums.ReservationMode;
import com.paytm.seat_selection.jooq.enums.ReservationStatus;
import com.paytm.seat_selection.jooq.tables.records.ReservationsRecord;
import com.paytm.seat_selection.service.ReserveCommand;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;

import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

	private final DSLContext dsl;

	public ReservationRepository(DSLContext dsl) {
		this.dsl = dsl;
	}

	/**
	 * Claims the idempotency key by inserting the reservation:
	 * {@code INSERT ... ON CONFLICT (user_id, idempotency_key) DO NOTHING}. A concurrent
	 * insert with the same key blocks on the unique index until this transaction ends.
	 * @return {@code false} if this user already used the key
	 */
	public boolean insertIfKeyUnused(UUID id, ReserveCommand command, long amountPaise, Duration holdTtl) {
		boolean hold = command.mode() == ReservationMode.HOLD;
		return this.dsl.insertInto(RESERVATIONS)
			.set(RESERVATIONS.ID, id)
			.set(RESERVATIONS.SHOW_ID, command.showId())
			.set(RESERVATIONS.USER_ID, command.userId())
			.set(RESERVATIONS.IDEMPOTENCY_KEY, command.idempotencyKey())
			.set(RESERVATIONS.MODE, command.mode())
			.set(RESERVATIONS.SEATS, command.seats().toArray(String[]::new))
			.set(RESERVATIONS.AMOUNT_PAISE, amountPaise)
			.set(RESERVATIONS.STATUS, command.initialStatus())
			.set(RESERVATIONS.EXPIRES_AT, hold ? expiryAfter(holdTtl) : DSL.castNull(OffsetDateTime.class))
			.onConflict(RESERVATIONS.USER_ID, RESERVATIONS.IDEMPOTENCY_KEY)
			.doNothing()
			.execute() == 1;
	}

	public Optional<ReservationsRecord> findByKey(String userId, String idempotencyKey) {
		return this.dsl.selectFrom(RESERVATIONS)
			.where(RESERVATIONS.USER_ID.eq(userId), RESERVATIONS.IDEMPOTENCY_KEY.eq(idempotencyKey))
			.fetchOptional();
	}

	/** Matching on the owner too means another user's reservation is simply not found. */
	public Optional<ReservationsRecord> findOwned(UUID id, String userId) {
		return this.dsl.selectFrom(RESERVATIONS)
			.where(RESERVATIONS.ID.eq(id), RESERVATIONS.USER_ID.eq(userId))
			.fetchOptional();
	}

	/** {@link #findOwned} with {@code FOR UPDATE}: serialises confirm, cancel and expiry. */
	public Optional<ReservationsRecord> lockOwned(UUID id, String userId) {
		return this.dsl.selectFrom(RESERVATIONS)
			.where(RESERVATIONS.ID.eq(id), RESERVATIONS.USER_ID.eq(userId))
			.forUpdate()
			.fetchOptional();
	}

	/** Whether the hold's deadline is still ahead, by the database clock. */
	public boolean isHoldLive(UUID id) {
		return this.dsl.select(DSL.field(RESERVATIONS.EXPIRES_AT.gt(DSL.currentOffsetDateTime())))
			.from(RESERVATIONS)
			.where(RESERVATIONS.ID.eq(id))
			.fetchSingle()
			.value1();
	}

	public void updateStatus(UUID id, ReservationStatus status) {
		this.dsl.update(RESERVATIONS).set(RESERVATIONS.STATUS, status).where(RESERVATIONS.ID.eq(id)).execute();
	}

	/**
	 * Overdue holds, locked with {@code FOR UPDATE SKIP LOCKED}: never waits on a hold that
	 * is being confirmed or cancelled, and lets several instances sweep at once.
	 */
	public List<ReservationsRecord> lockDueHolds(int limit) {
		return this.dsl.selectFrom(RESERVATIONS)
			.where(RESERVATIONS.STATUS.eq(ReservationStatus.HELD),
					RESERVATIONS.EXPIRES_AT.lt(DSL.currentOffsetDateTime()))
			.orderBy(RESERVATIONS.EXPIRES_AT)
			.limit(limit)
			.forUpdate()
			.skipLocked()
			.fetch();
	}

	/**
	 * Holds still HELD more than {@code grace} past their deadline (database clock). The
	 * sweeper runs every second, so this should always be 0. Uses the partial index on
	 * held reservations.
	 */
	public int countOverdueHolds(Duration grace) {
		return this.dsl.fetchCount(RESERVATIONS, RESERVATIONS.STATUS.eq(ReservationStatus.HELD),
				RESERVATIONS.EXPIRES_AT.lt(DSL.field("now() - make_interval(secs => {0})", OffsetDateTime.class,
						DSL.val(grace.toMillis() / 1000.0))));
	}

	/** Computed by the database clock, the same clock {@link #isHoldLive} and the sweeper use. */
	private static Field<OffsetDateTime> expiryAfter(Duration ttl) {
		return DSL.field("now() + make_interval(secs => {0})", OffsetDateTime.class, DSL.val(ttl.toMillis() / 1000.0));
	}

}
