package dev.kbrr.ticketing.payment.paymongo;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing.payments.paymongo")
public record PaymongoProperties(String secretKey, String webhookSecret, URI baseUrl, URI successUrl, URI cancelUrl,
		List<String> paymentMethodTypes, boolean live) {
}
