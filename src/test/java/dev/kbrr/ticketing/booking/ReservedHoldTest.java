package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ReservedHoldTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired EventSeatRepository eventSeats;

	private User org;

	/** Orchestra (rows A, B x 3) is Premium at 250000; Balcony (row C x 2) is Standard at 120000. */
	private EventResponse reservedEvent() {
		org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID orchestra = fx.section(org, v, "Orchestra", List.of("A", "B"), 3).id();
		UUID balcony = fx.section(org, v, "Balcony", List.of("C"), 2).id();
		return fx.reservedEvent(org, v, Map.of("Premium", List.of(orchestra), "Standard", List.of(balcony)),
				Map.of("Premium", 250000L, "Standard", 120000L));
	}

	private List<UUID> seats(EventResponse e, String section) {
		return eventSeats.findSeatMap(e.id()).stream().filter(r -> r.sectionName().equals(section))
				.map(SeatMapRow::eventSeatId).toList();
	}

	private static UUID typeId(EventResponse e, String name) {
		return e.ticketTypes().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow().id();
	}

	private EventSeatStatus statusOf(UUID seat) {
		return eventSeats.findById(seat).orElseThrow().getStatus();
	}

	private ResultActions send(User u, String body) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content(body));
	}

	private ResultActions holdSeats(User u, UUID eventId, UUID... seats) throws Exception {
		String ids = List.of(seats).stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
		return send(u, "{\"eventId\":\"%s\",\"eventSeatIds\":[%s]}".formatted(eventId, ids));
	}

	@Test
	void holdsSeats() throws Exception {
		EventResponse e = reservedEvent();
		List<UUID> premium = seats(e, "Orchestra");
		UUID s1 = premium.get(0);
		UUID s2 = premium.get(1);

		holdSeats(fx.customer(), e.id(), s1, s2).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
				.andExpect(jsonPath("$.total.amountMinor").value(500000))
				.andExpect(jsonPath("$.total.currency").value("PHP"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].ticketTypeId").value(typeId(e, "Premium").toString()))
				.andExpect(jsonPath("$.items[0].quantity").value(2))
				.andExpect(jsonPath("$.eventSeatIds", containsInAnyOrder(s1.toString(), s2.toString())));

		mvc.perform(get("/api/events/" + e.id() + "/availability"))
				.andExpect(jsonPath("$.sections[1].seats[0].status").value("HELD"))
				.andExpect(jsonPath("$.sections[1].seats[1].status").value("HELD"))
				.andExpect(jsonPath("$.sections[1].seats[2].status").value("AVAILABLE"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Premium')].remaining").value(contains(4)));
	}

	@Test
	void mixedTypesTotal() throws Exception {
		EventResponse e = reservedEvent();
		holdSeats(fx.customer(), e.id(), seats(e, "Orchestra").get(0), seats(e, "Balcony").get(0))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.total.amountMinor").value(370000))
				.andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test
	void requestShape() throws Exception {
		EventResponse e = reservedEvent();
		UUID s1 = seats(e, "Orchestra").get(0);
		User c = fx.customer();
		send(c, "{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}],\"eventSeatIds\":[\"%s\"]}"
				.formatted(e.id(), typeId(e, "Premium"), s1)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("items"));
		send(c, "{\"eventId\":\"%s\"}".formatted(e.id())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("items"));
		holdSeats(c, e.id(), s1, s1).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("eventSeatIds"));
		assertThat(statusOf(s1)).isEqualTo(EventSeatStatus.AVAILABLE);
	}

	@Test
	void modeMismatchAndForeignSeat() throws Exception {
		EventResponse reserved = reservedEvent();
		EventResponse other = reservedEvent();
		EventResponse ga = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 10, 50000);
		UUID seat = seats(reserved, "Orchestra").get(0);
		User c = fx.customer();

		holdSeats(c, ga.id(), seat).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Seat not found"));
		send(c, "{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":1}]}"
				.formatted(reserved.id(), typeId(reserved, "Premium"))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Ticket type not found"));
		holdSeats(c, reserved.id(), seats(other, "Orchestra").get(0)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("Seat not found"));
		holdSeats(c, reserved.id(), UUID.randomUUID()).andExpect(status().isNotFound());

		mvc.perform(get("/api/events/" + reserved.id() + "/availability"))
				.andExpect(jsonPath("$.ticketTypes[?(@.name=='Premium')].remaining").value(contains(6)));
		assertThat(eventSeats.countByEventIdAndStatus(other.id(), EventSeatStatus.AVAILABLE)).isEqualTo(8);
	}

	@Test
	void takenSeat() throws Exception {
		EventResponse e = reservedEvent();
		UUID s1 = seats(e, "Orchestra").get(0);
		UUID s2 = seats(e, "Orchestra").get(1);
		holdSeats(fx.customer(), e.id(), s1).andExpect(status().isCreated());

		holdSeats(fx.customer(), e.id(), s1, s2).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKETS_UNAVAILABLE"))
				.andExpect(jsonPath("$.unavailable", contains(s1.toString())));
		assertThat(statusOf(s2)).isEqualTo(EventSeatStatus.AVAILABLE);
	}

	@Test
	void seatLimit() throws Exception {
		EventResponse e = reservedEvent();
		mvc.perform(put("/api/organizer/events/" + e.id()).header("Authorization", fx.bearer(org))
				.contentType(APPLICATION_JSON)
				.content("{\"title\":\"Show\",\"category\":\"CONCERT\",\"maxTicketsPerCustomer\":2}"))
				.andExpect(status().isOk());
		List<UUID> s = seats(e, "Orchestra");

		holdSeats(fx.customer(), e.id(), s.get(0), s.get(1), s.get(2)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
		assertThat(eventSeats.countByEventIdAndStatus(e.id(), EventSeatStatus.AVAILABLE)).isEqualTo(8);
	}

	@Test
	void pendingRuleAppliesToSeats() throws Exception {
		EventResponse e = reservedEvent();
		User c = fx.customer();
		holdSeats(c, e.id(), seats(e, "Orchestra").get(0)).andExpect(status().isCreated());
		holdSeats(c, e.id(), seats(e, "Orchestra").get(1)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PENDING_HOLD_EXISTS"));
	}
}
