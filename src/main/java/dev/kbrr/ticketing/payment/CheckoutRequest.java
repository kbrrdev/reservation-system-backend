package dev.kbrr.ticketing.payment;

import java.util.UUID;

public record CheckoutRequest(UUID bookingId, long amountMinor, String currency, String description) {
}
