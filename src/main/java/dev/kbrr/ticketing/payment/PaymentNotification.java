package dev.kbrr.ticketing.payment;

import java.util.UUID;

/** {@code bookingId}, {@code amountMinor} and {@code currency} are null when missing or unparseable. */
public record PaymentNotification(String provider, String providerEventId, String type, boolean paid, UUID bookingId,
		Long amountMinor, String currency, String payload) {
}
