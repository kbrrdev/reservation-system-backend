package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.payment.Checkout;
import dev.kbrr.ticketing.payment.PaymentGateways;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deliberately NOT transactional (spec rule 8): the hold commits first, then the provider is called with no
 * transaction or row lock open. Each BookingService call goes through the proxy and commits on its own.
 */
@Service
class BookingCheckout {

	private static final Logger log = LoggerFactory.getLogger(BookingCheckout.class);

	private final BookingService bookings;
	private final PaymentGateways gateways;

	BookingCheckout(BookingService bookings, PaymentGateways gateways) {
		this.bookings = bookings;
		this.gateways = gateways;
	}

	BookingResponse holdAndCheckout(UUID userId, HoldRequest req) {
		BookingResponse held = bookings.hold(userId, req);
		UUID id = held.id();
		BookingService.CheckoutData data = bookings.checkoutData(id);
		Checkout checkout;
		try {
			checkout = gateways.get(data.provider()).createCheckout(data.request());
		} catch (RuntimeException e) {
			log.warn("Checkout creation failed for booking {}, cancelling it", id, e);
			bookings.cancelAfterCheckoutFailure(id);
			throw new ApiException(ErrorCode.PAYMENT_PROVIDER_UNAVAILABLE,
					"Payment provider unavailable, please try again");
		}
		try {
			bookings.attachCheckout(id, checkout.sessionId(), checkout.checkoutUrl());
		} catch (RuntimeException e) {
			// The webhook finds the booking through the metadata we sent (spec flow 2), so the customer can still pay.
			log.error("Could not save checkout reference for booking {}", id, e);
		}
		return new BookingResponse(held.id(), held.eventId(), held.type(), held.status(), held.items(), held.total(),
				held.expiresAt(), held.createdAt(), checkout.checkoutUrl(), held.eventSeatIds());
	}
}
