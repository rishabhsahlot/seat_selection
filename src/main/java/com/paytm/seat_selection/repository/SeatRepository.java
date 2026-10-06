package com.paytm.seat_selection.repository;

import static com.paytm.seat_selection.jooq.Tables.SEATS;
import static com.paytm.seat_selection.jooq.Tables.SHOWS;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.jooq.tables.pojos.Seats;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import org.springframework.stereotype.Repository;

/**
 * Seat rows. Seats are only ever row-locked through {@link #lockInOrder}, in name order,
 * which is what keeps overlapping multi-seat requests deadlock-free.
 */
@Repository
public class SeatRepository {

	private final DSLContext dsl;

	public SeatRepository(DSLContext dsl) {
		this.dsl = dsl;
	}

	/**
	 * Inserts every seat as AVAILABLE in one statement with one array parameter, whatever
	 * the hall size: {@code INSERT INTO seats (show_id, seat_name) SELECT ?, t.seat_name FROM unnest(?) AS t(seat_name)}.
	 */
	public void insertAll(UUID showId, List<String> seatNames) {
		Table<?> names = DSL.unnest(seatNames.toArray(String[]::new)).as("t", "seat_name");
		Field<String> seatName = names.field("seat_name", String.class);
		this.dsl.insertInto(SEATS, SEATS.SHOW_ID, SEATS.SEAT_NAME)
			.select(DSL.select(DSL.val(showId), seatName).from(names))
			.execute();
	}

	/** All seats of a show, by name, from a single statement (one consistent snapshot). */
	public List<Seats> findByShow(UUID showId) {
		return this.dsl.selectFrom(SEATS).where(SEATS.SHOW_ID.eq(showId)).orderBy(SEATS.SEAT_NAME).fetchInto(Seats.class);
	}

	/** How many of the named seats exist, and how many of those are still available. */
	public record Availability(int existing, int available) {
	}

	/**
	 * A plain read with no locks, used to turn away requests that cannot succeed before
	 * they write anything. It can be stale by the time the transaction locks the seats, so
	 * it only ever leads to a decline; taking a seat is still decided under the lock.
	 */
	public Availability availability(UUID showId, List<String> seatNames) {
		var row = this.dsl.select(DSL.count(), DSL.count().filterWhere(SEATS.STATUS.eq(SeatStatus.AVAILABLE)))
			.from(SEATS)
			.where(SEATS.SHOW_ID.eq(showId), SEATS.SEAT_NAME.in(seatNames))
			.fetchSingle();
		return new Availability(row.value1(), row.value2());
	}

	/**
	 * {@code SELECT ... ORDER BY seat_name FOR UPDATE}: row-locks the named seats in name
	 * order. A waiter re-reads each row once the holder commits.
	 * @return how many of the named seats exist (fewer means an unknown seat name)
	 */
	public int lockInOrder(UUID showId, List<String> seatNames) {
		return this.dsl.select(SEATS.SEAT_NAME)
			.from(SEATS)
			.where(SEATS.SHOW_ID.eq(showId), SEATS.SEAT_NAME.in(seatNames))
			.orderBy(SEATS.SEAT_NAME)
			.forUpdate()
			.fetch()
			.size();
	}

	/**
	 * Guarded update, {@code ... WHERE status = 'AVAILABLE'}: assigns only seats that are
	 * still available.
	 * @return how many seats were assigned (fewer than requested means one was taken)
	 */
	public int assignIfAvailable(UUID showId, List<String> seatNames, UUID reservationId, String userId,
			SeatStatus status) {
		return this.dsl.update(SEATS)
			.set(SEATS.STATUS, status)
			.set(SEATS.USER_ID, userId)
			.set(SEATS.RESERVATION_ID, reservationId)
			.where(SEATS.SHOW_ID.eq(showId), SEATS.SEAT_NAME.in(seatNames), SEATS.STATUS.eq(SeatStatus.AVAILABLE))
			.execute();
	}

	/** Marks the reservation's own seats CONFIRMED (matched by reservation id). */
	public void confirmFor(UUID showId, List<String> seatNames, UUID reservationId) {
		this.dsl.update(SEATS)
			.set(SEATS.STATUS, SeatStatus.CONFIRMED)
			.where(SEATS.SHOW_ID.eq(showId), SEATS.SEAT_NAME.in(seatNames), SEATS.RESERVATION_ID.eq(reservationId))
			.execute();
	}

	/**
	 * Returns the reservation's seats to AVAILABLE. Matching on reservation id means a
	 * release can never free a seat that now belongs to someone else.
	 */
	public void freeFor(UUID showId, List<String> seatNames, UUID reservationId) {
		this.dsl.update(SEATS)
			.set(SEATS.STATUS, SeatStatus.AVAILABLE)
			.setNull(SEATS.USER_ID)
			.setNull(SEATS.RESERVATION_ID)
			.where(SEATS.SHOW_ID.eq(showId), SEATS.SEAT_NAME.in(seatNames), SEATS.RESERVATION_ID.eq(reservationId))
			.execute();
	}

	/** Seat counts per status for shows created after {@code since}, from one GROUP BY. */
	public Map<UUID, Map<SeatStatus, Integer>> countByStatusForShowsCreatedAfter(OffsetDateTime since) {
		Map<UUID, Map<SeatStatus, Integer>> counts = new LinkedHashMap<>();
		this.dsl.select(SEATS.SHOW_ID, SEATS.STATUS, DSL.count())
			.from(SEATS)
			.join(SHOWS).on(SHOWS.ID.eq(SEATS.SHOW_ID))
			.where(SHOWS.CREATED_AT.gt(since))
			.groupBy(SEATS.SHOW_ID, SEATS.STATUS)
			.forEach(r -> counts.computeIfAbsent(r.value1(), id -> new EnumMap<>(SeatStatus.class))
				.put(r.value2(), r.value3()));
		return counts;
	}

}
