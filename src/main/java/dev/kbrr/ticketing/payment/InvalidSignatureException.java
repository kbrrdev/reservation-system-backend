package dev.kbrr.ticketing.payment;

public class InvalidSignatureException extends RuntimeException {

	public InvalidSignatureException(String message) {
		super(message);
	}
}
