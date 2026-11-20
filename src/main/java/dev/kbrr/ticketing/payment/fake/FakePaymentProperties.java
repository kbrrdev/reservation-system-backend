package dev.kbrr.ticketing.payment.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing.payments.fake")
public record FakePaymentProperties(boolean enabled, String webhookSecret) {
}
