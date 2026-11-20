package dev.kbrr.ticketing.event;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SeatMapTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;

	@Test
	void reservedSeatMap() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID orchestra = fx.section(org, v, "Orchestra", List.of("A", "B"), 3).id();
		UUID balcony = fx.section(org, v, "Balcony", List.of("C"), 2).id();
		EventResponse e = fx.reservedEvent(org, v, Map.of("Premium", List.of(orchestra), "Standard", List.of(balcony)),
				Map.of("Premium", 250000L, "Standard", 120000L));
		mvc.perform(get("/api/events/" + e.id() + "/availability")).andExpect(status().isOk())
				.andExpect(jsonPath("$.seatingMode").value("RESERVED"))
				.andExpect(jsonPath("$.sections[0].name").value("Balcony"))
				.andExpect(jsonPath("$.sections[0].price.amountMinor").value(120000))
				.andExpect(jsonPath("$.sections[0].seats.length()").value(2))
				.andExpect(jsonPath("$.sections[0].seats[0].row").value("C"))
				.andExpect(jsonPath("$.sections[0].seats[0].number").value(1))
				.andExpect(jsonPath("$.sections[0].seats[0].status").value("AVAILABLE"))
				.andExpect(jsonPath("$.sections[1].price.amountMinor").value(250000))
				.andExpect(jsonPath("$.sections[1].price.currency").value("PHP"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Premium')].remaining")
						.value(contains(6)));
	}

	@Test
	void seatOrderFollowsRowIndexNotLabel() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID s = fx.section(org, v, "Main", List.of("B", "A"), 2).id();
		EventResponse e = fx.reservedEvent(org, v, Map.of("Std", List.of(s)), 100000);
		mvc.perform(get("/api/events/" + e.id() + "/availability")).andExpect(status().isOk())
				.andExpect(jsonPath("$.sections[0].seats[0].row").value("B"))
				.andExpect(jsonPath("$.sections[0].seats[2].row").value("A"));
	}

	@Test
	void gaAvailabilityUnchanged() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 10, 50000);
		mvc.perform(get("/api/events/" + e.id() + "/availability")).andExpect(status().isOk())
				.andExpect(jsonPath("$.seatingMode").value("GA"))
				.andExpect(jsonPath("$.sections.length()").value(0))
				.andExpect(jsonPath("$.ticketTypes[0].remaining").value(10));
	}
}
