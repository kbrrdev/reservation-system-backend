package dev.kbrr.ticketing.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
	VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
	MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
	FORBIDDEN(HttpStatus.FORBIDDEN),
	NOT_FOUND(HttpStatus.NOT_FOUND),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
	EMAIL_TAKEN(HttpStatus.CONFLICT),
	STALE_UPDATE(HttpStatus.CONFLICT),
	EVENT_NOT_ON_SALE(HttpStatus.CONFLICT),
	TICKET_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
	PENDING_HOLD_EXISTS(HttpStatus.CONFLICT),
	PREVIOUS_HOLD_RELEASING(HttpStatus.CONFLICT),
	TICKETS_UNAVAILABLE(HttpStatus.CONFLICT),
	BOOKING_NOT_PENDING(HttpStatus.CONFLICT),
	VENUE_IN_USE(HttpStatus.CONFLICT),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

	private final HttpStatus status;

	ErrorCode(HttpStatus status) {
		this.status = status;
	}

	public HttpStatus status() {
		return status;
	}
}
