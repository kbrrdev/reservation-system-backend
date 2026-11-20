package dev.kbrr.ticketing.payment;

/** A correctly signed webhook whose body is unparseable or lacks the event id or type; retrying cannot fix it. */
public class MalformedWebhookException extends RuntimeException {

	public MalformedWebhookException(String message) {
		super(message);
	}

	public MalformedWebhookException(String message, Throwable cause) {
		super(message, cause);
	}
}
