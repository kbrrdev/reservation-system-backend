package dev.kbrr.ticketing.payment;

import org.springframework.http.HttpHeaders;

public interface PaymentGateway {

	String provider();

	Checkout createCheckout(CheckoutRequest req);

	void expireCheckout(String sessionId);

	/** Verifies the signature over the raw bytes before parsing; throws {@link InvalidSignatureException} otherwise. */
	PaymentNotification parseWebhook(byte[] body, HttpHeaders headers);
}
