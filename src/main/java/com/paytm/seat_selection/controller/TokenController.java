package com.paytm.seat_selection.controller;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.validation.Valid;

import com.paytm.seat_selection.dto.request.TokenRequest;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo token issuer so testers can act as many users. A real deployment would delegate
 * this to an identity provider; the rest of the service only trusts the signed subject.
 */
@RestController
public class TokenController {

	private static final Duration TTL = Duration.ofHours(12);

	private final JwtEncoder encoder;

	public TokenController(JwtEncoder encoder) {
		this.encoder = encoder;
	}

	@PostMapping("/auth/token")
	public Map<String, Object> token(@Valid @RequestBody TokenRequest request) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("seat-selection-demo")
			.subject(request.userId())
			.issuedAt(now)
			.expiresAt(now.plus(TTL))
			.build();
		String token = this.encoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return Map.of("access_token", token, "token_type", "Bearer", "user_id", request.userId(), "expires_in",
				TTL.toSeconds());
	}

}
