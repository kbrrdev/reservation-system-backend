package dev.kbrr.ticketing.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Spec rule 8: the provider is called only after the booking change committed; a failure here never matters. */
@Component
class CheckoutExpiryListener {

	private static final Logger log = LoggerFactory.getLogger(CheckoutExpiryListener.class);

	private final PaymentGateways gateways;

	CheckoutExpiryListener(PaymentGateways gateways) {
		this.gateways = gateways;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void on(CheckoutExpiryRequested e) {
		try {
			gateways.get(e.provider()).expireCheckout(e.sessionId());
		} catch (RuntimeException ex) {
			log.warn("Could not expire checkout {} at {}", e.sessionId(), e.provider(), ex);
		}
	}
}
