package dev.kbrr.ticketing.auth;

import java.time.Instant;

record IssuedToken(String token, Instant expiresAt) {
}
