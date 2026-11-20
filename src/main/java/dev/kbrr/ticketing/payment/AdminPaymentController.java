package dev.kbrr.ticketing.payment;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Paid webhooks that matched no booking: money received that the system can't attribute, so an admin refunds it. */
@RestController
@RequestMapping("/api/admin/payments")
class AdminPaymentController {

	private final PaymentEventRepository events;

	AdminPaymentController(PaymentEventRepository events) {
		this.events = events;
	}

	/** Newest first (the query orders by receivedAt); the page's own sort is only a stable tie-break. */
	@GetMapping("/unmatched")
	@Transactional(readOnly = true)
	PagedModel<UnmatchedPaymentResponse> unmatched(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new PagedModel<>(events.findUnmatched(PageRequest.of(page, size, Sort.by("id"))).map(UnmatchedPaymentResponse::from));
	}

	/** The guarded update decides; the existence check only tells 404 from "already handled". */
	@PostMapping("/{id}/refund-handled")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	void refundHandled(@PathVariable UUID id) {
		if (events.markRefundHandled(id) == 0) {
			if (!events.existsById(id)) {
				throw new ApiException(ErrorCode.NOT_FOUND, "Payment not found");
			}
			throw new ApiException(ErrorCode.REFUND_ALREADY_HANDLED, "Nothing to refund for this payment");
		}
	}
}
