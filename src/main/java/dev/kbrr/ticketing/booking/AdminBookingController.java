package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/bookings")
class AdminBookingController {

	private final BookingService bookings;

	AdminBookingController(BookingService bookings) {
		this.bookings = bookings;
	}

	/** Only the refund queue exists for now, so the filter is required and must be true. */
	@GetMapping
	PagedModel<AdminRefundResponse> refundRequired(@RequestParam boolean refundRequired,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		if (!refundRequired) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "Only refundRequired=true is supported");
		}
		return new PagedModel<>(bookings.listRefundRequired(page, size));
	}

	@PostMapping("/{id}/refund-handled")
	AdminRefundResponse refundHandled(@PathVariable UUID id, @Valid @RequestBody RefundHandledRequest req) {
		return bookings.refundHandled(id, req.version());
	}
}
