package dev.kbrr.ticketing.payment;

/** Applies a verified notification. Records it first, so a duplicate delivery fails on the unique key. */
public interface PaymentNotificationHandler {

	void handle(PaymentNotification n);
}
