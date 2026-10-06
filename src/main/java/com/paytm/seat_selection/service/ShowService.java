package com.paytm.seat_selection.service;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.paytm.seat_selection.config.AppProperties;
import com.paytm.seat_selection.dto.request.CreateShowRequest;
import com.paytm.seat_selection.dto.response.ShowResponse;
import com.paytm.seat_selection.exception.ApiException;
import com.paytm.seat_selection.jooq.enums.SeatStatus;
import com.paytm.seat_selection.jooq.tables.pojos.Seats;
import com.paytm.seat_selection.jooq.tables.pojos.Shows;
import com.paytm.seat_selection.repository.SeatRepository;
import com.paytm.seat_selection.repository.ShowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShowService {

	private static final Logger log = LoggerFactory.getLogger(ShowService.class);

	private final ShowRepository shows;

	private final SeatRepository seats;

	private final AppProperties props;

	/**
	 * Shows are never updated or deleted after creation, so a show read once can be served
	 * from memory. This saves a database round trip on every reserve request.
	 */
	private final Map<UUID, Shows> cache = new ConcurrentHashMap<>();

	public ShowService(ShowRepository shows, SeatRepository seats, AppProperties props) {
		this.shows = shows;
		this.seats = seats;
		this.props = props;
	}

	/** Creates the show and all its seats (AVAILABLE) in one transaction. */
	@Transactional
	public ShowResponse create(CreateShowRequest request) {
		List<String> seatNames = request.seats().stream().map(String::trim).toList();
		if (new HashSet<>(seatNames).size() != seatNames.size()) {
			throw ApiException.invalid("seat names must be unique");
		}
		int limit = (request.perUserLimit() != null) ? request.perUserLimit() : this.props.defaultPerUserLimit();
		UUID id = UUID.randomUUID();
		this.shows.insert(id, request.name(), request.pricePaise(), limit, seatNames.size());
		this.seats.insertAll(id, seatNames);
		log.atInfo()
			.addKeyValue("show_id", id)
			.addKeyValue("name", request.name())
			.addKeyValue("total_seats", seatNames.size())
			.addKeyValue("per_user_limit", limit)
			.addKeyValue("price_paise", request.pricePaise())
			.log("show created");
		return get(id);
	}

	public Shows require(UUID id) {
		Shows cached = this.cache.get(id);
		if (cached != null) {
			return cached;
		}
		Shows show = this.shows.findById(id).orElseThrow(() -> ApiException.notFound("show"));
		this.cache.put(id, show);
		return show;
	}

	/**
	 * Seats and counts come from one statement, so they are a single consistent snapshot:
	 * available + held + confirmed == total, even mid-burst.
	 */
	public ShowResponse get(UUID id) {
		Shows show = require(id);
		List<Seats> rows = this.seats.findByShow(id);
		Map<SeatStatus, Integer> counts = new EnumMap<>(SeatStatus.class);
		List<ShowResponse.SeatResponse> seatViews = rows.stream().map(seat -> {
			counts.merge(seat.status(), 1, Integer::sum);
			return new ShowResponse.SeatResponse(seat.seatName(), seat.status().getLiteral().toLowerCase());
		}).toList();
		ShowResponse.Counts totals = new ShowResponse.Counts(counts.getOrDefault(SeatStatus.AVAILABLE, 0),
				counts.getOrDefault(SeatStatus.HELD, 0), counts.getOrDefault(SeatStatus.CONFIRMED, 0), rows.size());
		return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(), totals,
				seatViews);
	}

}
