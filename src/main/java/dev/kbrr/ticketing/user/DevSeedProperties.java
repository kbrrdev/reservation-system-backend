package dev.kbrr.ticketing.user;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing.dev-seed")
public record DevSeedProperties(String password) {
}
