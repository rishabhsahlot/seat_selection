package com.paytm.seat_selection.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.paytm.seat_selection.web.ApiExceptionHandler;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JsonMapper json) throws Exception {
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(o -> o.jwt(jwt -> {
				}).authenticationEntryPoint(jsonError(json, HttpStatus.UNAUTHORIZED, "unauthorized",
						"a valid bearer token is required")))
				.exceptionHandling(e -> e
						.authenticationEntryPoint(jsonError(json, HttpStatus.UNAUTHORIZED, "unauthorized",
								"a valid bearer token is required"))
						.accessDeniedHandler((request, response, ex) -> writeError(response, json, HttpStatus.FORBIDDEN,
								"forbidden", "access denied")));
		return http.build();
	}

	private static AuthenticationEntryPoint jsonError(JsonMapper json, HttpStatus status, String reason,
			String message) {
		return (request, response, ex) -> writeError(response, json, status, reason, message);
	}

	private static void writeError(HttpServletResponse response, JsonMapper json, HttpStatus status, String reason,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		json.writeValue(response.getOutputStream(), ApiExceptionHandler.errorBody(reason, message));
	}

	@Bean
	SecretKey jwtKey(AppProperties props) {
		return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

}
