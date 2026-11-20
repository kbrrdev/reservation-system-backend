package dev.kbrr.ticketing.payment;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately NOT transactional: when a duplicate is detected the handler's transaction is already rollback-only,
 * so the duplicate check below runs in a fresh one. Any non-2xx makes the provider retry.
 */
@RestController
class PaymentWebhookController {

	private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

	private final PaymentGateways gateways;
	private final PaymentNotificationHandler handler;
	private final PaymentEventRepository paymentEvents;

	PaymentWebhookController(PaymentGateways gateways, PaymentNotificationHandler handler,
			PaymentEventRepository paymentEvents) {
		this.gateways = gateways;
		this.handler = handler;
		this.paymentEvents = paymentEvents;
	}

	/** Raw bytes: the signature is verified over exactly what the provider sent, before any JSON parsing. */
	@PostMapping("/api/payments/webhook/{provider}")
	ResponseEntity<Void> receive(@PathVariable String provider, @RequestBody byte[] body,
			@RequestHeader HttpHeaders headers) {
		PaymentGateway gateway = gateways.find(provider)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Unknown payment provider"));
		PaymentNotification n;
		try {
			n = gateway.parseWebhook(body, headers);
		} catch (InvalidSignatureException e) {
			throw new ApiException(ErrorCode.INVALID_SIGNATURE, "Invalid webhook signature");
		} catch (MalformedWebhookException e) {
			// Signed but unusable: 400 so it is not retried forever, error log so a human sees it, nothing written.
			log.error("malformed {} webhook: {}", provider, e.getMessage(), e);
			throw new ApiException(ErrorCode.MALFORMED_REQUEST, "Malformed webhook payload");
		}
		try {
			handler.handle(n);
		} catch (DataIntegrityViolationException e) {
			// Only the (provider, event id) key means "already processed"; any other constraint is a real failure.
			if (!paymentEvents.existsByProviderAndProviderEventId(n.provider(), n.providerEventId())) {
				throw e;
			}
			log.info("duplicate delivery {} {}", n.provider(), n.providerEventId());
		}
		return ResponseEntity.ok().build();
	}
}
