package com.paytm.seat_selection.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.paytm.seat_selection.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
		return body(ex.status(), ex.reason(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<Map<String, Object>> handleInvalid(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
			.map(e -> toSnakeCase(e.getField()) + " " + e.getDefaultMessage())
			.findFirst()
			.orElse("invalid request");
		return body(HttpStatus.UNPROCESSABLE_CONTENT, "validation", message);
	}

	@ExceptionHandler({ HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			MissingRequestHeaderException.class })
	ResponseEntity<Map<String, Object>> handleMalformed(Exception ex) {
		log.debug("malformed request: {}", ex.getMessage());
		return body(HttpStatus.BAD_REQUEST, "malformed_request", "malformed request");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
		return body(HttpStatus.NOT_FOUND, "not_found", "no such endpoint");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
		log.error("unhandled error", ex);
		return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "internal error");
	}

	/** Field names in messages match the JSON the client sent (pricePaise -> price_paise). */
	private static String toSnakeCase(String field) {
		return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
	}

	static ResponseEntity<Map<String, Object>> body(HttpStatus status, String reason, String message) {
		return ResponseEntity.status(status).body(errorBody(reason, message));
	}

	/** The single error shape for every 4xx/5xx, including those written by Spring Security. */
	public static Map<String, Object> errorBody(String reason, String message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("error", message);
		body.put("reason", reason);
		body.put("request_id", MDC.get(RequestIdFilter.MDC_KEY));
		return body;
	}

}
