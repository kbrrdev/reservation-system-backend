package dev.kbrr.ticketing.payment.paymongo;

import dev.kbrr.ticketing.payment.Checkout;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.payment.InvalidSignatureException;
import dev.kbrr.ticketing.payment.MalformedWebhookException;
import dev.kbrr.ticketing.payment.PaymentGateway;
import dev.kbrr.ticketing.payment.PaymentNotification;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PayMongo checkout sessions + signed webhooks. Connect/read timeouts come from
 * {@code spring.http.clients.*} (the auto-configured builder), not from code.
 */
@Component
public class PaymongoGateway implements PaymentGateway {

	private final PaymongoProperties props;
	private final RestClient client;
	private final JsonMapper json;

	public PaymongoGateway(PaymongoProperties props, RestClient.Builder builder, JsonMapper json) {
		this.props = props;
		this.json = json;
		this.client = builder.baseUrl(props.baseUrl().toString()).build();
	}

	@Override
	public String provider() {
		return "paymongo";
	}

	@Override
	public Checkout createCheckout(CheckoutRequest req) {
		if (props.secretKey() == null || props.secretKey().isBlank()) {
			throw new IllegalStateException("PayMongo secret key not configured");
		}
		Map<String, Object> attributes = Map.of(
				"line_items", List.of(Map.of("name", req.description(), "amount", req.amountMinor(),
						"currency", req.currency(), "quantity", 1)),
				"payment_method_types", props.paymentMethodTypes(),
				"success_url", props.successUrl().toString(),
				"cancel_url", props.cancelUrl().toString(),
				"description", req.description(),
				"reference_number", req.bookingId().toString(),
				"show_line_items", true,
				"metadata", Map.of("booking_id", req.bookingId().toString()));
		JsonNode res = client.post().uri("/v1/checkout_sessions").headers(h -> h.setBasicAuth(props.secretKey(), ""))
				.body(Map.of("data", Map.of("attributes", attributes))).retrieve().body(JsonNode.class);
		return new Checkout(res.at("/data/id").asString(), res.at("/data/attributes/checkout_url").asString());
	}

	@Override
	public void expireCheckout(String sessionId) {
		client.post().uri("/v1/checkout_sessions/{id}/expire", sessionId)
				.headers(h -> h.setBasicAuth(props.secretKey(), "")).retrieve().toBodilessEntity();
	}

	@Override
	public PaymentNotification parseWebhook(byte[] body, HttpHeaders headers) {
		verify(new String(body, StandardCharsets.UTF_8), headers.getFirst("Paymongo-Signature"));
		JsonNode root;
		try {
			root = json.readTree(body);
		} catch (JacksonException e) {
			throw new MalformedWebhookException("webhook body is not JSON", e);
		}
		String id = text(root.at("/data/id"));
		String type = text(root.at("/data/attributes/type"));
		if (id == null || type == null) {
			throw new MalformedWebhookException("webhook lacks data.id or data.attributes.type");
		}
		JsonNode session = root.at("/data/attributes/data/attributes");
		JsonNode payment = session.at("/payments/0/attributes");
		JsonNode amount = payment.path("amount");
		return new PaymentNotification("paymongo", id, type, "checkout_session.payment.paid".equals(type),
				uuid(text(session.at("/metadata/booking_id"))), amount.canConvertToLong() ? amount.asLong() : null,
				text(payment.path("currency")), new String(body, StandardCharsets.UTF_8));
	}

	/** Header form {@code t=<ts>,te=<hex>,li=<hex>}; HMAC-SHA256 over {@code t.body}, test (te) or live (li) part. */
	private void verify(String rawBody, String header) {
		String secret = props.webhookSecret();
		if (secret == null || secret.isBlank() || header == null) {
			throw new InvalidSignatureException("webhook secret or signature header missing");
		}
		String t = null;
		String sig = null;
		for (String part : header.split(",")) {
			String[] kv = part.split("=", 2);
			if (kv.length == 2 && kv[0].trim().equals("t")) {
				t = kv[1].trim();
			} else if (kv.length == 2 && kv[0].trim().equals(props.live() ? "li" : "te")) {
				sig = kv[1].trim();
			}
		}
		if (t == null || sig == null || sig.isEmpty()) {
			throw new InvalidSignatureException("signature header incomplete");
		}
		String expected = hmacHex(secret, t + "." + rawBody);
		if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sig.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
			throw new InvalidSignatureException("bad paymongo signature");
		}
	}

	private static String hmacHex(String secret, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String text(JsonNode n) {
		return n.isString() && !n.asString().isBlank() ? n.asString() : null;
	}

	private static UUID uuid(String s) {
		try {
			return s == null ? null : UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
