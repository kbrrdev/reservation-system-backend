package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

record BookingResponse(UUID id, UUID eventId, BookingType type, BookingStatus status, List<Item> items, Money total,
		Instant expiresAt, Instant createdAt, String checkoutUrl, List<UUID> eventSeatIds) {

	record Item(UUID ticketTypeId, int quantity, Money unitPrice) {
	}

	/** Call inside a transaction: items and seats are lazy. */
	static BookingResponse from(Booking b) {
		return new BookingResponse(b.getId(), b.getEventId(), b.getType(), b.getStatus(),
				b.getItems().stream().map(i -> new Item(i.getTicketTypeId(), i.getQuantity(),
						new Money(i.getUnitPriceMinor(), b.getCurrency()))).toList(),
				new Money(b.getTotalMinor(), b.getCurrency()), b.getExpiresAt(), b.getCreatedAt(), b.getCheckoutUrl(),
				b.getEventSeatIds().stream().sorted().toList());
	}
}
