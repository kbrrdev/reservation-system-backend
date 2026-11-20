package dev.kbrr.ticketing.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing.booking")
public record BookingProperties(Duration holdDuration, int defaultMaxTicketsPerCustomer,
		int platformMaxTicketsPerCustomer, Duration expiryInterval) {
}
