package dev.kbrr.ticketing.payment.paymongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.kbrr.ticketing.payment.Checkout;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.payment.InvalidSignatureException;
import dev.kbrr.ticketing.payment.MalformedWebhookException;
import dev.kbrr.ticketing.payment.PaymentNotification;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class PaymongoGatewayTest {

	private static final String BASE = "https://api.paymongo.test";
	private static final String SECRET = "whsk_test_secret";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private MockRestServiceServer server;

	private PaymongoGateway gateway(String secretKey, String webhookSecret, boolean live) {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		return new PaymongoGateway(new PaymongoProperties(secretKey, webhookSecret, URI.create(BASE),
				URI.create("https://app.test/ok"), URI.create("https://app.test/cancel"), List.of("gcash", "card"), live),
				builder, JSON);
	}

	private static String sample() throws Exception {
		try (var in = PaymongoGatewayTest.class.getResourceAsStream("/paymongo/checkout_session_payment_paid.json")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String hmac(String secret, String t, String body) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
	}

	private static HttpHeaders sigHeader(String value) {
		HttpHeaders h = new HttpHeaders();
		if (value != null) {
			h.set("Paymongo-Signature", value);
		}
		return h;
	}

	private static HttpHeaders signed(String secret, String body) throws Exception {
		return sigHeader("t=1700000000,te=" + hmac(secret, "1700000000", body) + ",li=");
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void createCheckoutSendsBodyAndParsesResponse() {
		PaymongoGateway gw = gateway("sk_test_x", SECRET, false);
		UUID id = UUID.randomUUID();
		server.expect(requestTo(BASE + "/v1/checkout_sessions")).andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Basic " + Base64.getEncoder().encodeToString("sk_test_x:".getBytes())))
				.andExpect(jsonPath("$.data.attributes.line_items[0].amount").value(250000))
				.andExpect(jsonPath("$.data.attributes.line_items[0].currency").value("PHP"))
				.andExpect(jsonPath("$.data.attributes.metadata.booking_id").value(id.toString()))
				.andExpect(jsonPath("$.data.attributes.reference_number").value(id.toString()))
				.andRespond(withSuccess("{\"data\":{\"id\":\"cs_123\",\"attributes\":{\"checkout_url\":\"https://checkout.paymongo.com/cs_123\"}}}",
						MediaType.APPLICATION_JSON));

		Checkout c = gw.createCheckout(new CheckoutRequest(id, 250000, "PHP", "Concert"));

		assertThat(c).isEqualTo(new Checkout("cs_123", "https://checkout.paymongo.com/cs_123"));
		server.verify();
	}

	@Test
	void checkoutResponseWithoutIdOrUrlFails() {
		PaymongoGateway gw = gateway("sk_test_x", SECRET, false);
		server.expect(requestTo(BASE + "/v1/checkout_sessions"))
				.andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> gw.createCheckout(new CheckoutRequest(UUID.randomUUID(), 1, "PHP", "x")))
				.isInstanceOf(IllegalStateException.class).hasMessage("PayMongo checkout response lacks id or checkout_url");
	}

	@Test
	void overlongEventTypeThrowsMalformedWebhook() throws Exception {
		String body = sample().replace("checkout_session.payment.paid", "x".repeat(101));
		assertThatThrownBy(() -> gateway("sk_test_x", SECRET, false).parseWebhook(bytes(body), signed(SECRET, body)))
				.isInstanceOf(MalformedWebhookException.class);
	}

	@Test
	void blankSecretKeyFailsWithoutCallingOut() {
		PaymongoGateway gw = gateway("", SECRET, false);
		assertThatThrownBy(() -> gw.createCheckout(new CheckoutRequest(UUID.randomUUID(), 1, "PHP", "x")))
				.isInstanceOf(IllegalStateException.class).hasMessage("PayMongo secret key not configured");
		server.verify();
	}

	@Test
	void expireCheckoutPostsExpire() {
		PaymongoGateway gw = gateway("sk_test_x", SECRET, false);
		server.expect(requestTo(BASE + "/v1/checkout_sessions/cs_123/expire")).andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
		gw.expireCheckout("cs_123");
		server.verify();
	}

	@Test
	void validSignatureParsesPaidEvent() throws Exception {
		String body = sample();
		PaymentNotification n = gateway("sk_test_x", SECRET, false).parseWebhook(bytes(body), signed(SECRET, body));
		assertThat(n.provider()).isEqualTo("paymongo");
		assertThat(n.providerEventId()).isEqualTo("evt_test_1");
		assertThat(n.paid()).isTrue();
		assertThat(n.bookingId()).isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		assertThat(n.amountMinor()).isEqualTo(250000L);
		assertThat(n.currency()).isEqualTo("PHP");
		assertThat(n.payload()).isEqualTo(body);
	}

	@Test
	void reserializedBodyRejected() throws Exception {
		String body = sample();
		String reserialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.readTree(body));
		assertThat(reserialized).isNotEqualTo(body);
		assertThatThrownBy(() -> gateway("sk_test_x", SECRET, false).parseWebhook(bytes(reserialized), signed(SECRET, body)))
				.isInstanceOf(InvalidSignatureException.class);
	}

	@Test
	void wrongSecretMissingHeaderOrLiveMismatchRejected() throws Exception {
		String body = sample();
		PaymongoGateway gw = gateway("sk_test_x", SECRET, false);
		assertThatThrownBy(() -> gw.parseWebhook(bytes(body), signed("other", body))).isInstanceOf(InvalidSignatureException.class);
		assertThatThrownBy(() -> gw.parseWebhook(bytes(body), sigHeader(null))).isInstanceOf(InvalidSignatureException.class);
		assertThatThrownBy(() -> gw.parseWebhook(bytes(body), sigHeader("t=1700000000"))).isInstanceOf(InvalidSignatureException.class);
		// a test-mode (te) signature presented to a live gateway
		assertThatThrownBy(() -> gateway("sk_test_x", SECRET, true).parseWebhook(bytes(body), signed(SECRET, body)))
				.isInstanceOf(InvalidSignatureException.class);
	}

	@Test
	void blankWebhookSecretRejectsEverything() throws Exception {
		String body = sample();
		assertThatThrownBy(() -> gateway("sk_test_x", "", false).parseWebhook(bytes(body), sigHeader("t=1700000000,te=" + hmac("x", "1700000000", body))))
				.isInstanceOf(InvalidSignatureException.class);
	}

	@Test
	void nonPaidTypeAndBadMetadataParse() throws Exception {
		String body = sample().replace("checkout_session.payment.paid", "payment.failed")
				.replace("11111111-1111-1111-1111-111111111111", "x");
		PaymentNotification n = gateway("sk_test_x", SECRET, false).parseWebhook(bytes(body), signed(SECRET, body));
		assertThat(n.paid()).isFalse();
		assertThat(n.bookingId()).isNull();
	}

	@Test
	void signedMalformedBodyThrowsMalformedWebhook() throws Exception {
		PaymongoGateway gw = gateway("sk_test_x", SECRET, false);
		String noId = sample().replace("\"id\":\"evt_test_1\",", "");
		assertThatThrownBy(() -> gw.parseWebhook(bytes(noId), signed(SECRET, noId)))
				.isInstanceOf(MalformedWebhookException.class);
		String notJson = "not json";
		assertThatThrownBy(() -> gw.parseWebhook(bytes(notJson), signed(SECRET, notJson)))
				.isInstanceOf(MalformedWebhookException.class);
	}
}
