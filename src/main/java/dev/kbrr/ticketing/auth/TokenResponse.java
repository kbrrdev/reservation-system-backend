package dev.kbrr.ticketing.auth;

import java.time.Instant;

record TokenResponse(String accessToken, String tokenType, Instant expiresAt) {

	static TokenResponse from(IssuedToken t) {
		return new TokenResponse(t.token(), "Bearer", t.expiresAt());
	}
}
