package com.paytm.seat_selection.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import jakarta.validation.Valid;

import com.paytm.seat_selection.config.AppProperties;
import com.paytm.seat_selection.dto.request.CreateShowRequest;
import com.paytm.seat_selection.dto.response.ShowResponse;
import com.paytm.seat_selection.service.ShowService;
import com.paytm.seat_selection.web.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ShowController {

	private final ShowService shows;

	private final AppProperties props;

	public ShowController(ShowService shows, AppProperties props) {
		this.shows = shows;
		this.props = props;
	}

	@PostMapping("/shows")
	@ResponseStatus(HttpStatus.CREATED)
	public ShowResponse create(@RequestHeader(name = "X-Admin-Key", required = false) String adminKey,
			@Valid @RequestBody CreateShowRequest request) {
		requireAdmin(adminKey);
		return this.shows.create(request);
	}

	@GetMapping("/shows/{id}")
	public ShowResponse get(@PathVariable UUID id) {
		return this.shows.get(id);
	}

	private void requireAdmin(String adminKey) {
		byte[] expected = this.props.adminApiKey().getBytes(StandardCharsets.UTF_8);
		byte[] actual = (adminKey != null) ? adminKey.getBytes(StandardCharsets.UTF_8) : new byte[0];
		if (!MessageDigest.isEqual(expected, actual)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "forbidden", "admin key required");
		}
	}

}
