package dev.kbrr.ticketing.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ReservedEventTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired EventSeatRepository eventSeats;

	private ResultActions create(User org, UUID venueId, String mode, String tickets) throws Exception {
		return mvc.perform(post("/api/organizer/events").header("Authorization", fx.bearer(org))
				.contentType(APPLICATION_JSON).content("""
						{"venueId":"%s","title":"Show","category":"CONCERT","startsAt":"2099-01-01T20:00",
						 "endsAt":"2099-01-01T23:00","saleStartsAt":"2098-01-01T10:00"%s,"ticketTypes":%s}"""
						.formatted(venueId, mode == null ? "" : ",\"seatingMode\":\"" + mode + "\"", tickets)));
	}

	private static String type(String name, String capacityJson, UUID... sectionIds) {
		StringBuilder ids = new StringBuilder();
		for (UUID id : sectionIds) {
			ids.append(ids.isEmpty() ? "" : ",").append('"').append(id).append('"');
		}
		return """
				{"name":"%s","priceMinor":250000%s%s}""".formatted(name,
				capacityJson == null ? "" : ",\"capacity\":" + capacityJson,
				sectionIds.length == 0 ? "" : ",\"sectionIds\":[" + ids + "]");
	}

	private record Setup(User org, VenueResponse venue, UUID orchestra, UUID balcony) {
	}

	private Setup setup() {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID o = fx.section(org, v, "Orchestra", java.util.List.of("A", "B"), 10).id();
		UUID b = fx.section(org, v, "Balcony", java.util.List.of("C"), 5).id();
		return new Setup(org, v, o, b);
	}

	@Test
	void createsReservedEventWithEventSeats() throws Exception {
		Setup s = setup();
		String body = create(s.org, s.venue.id(), "RESERVED", "[" + type("Premium", null, s.orchestra) + ","
				+ type("Standard", null, s.balcony) + "]").andExpect(status().isCreated())
				.andExpect(jsonPath("$.seatingMode").value("RESERVED"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Premium')].capacity").value(org.hamcrest.Matchers.contains(20)))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Premium')].sectionIds").value(org.hamcrest.Matchers.contains(java.util.List.of(s.orchestra.toString()))))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		assertThat(eventSeats.countByEventIdAndStatus(id, EventSeatStatus.AVAILABLE)).isEqualTo(25);
	}

	@Test
	void unmappedSection() throws Exception {
		Setup s = setup();
		create(s.org, s.venue.id(), "RESERVED", "[" + type("Premium", null, s.orchestra) + "]")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ticketTypes"));
	}

	@Test
	void sectionMappedTwice() throws Exception {
		Setup s = setup();
		create(s.org, s.venue.id(), "RESERVED", "[" + type("Premium", null, s.orchestra, s.balcony) + ","
				+ type("Standard", null, s.orchestra) + "]").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes"));
	}

	@Test
	void sectionOfAnotherVenue() throws Exception {
		Setup s = setup();
		VenueResponse other = fx.venue(s.org, "PH", "Asia/Manila");
		UUID foreign = fx.section(s.org, other, "Other", java.util.List.of("A"), 3).id();
		create(s.org, s.venue.id(), "RESERVED", "[" + type("Premium", null, s.orchestra, s.balcony, foreign) + "]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].sectionIds"));
	}

	@Test
	void venueWithoutSections() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		create(org, v.id(), "RESERVED", "[" + type("Premium", null, UUID.randomUUID()) + "]")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("venueId"));
	}

	@Test
	void capacityRulesPerMode() throws Exception {
		Setup s = setup();
		create(s.org, s.venue.id(), "RESERVED", "[" + type("Premium", "10", s.orchestra, s.balcony) + "]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].capacity"));
		create(s.org, s.venue.id(), null, "[" + type("Premium", null) + "]").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].capacity"));
		create(s.org, s.venue.id(), "GA", "[" + type("Premium", "10", s.orchestra) + "]")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("ticketTypes[0].sectionIds"));
	}

	@Test
	void gaStillDefault() throws Exception {
		Setup s = setup();
		create(s.org, s.venue.id(), null, "[" + type("Regular", "10") + "]").andExpect(status().isCreated())
				.andExpect(jsonPath("$.seatingMode").value("GA"))
				.andExpect(jsonPath("$.ticketTypes[0].sectionIds").isEmpty());
	}
}
