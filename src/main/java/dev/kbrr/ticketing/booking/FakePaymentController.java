package dev.kbrr.ticketing.booking;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stand-in for the payment webhook until M4 (then removed); only exists when fake payments are enabled. */
@RestController
@ConditionalOnBooleanProperty("ticketing.fake-payments.enabled")
class FakePaymentController {

	private final BookingService bookings;

	FakePaymentController(BookingService bookings) {
		this.bookings = bookings;
	}

	@PostMapping("/api/bookings/{id}/fake-pay")
	BookingResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return bookings.confirmFake(UUID.fromString(jwt.getSubject()), id);
	}
}
