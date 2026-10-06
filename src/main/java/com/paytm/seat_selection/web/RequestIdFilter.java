package com.paytm.seat_selection.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads or generates X-Request-Id, exposes it in MDC (structured logs) and echoes it back,
 * then writes one access-log line per request. Runs before Spring Security, so rejected
 * (401/403) requests are logged too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	public static final String MDC_KEY = "request_id";

	private static final Logger access = LoggerFactory.getLogger("access");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = request.getHeader(HEADER);
		if (id == null || id.isBlank() || id.length() > 100) {
			id = UUID.randomUUID().toString();
		}
		MDC.put(MDC_KEY, id);
		response.setHeader(HEADER, id);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			logAccess(request, response, (System.nanoTime() - start) / 1_000_000);
			MDC.remove(MDC_KEY);
		}
	}

	private static void logAccess(HttpServletRequest request, HttpServletResponse response, long durationMs) {
		int status = response.getStatus();
		// Health checks and metric scrapes run every few seconds; keep them out of the INFO stream.
		boolean probe = request.getRequestURI().startsWith("/actuator/");
		Level level = (status >= 500) ? Level.ERROR : probe ? Level.DEBUG : Level.INFO;
		access.atLevel(level)
			.addKeyValue("http_method", request.getMethod())
			.addKeyValue("http_path", request.getRequestURI())
			.addKeyValue("http_status", status)
			.addKeyValue("duration_ms", durationMs)
			.log("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, durationMs);
	}

}
