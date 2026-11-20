package dev.kbrr.ticketing.event;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class OrganizerEventTest {

	private static final String ONE = "[{\"name\":\"Regular\",\"priceMinor\":150000,\"capacity\":100}]";

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;

	private ResultActions submit(User org, UUID venueId, String starts, String ends, String saleStarts, String limit,
			String tickets) throws Exception {
		return mvc.perform(post("/api/organizer/events").header("Authorization", fx.bearer(org))
				.contentType(APPLICATION_JSON).content("""
						{"venueId":"%s","title":"Show","category":"CONCERT","startsAt":"%s","endsAt":"%s",
						 "saleStartsAt":"%s"%s,"ticketTypes":%s}""".formatted(venueId, starts, ends, saleStarts,
						limit == null ? "" : ",\"maxTicketsPerCustomer\":" + limit, tickets)));
	}

	private ResultActions schedule(User org, VenueResponse v, String starts, String ends, String saleStarts)
			throws Exception {
		return submit(org, v.id(), starts, ends, saleStarts, null, ONE);
	}

	private ResultActions future(User org, VenueResponse v, String limit, String tickets) throws Exception {
		return submit(org, v.id(), "2099-12-31T20:00", "2099-12-31T23:00", "2099-01-01T10:00", limit, tickets);
	}

	@Test
	void createsOnSaleGaEventInVenueCurrencyAndZone() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		schedule(org, v, "2099-12-31T20:00", "2099-12-31T23:00", "2099-11-01T10:00").andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("ON_SALE")).andExpect(jsonPath("$.currency").value("PHP"))
				.andExpect(jsonPath("$.startsAt").value("2099-12-31T20:00:00+08:00"))
				.andExpect(jsonPath("$.timeZone").value("Asia/Manila"))
				.andExpect(jsonPath("$.ticketTypes[0].price.amountMinor").value(150000))
				.andExpect(jsonPath("$.ticketTypes[0].price.currency").value("PHP"))
				.andExpect(jsonPath("$.maxTicketsPerCustomer").value(8));
	}

	@Test
	void jpyVenueGivesJpyEvent() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "JP", "Asia/Tokyo");
		future(org, v, null, "[{\"name\":\"Regular\",\"priceMinor\":1500,\"capacity\":10}]")
				.andExpect(status().isCreated()).andExpect(jsonPath("$.currency").value("JPY"))
				.andExpect(jsonPath("$.ticketTypes[0].price.amountMinor").value(1500));
	}

	@ParameterizedTest
	@ValueSource(strings = {"2099-12-31T20:00+08:00", "2099-12-31T20:00Z"})
	void scheduleWithOffsetIsRejected(String starts) throws Exception {
		User org = fx.organizer();
		schedule(org, fx.venue(org, "PH", "Asia/Manila"), starts, "2100-01-01T23:00", "2099-11-01T10:00")
				.andExpect(status().isBadRequest());
	}

	@Test
	void dstGapScheduleIsRejected() throws Exception {
		User org = fx.organizer();
		// PH venue with a US zone: the US itself is disabled in the test config.
		schedule(org, fx.venue(org, "PH", "America/New_York"), "2027-03-14T02:30", "2027-03-14T06:00",
				"2027-01-01T10:00").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("startsAt"));
	}

	@Test
	void scheduleOrderAndPast() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		schedule(org, v, "2099-12-31T20:00", "2099-12-31T23:00", "2099-12-31T20:00").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("saleStartsAt"));
		schedule(org, v, "2099-12-31T20:00", "2099-12-31T20:00", "2099-01-01T10:00").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
		schedule(org, v, "2020-12-31T20:00", "2020-12-31T23:00", "2020-01-01T10:00").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("endsAt"));
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 51})
	void ticketLimitOutOfRange(int limit) throws Exception {
		User org = fx.organizer();
		future(org, fx.venue(org, "PH", "Asia/Manila"), String.valueOf(limit), ONE).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("maxTicketsPerCustomer"));
	}

	@Test
	void priceBelowCountryMinimum() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		future(org, v, null, "[{\"name\":\"Regular\",\"priceMinor\":1999,\"capacity\":10}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].priceMinor"));
		future(org, v, null, "[{\"name\":\"Regular\",\"priceMinor\":0,\"capacity\":10}]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].priceMinor"));
	}

	@Test
	void duplicateTicketTypeNames() throws Exception {
		User org = fx.organizer();
		future(org, fx.venue(org, "PH", "Asia/Manila"), null,
				"[{\"name\":\"Regular\",\"priceMinor\":5000,\"capacity\":10},{\"name\":\"Regular\",\"priceMinor\":6000,\"capacity\":10}]")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ticketTypes"));
	}

	@Test
	void otherOrganizersVenueIsNotFound() throws Exception {
		VenueResponse v = fx.venue(fx.organizer(), "PH", "Asia/Manila");
		future(fx.organizer(), v, null, ONE).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void updateChangesLimitAndText() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 10, 5000);
		mvc.perform(put("/api/organizer/events/" + e.id()).header("Authorization", fx.bearer(org))
				.contentType(APPLICATION_JSON)
				.content("{\"title\":\"New\",\"category\":\"THEATER\",\"description\":\"d\",\"maxTicketsPerCustomer\":2}"))
				.andExpect(status().isOk());
		mvc.perform(get("/api/organizer/events/" + e.id()).header("Authorization", fx.bearer(org)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.title").value("New"))
				.andExpect(jsonPath("$.category").value("THEATER")).andExpect(jsonPath("$.description").value("d"))
				.andExpect(jsonPath("$.maxTicketsPerCustomer").value(2));
	}
}
