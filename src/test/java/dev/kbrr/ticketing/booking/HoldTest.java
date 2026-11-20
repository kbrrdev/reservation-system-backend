package dev.kbrr.ticketing.booking;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.MutableClock;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.CreateEventRequest;
import dev.kbrr.ticketing.event.EventCategory;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventService;
import dev.kbrr.ticketing.event.TicketTypeRequest;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class HoldTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired EventService events;

	private EventResponse phEvent(int capacity, int maxTickets) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, maxTickets, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	private static UUID typeOf(EventResponse e) {
		return e.ticketTypes().getFirst().id();
	}

	private ResultActions hold(User u, UUID eventId, String items) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content("{\"eventId\":\"%s\",\"items\":%s}".formatted(eventId, items)));
	}

	private ResultActions hold(User u, EventResponse e, int quantity) throws Exception {
		return hold(u, e.id(), "[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]".formatted(typeOf(e), quantity));
	}

	private String holdId(User u, EventResponse e, int quantity) throws Exception {
		return JsonPath.read(hold(u, e, quantity).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString(), "$.id");
	}

	private void remaining(EventResponse e, int expected) throws Exception {
		mvc.perform(get("/api/events/" + e.id() + "/availability"))
				.andExpect(jsonPath("$.ticketTypes[0].remaining").value(expected));
	}

	@Test
	void holdsGaTickets() throws Exception {
		EventResponse e = phEvent(10, 8);
		hold(fx.customer(), e, 2).andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
				.andExpect(jsonPath("$.type").value("PURCHASE"))
				.andExpect(jsonPath("$.eventId").value(e.id().toString()))
				.andExpect(jsonPath("$.total.amountMinor").value(300000))
				.andExpect(jsonPath("$.total.currency").value("PHP"))
				.andExpect(jsonPath("$.items[0].ticketTypeId").value(typeOf(e).toString()))
				.andExpect(jsonPath("$.items[0].quantity").value(2))
				.andExpect(jsonPath("$.items[0].unitPrice.amountMinor").value(150000))
				.andExpect(jsonPath("$.expiresAt").value(clock.instant().plus(Duration.ofMinutes(10)).toString()))
				.andExpect(jsonPath("$.checkoutUrl").doesNotExist());
		remaining(e, 8);
	}

	@Test
	void jpyTotalInMinorUnits() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "JP", "Asia/Tokyo"), 10, 1500);
		hold(fx.customer(), e, 3).andExpect(status().isCreated())
				.andExpect(jsonPath("$.total.amountMinor").value(4500))
				.andExpect(jsonPath("$.total.currency").value("JPY"));
	}

	@Test
	void ticketTypeOfAnotherEventIs404() throws Exception {
		EventResponse a = phEvent(10, 8);
		EventResponse b = phEvent(10, 8);
		hold(fx.customer(), a.id(), "[{\"ticketTypeId\":\"%s\",\"quantity\":1}]".formatted(typeOf(b)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		remaining(b, 10);
	}

	@Test
	void invalidQuantitiesAndDuplicates() throws Exception {
		EventResponse e = phEvent(10, 8);
		User c = fx.customer();
		hold(c, e, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		hold(c, e, -1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		String item = "{\"ticketTypeId\":\"%s\",\"quantity\":1}".formatted(typeOf(e));
		hold(c, e.id(), "[" + item + "," + item + "]").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("items"));
		hold(c, e.id(), "[]").andExpect(status().isBadRequest());
		hold(c, e.id(), "[null]").andExpect(status().isBadRequest());
		remaining(e, 10);
	}

	@Test
	void overflowingQuantity() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		EventResponse e = events.create(org.getId(), new CreateEventRequest(v.id(), "Two types", null,
				EventCategory.CONCERT, now.plusDays(30), now.plusDays(30).plusHours(3), now.minusHours(1), 8,
				List.of(new TicketTypeRequest("A", 150000L, 10, null),
				new TicketTypeRequest("B", 150000L, 10, null)), null));
		User c = fx.customer();
		hold(c, e, Integer.MAX_VALUE).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
		hold(c, e.id(), "[{\"ticketTypeId\":\"%s\",\"quantity\":%d},{\"ticketTypeId\":\"%s\",\"quantity\":%d}]"
				.formatted(e.ticketTypes().get(0).id(), Integer.MAX_VALUE, e.ticketTypes().get(1).id(),
						Integer.MAX_VALUE))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
	}

	@Test
	void notEnoughTickets() throws Exception {
		EventResponse e = phEvent(2, 8);
		hold(fx.customer(), e, 3).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKETS_UNAVAILABLE"))
				.andExpect(jsonPath("$.unavailable[0]").value(typeOf(e).toString()));
		remaining(e, 2);
	}

	@Test
	void saleWindowBoundaries() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		EventResponse notYet = fx.gaEvent(org, v, 10, 150000, 8, now.plusMinutes(1), now.plusDays(1),
				now.plusDays(1).plusHours(3));
		EventResponse ending = fx.gaEvent(org, v, 10, 150000, 8, now.minusHours(1), now.plusMinutes(1),
				now.plusMinutes(2));
		User c = fx.customer();

		hold(c, notYet, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));
		clock.advance(Duration.ofMinutes(1)); // now == saleStartsAt: sale is open
		hold(c, notYet, 1).andExpect(status().isCreated());
		clock.advance(Duration.ofMinutes(1)); // now == endsAt: sale is over
		hold(c, ending, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));
	}

	@Test
	void perCustomerLimit() throws Exception {
		EventResponse e = phEvent(10, 3);
		hold(fx.customer(), e, 4).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
		remaining(e, 10);
	}

	@Test
	void secondPendingHold() throws Exception {
		EventResponse e = phEvent(10, 8);
		User c = fx.customer();
		hold(c, e, 1).andExpect(status().isCreated());
		hold(c, e, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PENDING_HOLD_EXISTS"));
		remaining(e, 9);
	}

	@Test
	void rebookRightAfterExpiry() throws Exception {
		EventResponse e = phEvent(10, 8);
		User c = fx.customer();
		hold(c, e, 1).andExpect(status().isCreated());
		clock.advance(Duration.ofMinutes(10).plusSeconds(1)); // expired, but the expiry job has not run
		hold(c, e, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREVIOUS_HOLD_RELEASING"))
				.andExpect(jsonPath("$.detail").value("Your previous hold is being released, retry in a few seconds"));
	}

	@Test
	void limitCheckedAfterExpiredHold() throws Exception {
		EventResponse e = phEvent(10, 3);
		User c = fx.customer();
		hold(c, e, 3).andExpect(status().isCreated());
		clock.advance(Duration.ofMinutes(10).plusSeconds(1)); // expired, the job has not run: the hold still counts
		hold(c, e, 3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREVIOUS_HOLD_RELEASING"));
	}

	@Test
	void failedMultiTypeHoldRollsBackEarlierTypes() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		EventResponse e;
		UUID plenty;
		UUID scarce;
		// Locks are taken in UUID order; retry until the available type sorts first so a hold is taken before the failure.
		do {
			e = events.create(org.getId(), new CreateEventRequest(v.id(), "Rollback", null, EventCategory.CONCERT,
					now.plusDays(30), now.plusDays(30).plusHours(3), now.minusHours(1), 8,
					List.of(new TicketTypeRequest("Plenty", 150000L, 10, null),
					new TicketTypeRequest("Scarce", 150000L, 1, null)), null));
			plenty = e.ticketTypes().stream().filter(t -> t.name().equals("Plenty")).findFirst().orElseThrow().id();
			scarce = e.ticketTypes().stream().filter(t -> t.name().equals("Scarce")).findFirst().orElseThrow().id();
		} while (plenty.compareTo(scarce) > 0);

		hold(fx.customer(), e.id(), "[{\"ticketTypeId\":\"%s\",\"quantity\":2},{\"ticketTypeId\":\"%s\",\"quantity\":2}]"
				.formatted(scarce, plenty)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKETS_UNAVAILABLE"))
				.andExpect(jsonPath("$.unavailable.length()").value(1))
				.andExpect(jsonPath("$.unavailable[0]").value(scarce.toString()));
		mvc.perform(get("/api/events/" + e.id() + "/availability"))
				.andExpect(jsonPath("$.ticketTypes[?(@.ticketTypeId=='%s')].remaining".formatted(plenty)).value(10))
				.andExpect(jsonPath("$.ticketTypes[?(@.ticketTypeId=='%s')].remaining".formatted(scarce)).value(1));
	}

	@Test
	void loweringLimitBlocksFurtherBuysOnly() throws Exception {
		User org = fx.organizer();
		EventResponse e = fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 20, 150000);
		User a = fx.customer();
		String id = holdId(a, e, 4);

		mvc.perform(put("/api/organizer/events/" + e.id()).header("Authorization", fx.bearer(org))
				.contentType(APPLICATION_JSON)
				.content("{\"title\":\"Show\",\"category\":\"CONCERT\",\"maxTicketsPerCustomer\":2}"))
				.andExpect(status().isOk());

		mvc.perform(get("/api/bookings/" + id).header("Authorization", fx.bearer(a))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
		User b = fx.customer();
		hold(b, e, 3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
		hold(b, e, 2).andExpect(status().isCreated());
	}

	@Test
	void othersBookingIs404() throws Exception {
		String id = holdId(fx.customer(), phEvent(10, 8), 1);
		mvc.perform(get("/api/bookings/" + id).header("Authorization", fx.bearer(fx.customer())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void listShowsOwnBookingsOnly() throws Exception {
		EventResponse e = phEvent(10, 8);
		User a = fx.customer();
		String id = holdId(a, e, 1);
		holdId(fx.customer(), e, 1);
		mvc.perform(get("/api/bookings").header("Authorization", fx.bearer(a))).andExpect(status().isOk())
				.andExpect(jsonPath("$.page.totalElements").value(1))
				.andExpect(jsonPath("$.content[0].id").value(id));
		mvc.perform(get("/api/bookings").param("size", "101").header("Authorization", fx.bearer(a)))
				.andExpect(status().isBadRequest());
	}
}
