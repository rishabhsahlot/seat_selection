package com.paytm.seat_selection.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Tag;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runs the real application on a random port against a real Postgres (one container for
 * the whole test run), and talks to it over HTTP the way a client would.
 * <p>
 * Tagged "integration" (inherited by every subclass) because these tests need Docker; the
 * image build runs only the unit tests with {@code -DexcludedGroups=integration}.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

	protected static final String ADMIN_KEY = "dev-admin-key";

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("app.admin-api-key", () -> ADMIN_KEY);
	}

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	private int port;

	protected record Response(int status, JsonNode body, HttpResponse<String> raw) {

		public String field(String name) {
			JsonNode value = this.body.get(name);
			return (value == null || value.isNull()) ? null : value.asString();
		}

		public String header(String name) {
			return this.raw.headers().firstValue(name).orElse(null);
		}

	}

	protected Response send(String method, String path, String token, Object body, Map<String, String> headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path))
			.header("Content-Type", "application/json")
			.method(method, (body != null) ? HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))
					: HttpRequest.BodyPublishers.noBody());
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		headers.forEach(request::header);
		try {
			HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
			JsonNode json = response.body().isEmpty() ? JSON.nullNode() : JSON.readTree(response.body());
			return new Response(response.statusCode(), json, response);
		}
		catch (Exception ex) {
			throw new IllegalStateException(method + " " + path + " failed", ex);
		}
	}

	/**
	 * Tests share one database, and idempotency keys are unique per user, so every test
	 * gets its own users: "alice" in one test is a different user from "alice" in another.
	 */
	private final String testRun = UUID.randomUUID().toString().substring(0, 8);

	protected String userId(String name) {
		return name + "-" + this.testRun;
	}

	protected String token(String name) {
		return send("POST", "/auth/token", null, Map.of("user_id", userId(name)), Map.of()).field("access_token");
	}

	/** Creates a show and returns its id. */
	protected String createShow(int perUserLimit, String... seats) {
		Response response = send("POST", "/shows", null, Map.of("name", "test", "seats", List.of(seats),
				"price_paise", 25000, "per_user_limit", perUserLimit), Map.of("X-Admin-Key", ADMIN_KEY));
		if (response.status() != 201) {
			throw new IllegalStateException("could not create show: " + response.raw().body());
		}
		return response.field("id");
	}

	protected Response reserve(String token, String showId, String key, String... seats) {
		return send("POST", "/shows/" + showId + "/reserve", token,
				Map.of("seats", List.of(seats), "idempotency_key", key), Map.of());
	}

	protected Response hold(String token, String showId, String key, String... seats) {
		return send("POST", "/shows/" + showId + "/reserve", token,
				Map.of("seats", List.of(seats), "idempotency_key", key, "mode", "hold"), Map.of());
	}

	protected Response show(String showId) {
		return send("GET", "/shows/" + showId, null, null, Map.of());
	}

	/** A seat's status in GET /shows/{id}: "available", "held" or "confirmed". */
	protected String seatStatus(String showId, String seat) {
		for (JsonNode s : show(showId).body().get("seats")) {
			if (s.get("seat_name").asString().equals(seat)) {
				return s.get("status").asString();
			}
		}
		throw new IllegalArgumentException("no seat " + seat);
	}

	/** Releases every task at the same instant and waits for all of them. */
	protected static List<Response> concurrently(List<Callable<Response>> tasks) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
			List<Future<Response>> futures = new ArrayList<>();
			for (Callable<Response> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<Response> results = new ArrayList<>();
			for (Future<Response> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

	protected static long countStatus(List<Response> responses, int status) {
		return responses.stream().filter(r -> r.status() == status).count();
	}

	protected static long countReason(List<Response> responses, String reason) {
		return responses.stream().filter(r -> reason.equals(r.field("reason"))).count();
	}

}
