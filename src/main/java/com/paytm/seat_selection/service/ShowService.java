package com.paytm.seat_selection.service;

import static com.paytm.seat_selection.jooq.tables.Seats.SEATS;
import static com.paytm.seat_selection.jooq.tables.Shows.SHOWS;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.paytm.seat_selection.config.AppProperties;
import com.paytm.seat_selection.dto.request.CreateShowRequest;
import com.paytm.seat_selection.dto.response.ShowResponse;
import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.jooq.tables.pojos.Shows;
import com.paytm.seat_selection.web.ApiException;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

	private final DSLContext dsl;

	private final AppProperties props;

	public ShowService(DSLContext dsl, AppProperties props) {
		this.dsl = dsl;
		this.props = props;
	}

	@Transactional
	public ShowResponse create(CreateShowRequest request) {
		List<String> seats = request.seats().stream().map(String::trim).toList();
		if (new HashSet<>(seats).size() != seats.size()) {
			throw ApiException.invalid("seat names must be unique");
		}
		int limit = (request.perUserLimit() != null) ? request.perUserLimit() : this.props.defaultPerUserLimit();
		UUID id = UUID.randomUUID();
		this.dsl.insertInto(SHOWS)
				.set(SHOWS.ID, id)
				.set(SHOWS.NAME, request.name())
				.set(SHOWS.PRICE_PAISE, request.pricePaise())
				.set(SHOWS.PER_USER_LIMIT, limit)
				.set(SHOWS.TOTAL_SEATS, seats.size())
				.execute();
		// One statement with one array parameter, whatever the hall size (avoids the
		// bind-parameter limit).
		Table<?> names = DSL.unnest(seats.toArray(String[]::new)).as("t", "seat_name");
		Field<String> seatName = names.field("seat_name", String.class);
		this.dsl.insertInto(SEATS, SEATS.SHOW_ID, SEATS.SEAT_NAME)
				.select(DSL.select(DSL.val(id), seatName).from(names))
				.execute();
		return get(id);
	}

	public Optional<Shows> find(UUID id) {
		return this.dsl.selectFrom(SHOWS).where(SHOWS.ID.eq(id)).fetchOptionalInto(Shows.class);
	}

	/**
	 * Seats and counts come from one statement, so they are a single consistent
	 * snapshot.
	 */
	public ShowResponse get(UUID id) {
		Shows show = find(id).orElseThrow(() -> ApiException.notFound("show"));
		List<ShowResponse.SeatResponse> seats = new ArrayList<>(show.totalSeats());
		Map<SeatStatus, Integer> counts = new EnumMap<>(SeatStatus.class);
		this.dsl.select(SEATS.SEAT_NAME, SEATS.STATUS)
				.from(SEATS)
				.where(SEATS.SHOW_ID.eq(id))
				.orderBy(SEATS.SEAT_NAME)
				.forEach(r -> {
					counts.merge(r.value2(), 1, Integer::sum);
					seats.add(new ShowResponse.SeatResponse(r.value1(), r.value2().getLiteral().toLowerCase()));
				});
		ShowResponse.Counts totals = new ShowResponse.Counts(counts.getOrDefault(SeatStatus.AVAILABLE, 0),
				counts.getOrDefault(SeatStatus.HELD, 0), counts.getOrDefault(SeatStatus.CONFIRMED, 0), seats.size());
		return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(), totals,
				seats);
	}

}
