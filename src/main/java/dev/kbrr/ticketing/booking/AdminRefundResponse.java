package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.Money;
import java.util.UUID;

record AdminRefundResponse(UUID id, UUID userId, UUID eventId, BookingStatus status, Money total,
		String paymentProvider, String paymentReference, boolean refundRequired, long version) {

	static AdminRefundResponse from(Booking b) {
		return new AdminRefundResponse(b.getId(), b.getUserId(), b.getEventId(), b.getStatus(),
				new Money(b.getTotalMinor(), b.getCurrency()), b.getPaymentProvider(), b.getPaymentReference(),
				b.isRefundRequired(), b.getVersion());
	}
}
