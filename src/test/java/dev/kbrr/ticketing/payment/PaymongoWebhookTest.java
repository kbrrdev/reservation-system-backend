package dev.kbrr.ticketing.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.user.User;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PaymongoWebhookTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired JdbcTemplate jdbc;

	@Test
	void signedPaymongoWebhookProviderMismatchFlagsRefund() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 10, 150000);
		String res = mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(fx.customer()))
				.contentType(APPLICATION_JSON).content("{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}]}"
						.formatted(e.id(), e.ticketTypes().getFirst().id())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(res, "$.id"));
		Long total = jdbc.queryForObject("select total_minor from bookings where id = ?", Long.class, id);

		String body;
		try (var in = getClass().getResourceAsStream("/paymongo/checkout_session_payment_paid.json")) {
			body = new String(in.readAllBytes(), StandardCharsets.UTF_8)
					.replace("11111111-1111-1111-1111-111111111111", id.toString())
					.replace("250000", String.valueOf(total))
					.replace("evt_test_1", "evt_" + UUID.randomUUID());
		}
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("whsk_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		String sig = HexFormat.of().formatHex(mac.doFinal(("1700000000." + body).getBytes(StandardCharsets.UTF_8)));

		mvc.perform(post("/api/payments/webhook/paymongo").header("Paymongo-Signature", "t=1700000000,te=" + sig + ",li=")
				.contentType(APPLICATION_JSON).content(body)).andExpect(status().isOk());

		Map<String, Object> row = jdbc.queryForMap("select status, refund_required from bookings where id = ?", id);
		assertThat(row.get("status")).isEqualTo("PENDING_PAYMENT");
		assertThat(row.get("refund_required")).isEqualTo(true);
	}

	@Test
	void unsignedPaymongoWebhook401() throws Exception {
		mvc.perform(post("/api/payments/webhook/paymongo").contentType(APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
	}
}
