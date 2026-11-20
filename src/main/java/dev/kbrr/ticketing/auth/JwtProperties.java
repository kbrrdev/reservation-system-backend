package dev.kbrr.ticketing.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing.jwt")
public record JwtProperties(String secret, Duration ttl) {

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalArgumentException("ticketing.jwt.secret must be at least 32 bytes (set JWT_SECRET)");
		}
	}
}
