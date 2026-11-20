package dev.kbrr.ticketing.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JwtPropertiesTest {

	private static final String SECRET = "0123456789abcdef0123456789abcdef";

	@Test
	void rejectsMissingOrNonPositiveTtl() {
		assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, null));
		assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new JwtProperties(SECRET, Duration.ofMinutes(-1)));
	}

	@Test
	void acceptsPositiveTtl() {
		assertDoesNotThrow(() -> new JwtProperties(SECRET, Duration.ofMinutes(15)));
	}
}
