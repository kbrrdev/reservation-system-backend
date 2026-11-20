package dev.kbrr.ticketing.payment.fake;

import dev.kbrr.ticketing.payment.Checkout;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.payment.InvalidSignatureException;
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
		JsonNode n = JSON.readTree(body);
		String type = text(n, "type");
		Long amount = n.hasNonNull("amountMinor") && n.get("amountMinor").canConvertToLong()
				? n.get("amountMinor").asLong() : null;
		return new PaymentNotification("fake", text(n, "id"), type, "payment.paid".equals(type),
				uuid(text(n, "bookingId")), amount, text(n, "currency"), new String(body, StandardCharsets.UTF_8));
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
