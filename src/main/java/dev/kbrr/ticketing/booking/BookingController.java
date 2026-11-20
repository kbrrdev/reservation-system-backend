package dev.kbrr.ticketing.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
class BookingController {

	private final BookingService bookings;

	private final BookingCheckout checkout;

	BookingController(BookingService bookings, BookingCheckout checkout) {
		this.bookings = bookings;
		this.checkout = checkout;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	BookingResponse hold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody HoldRequest req) {
		return checkout.holdAndCheckout(UUID.fromString(jwt.getSubject()), req);
	}

	/** Own purchases only, newest first; the client cannot pick a sort field. */
	@GetMapping
	PagedModel<BookingResponse> list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new PagedModel<>(bookings.list(UUID.fromString(jwt.getSubject()), page, size));
	}

	@GetMapping("/{id}")
	BookingResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return bookings.get(UUID.fromString(jwt.getSubject()), id);
	}

	@GetMapping("/{id}/tickets")
	List<TicketResponse> tickets(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return bookings.tickets(UUID.fromString(jwt.getSubject()), id);
	}

	/** Cancels the caller's own pending booking and releases its tickets; confirmed bookings can't be cancelled. */
	@DeleteMapping("/{id}")
	BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return bookings.cancel(UUID.fromString(jwt.getSubject()), id);
	}
}
