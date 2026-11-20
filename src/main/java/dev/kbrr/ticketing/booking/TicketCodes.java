package dev.kbrr.ticketing.booking;

import java.security.SecureRandom;
import java.util.Base64;

/** 128 random bits: unguessable, so a code can be shown as a QR and checked at the door without a signature. */
final class TicketCodes {

	private static final SecureRandom RANDOM = new SecureRandom();

	private TicketCodes() {
	}

	/** 22 URL-safe characters (16 bytes, Base64 without padding). */
	static String newCode() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
