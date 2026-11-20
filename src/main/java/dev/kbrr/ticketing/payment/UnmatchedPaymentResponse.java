package dev.kbrr.ticketing.payment;

import java.time.Instant;
import java.util.UUID;

record UnmatchedPaymentResponse(UUID id, String provider, String providerEventId, Long amountMinor, String currency,
		Instant receivedAt) {

	static UnmatchedPaymentResponse from(PaymentEvent e) {
		return new UnmatchedPaymentResponse(e.getId(), e.getProvider(), e.getProviderEventId(), e.getAmountMinor(),
				e.getCurrency(), e.getReceivedAt());
	}
}
