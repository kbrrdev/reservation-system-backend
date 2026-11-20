package dev.kbrr.ticketing.payment.fake;

import dev.kbrr.ticketing.payment.Checkout;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.payment.InvalidSignatureException;
import dev.kbrr.ticketing.payment.MalformedWebhookException;
import dev.kbrr.ticketing.payment.PaymentGateway;
import dev.kbrr.ticketing.payment.PaymentNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** In-memory gateway for dev and tests: records calls, verifies a shared-secret header. */
@Component
@ConditionalOnBooleanProperty("ticketing.payments.fake.enabled")
public class FakePaymentGateway implements PaymentGateway {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final FakePaymentProperties props;
	private final List<CheckoutRequest> checkouts = new CopyOnWriteArrayList<>();
	private final List<String> expired = new CopyOnWriteArrayList<>();
	private final AtomicBoolean failNext = new AtomicBoolean();

	public FakePaymentGateway(FakePaymentProperties props) {
		this.props = props;
	}

	@Override
	public String provider() {
		return "fake";
	}

	@Override
	public Checkout createCheckout(CheckoutRequest req) {
		if (failNext.getAndSet(false)) {
			throw new IllegalStateException("fake provider down");
		}
		checkouts.add(req);
		return new Checkout("fake_cs_" + req.bookingId(), "https://checkout.fake.test/fake_cs_" + req.bookingId());
	}

	@Override
	public void expireCheckout(String sessionId) {
		expired.add(sessionId);
	}

	@Override
	public PaymentNotification parseWebhook(byte[] body, HttpHeaders headers) {
		String sig = headers.getFirst("Fake-Signature");
		String secret = props.webhookSecret();
		if (sig == null || secret == null || secret.isBlank() || !MessageDigest.isEqual(
				sig.getBytes(StandardCharsets.UTF_8), secret.getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidSignatureException("bad fake signature");
		}
		JsonNode n;
		try {
			n = JSON.readTree(body);
		} catch (JacksonException e) {
			throw new MalformedWebhookException("webhook body is not JSON", e);
		}
		String type = text(n, "type");
		if (text(n, "id") == null || type == null) {
			throw new MalformedWebhookException("webhook lacks id or type");
		}
		Long amount = n.hasNonNull("amountMinor") && n.get("amountMinor").canConvertToLong()
				? n.get("amountMinor").asLong() : null;
		String id = text(n, "id");
		String currency = text(n, "currency");
		if (id.length() > 100 || type.length() > 100 || currency != null && currency.length() != 3) {
			throw new MalformedWebhookException("webhook id, type or currency out of range");
		}
		return new PaymentNotification("fake", id, type, "payment.paid".equals(type),
				uuid(text(n, "bookingId")), amount, currency, new String(body, StandardCharsets.UTF_8));
	}

	public List<CheckoutRequest> checkouts() {
		return List.copyOf(checkouts);
	}

	public List<String> expiredSessions() {
		return List.copyOf(expired);
	}

	public void failNextCheckout() {
		failNext.set(true);
	}

	private static String text(JsonNode n, String field) {
		return n.hasNonNull(field) ? n.get(field).asString() : null;
	}

	private static UUID uuid(String s) {
		try {
			return s == null ? null : UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
