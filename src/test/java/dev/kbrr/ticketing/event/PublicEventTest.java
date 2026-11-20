package dev.kbrr.ticketing.event;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class PublicEventTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;

	@Test
	void listFiltersByCountryCityCategoryAndTime() throws Exception {
		String city = "City-" + UUID.randomUUID();
		User org = fx.organizer();
		EventResponse ph = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila", city), 10, 50000);
		VenueResponse jpVenue = fx.venue(org, "JP", "Asia/Tokyo", city);
		mvc.perform(post("/api/organizer/events").header("Authorization", fx.bearer(org)).contentType(APPLICATION_JSON)
				.content("""
						{"venueId":"%s","title":"Conf","category":"CONFERENCE","startsAt":"2099-12-31T20:00",
						 "endsAt":"2099-12-31T23:00","saleStartsAt":"2099-01-01T10:00",
						 "ticketTypes":[{"name":"Regular","priceMinor":1000,"capacity":5}]}""".formatted(jpVenue.id())))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/events").param("city", city)).andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(2));
		mvc.perform(get("/api/events").param("city", city.toLowerCase()).param("country", "ph"))
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].countryCode").value("PH"));
		mvc.perform(get("/api/events").param("city", city).param("category", "CONFERENCE"))
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].countryCode").value("JP"));
		// from is inclusive, to is exclusive
		String startsAt = ph.startsAt().toString();
		mvc.perform(get("/api/events").param("city", city).param("country", "PH").param("from", startsAt))
				.andExpect(jsonPath("$.page.totalElements").value(1));
		mvc.perform(get("/api/events").param("city", city).param("country", "PH").param("to", startsAt))
				.andExpect(jsonPath("$.page.totalElements").value(0));
	}

	@Test
	void filterTimeWithoutOffsetIsRejected() throws Exception {
		mvc.perform(get("/api/events").param("from", "2026-12-01T00:00")).andExpect(status().isBadRequest());
	}

	@Test
	void pageSizeOverLimitIsRejected() throws Exception {
		mvc.perform(get("/api/events").param("size", "101")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void getEventIsPublicAndCarriesZone() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 5, 50000);
		mvc.perform(get("/api/events/" + e.id())).andExpect(status().isOk())
				.andExpect(jsonPath("$.timeZone").value("Asia/Manila"))
				.andExpect(jsonPath("$.startsAt").value(endsWith("+08:00")));
	}

	@Test
	void unknownEventIs404() throws Exception {
		mvc.perform(get("/api/events/" + UUID.randomUUID())).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/api/events/" + UUID.randomUUID() + "/availability")).andExpect(status().isNotFound());
		mvc.perform(get("/api/events/not-a-uuid")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
	}

	@Test
	void availabilityShowsRemaining() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 5, 50000);
		mvc.perform(get("/api/events/" + e.id() + "/availability")).andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(e.id().toString()))
				.andExpect(jsonPath("$.ticketTypes[0].name").value("Regular"))
				.andExpect(jsonPath("$.ticketTypes[0].remaining").value(5))
				.andExpect(jsonPath("$.ticketTypes[0].price.amountMinor").value(50000))
				.andExpect(jsonPath("$.ticketTypes[0].price.currency").value("PHP"));
	}
}
