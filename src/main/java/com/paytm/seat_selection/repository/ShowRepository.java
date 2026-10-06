package com.paytm.seat_selection.repository;

import static com.paytm.seat_selection.jooq.Tables.SHOWS;

import java.util.Optional;
import java.util.UUID;

import com.paytm.seat_selection.jooq.tables.pojos.Shows;
import org.jooq.DSLContext;

import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

	private final DSLContext dsl;

	public ShowRepository(DSLContext dsl) {
		this.dsl = dsl;
	}

	public void insert(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats) {
		this.dsl.insertInto(SHOWS)
			.set(SHOWS.ID, id)
			.set(SHOWS.NAME, name)
			.set(SHOWS.PRICE_PAISE, pricePaise)
			.set(SHOWS.PER_USER_LIMIT, perUserLimit)
			.set(SHOWS.TOTAL_SEATS, totalSeats)
			.execute();
	}

	public Optional<Shows> findById(UUID id) {
		return this.dsl.selectFrom(SHOWS).where(SHOWS.ID.eq(id)).fetchOptionalInto(Shows.class);
	}

}
