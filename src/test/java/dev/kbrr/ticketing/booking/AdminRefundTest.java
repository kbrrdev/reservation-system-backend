package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class AdminRefundTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired JdbcTemplate jdbc;

	/** A confirmed booking paid twice, so refund_required is set. */
	private UUID doublePaid() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		EventResponse e = fx.gaEvent(org, v, 10, 150000);
		String body = mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(fx.customer()))
				.contentType(APPLICATION_JSON).content("{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}]}"
						.formatted(e.id(), e.ticketTypes().getFirst().id())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		assertThat(fx.pay(id).getStatus()).isEqualTo(200);
		assertThat(fx.pay(id).getStatus()).isEqualTo(200);
		return id;
	}

	private String adminToken() {
		return fx.bearer(fx.admin());
	}

	private String refundList(String token) throws Exception {
		return mvc.perform(get("/api/admin/bookings?refundRequired=true&size=100").header("Authorization", token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private long version(String token, UUID id) throws Exception {
		List<Number> v = JsonPath.read(refundList(token), "$.content[?(@.id=='%s')].version".formatted(id));
		assertThat(v).hasSize(1);
		return v.getFirst().longValue();
	}

	private org.springframework.test.web.servlet.ResultActions handle(String token, UUID id, long version)
			throws Exception {
		return mvc.perform(post("/api/admin/bookings/{id}/refund-handled", id).header("Authorization", token)
				.contentType(APPLICATION_JSON).content("{\"version\":" + version + "}"));
	}

	@Test
	void listsAndClearsRefundFlag() throws Exception {
		String token = adminToken();
		UUID id = doublePaid();
		long v = version(token, id);

		handle(token, id, v).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.refundRequired").value(false));

		List<String> ids = JsonPath.read(refundList(token), "$.content[*].id");
		assertThat(ids).doesNotContain(id.toString());
	}

	@Test
	void staleRefundHandled409() throws Exception {
		String token = adminToken();
		UUID id = doublePaid();
		long v = version(token, id);
		assertThat(fx.pay(id).getStatus()).isEqualTo(200); // flags again, bumping the version

		handle(token, id, v).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_UPDATE"));

		assertThat(jdbc.queryForObject("select refund_required from bookings where id = ?", Boolean.class, id)).isTrue();
	}

	@Test
	void alreadyHandled409() throws Exception {
		String token = adminToken();
		UUID id = doublePaid();
		handle(token, id, version(token, id)).andExpect(status().isOk());
		long next = jdbc.queryForObject("select version from bookings where id = ?", Long.class, id);

		handle(token, id, next).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("REFUND_ALREADY_HANDLED"));
	}

	@Test
	void unknownBookingAndBadFilter() throws Exception {
		String token = adminToken();
		handle(token, UUID.randomUUID(), 0).andExpect(status().isNotFound());
		mvc.perform(get("/api/admin/bookings?refundRequired=false").header("Authorization", token))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/admin/bookings").header("Authorization", token)).andExpect(status().isBadRequest());
	}

	@Test
	void unmatchedPaymentsListedAndCleared() throws Exception {
		String token = adminToken();
		String eventId = "evt_" + UUID.randomUUID();
		assertThat(fx.webhook(eventId, "payment.paid", UUID.randomUUID(), 5000L, "PHP").getStatus()).isEqualTo(200);

		String page = mvc.perform(get("/api/admin/payments/unmatched?size=100").header("Authorization", token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		List<String> found = JsonPath.read(page, "$.content[?(@.providerEventId=='%s')].id".formatted(eventId));
		assertThat(found).hasSize(1);
		String id = found.getFirst();

		mvc.perform(post("/api/admin/payments/{id}/refund-handled", id).header("Authorization", token))
				.andExpect(status().isNoContent());
		mvc.perform(post("/api/admin/payments/{id}/refund-handled", id).header("Authorization", token))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFUND_ALREADY_HANDLED"));
		mvc.perform(post("/api/admin/payments/{id}/refund-handled", UUID.randomUUID()).header("Authorization", token))
				.andExpect(status().isNotFound());

		page = mvc.perform(get("/api/admin/payments/unmatched?size=100").header("Authorization", token))
				.andReturn().getResponse().getContentAsString();
		assertThat((List<?>) JsonPath.read(page, "$.content[?(@.providerEventId=='%s')]".formatted(eventId))).isEmpty();
	}

	@Test
	void nonAdmin403() throws Exception {
		UUID id = UUID.randomUUID();
		for (User u : List.of(fx.customer(), fx.organizer())) {
			String t = fx.bearer(u);
			mvc.perform(get("/api/admin/bookings?refundRequired=true").header("Authorization", t))
					.andExpect(status().isForbidden());
			handle(t, id, 0).andExpect(status().isForbidden());
			mvc.perform(get("/api/admin/payments/unmatched").header("Authorization", t))
					.andExpect(status().isForbidden());
			mvc.perform(post("/api/admin/payments/{id}/refund-handled", id).header("Authorization", t))
					.andExpect(status().isForbidden());
		}
	}
}
