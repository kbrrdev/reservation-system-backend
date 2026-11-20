package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class WebhookTest {

	private static final long GA_PRICE = 150000;

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketRepository tickets;
	@Autowired TicketTypeRepository ticketTypes;
	@Autowired EventSeatRepository eventSeats;
	@Autowired JdbcTemplate jdbc;

	private EventResponse gaEvent() {
		User org = fx.organizer();
		return fx.gaEvent(org, fx.venue(org, "PH", "Asia/Manila"), 10, GA_PRICE);
	}

	private EventResponse reservedEvent(int seats) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID orchestra = fx.section(org, v, "Orchestra", List.of("A"), seats).id();
		return fx.reservedEvent(org, v, Map.of("Premium", List.of(orchestra)), 250000);
	}

	private UUID holdGa(User u, EventResponse e, int quantity) throws Exception {
		return created(mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u))
				.contentType(APPLICATION_JSON).content("{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}"
						.formatted(e.id(), e.ticketTypes().getFirst().id(), quantity)))
				.andExpect(status().isCreated()).andReturn().getResponse());
	}

	private UUID holdSeats(User u, EventResponse e, UUID... seats) throws Exception {
		String ids = List.of(seats).stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
		return created(mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u))
				.contentType(APPLICATION_JSON).content("{\"eventId\":\"%s\",\"eventSeatIds\":[%s]}".formatted(e.id(), ids)))
				.andExpect(status().isCreated()).andReturn().getResponse());
	}

	private static UUID created(MockHttpServletResponse r) throws Exception {
		return UUID.fromString(JsonPath.read(r.getContentAsString(), "$.id"));
	}

	private List<UUID> seats(EventResponse e) {
		return eventSeats.findSeatMap(e.id()).stream().map(SeatMapRow::eventSeatId).toList();
	}

	private Booking booking(UUID id) {
		return bookingRepository.findById(id).orElseThrow();
	}

	private TicketType type(EventResponse e) {
		return ticketTypes.findById(e.ticketTypes().getFirst().id()).orElseThrow();
	}

	private long eventRows(String eventId) {
		return jdbc.queryForObject("select count(*) from payment_events where provider = 'fake' and provider_event_id = ?",
				Long.class, eventId);
	}

	private static String newEventId() {
		return "evt_" + UUID.randomUUID();
	}

	@Test
	void paidWebhookConfirms() throws Exception {
		EventResponse e = gaEvent();
		UUID id = holdGa(fx.customer(), e, 2);

		MockHttpServletResponse r = fx.pay(id);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentAsString()).isEmpty();
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isEqualTo(2);
		List<Map<String, Object>> rows = jdbc.queryForList("select paid from payment_events where booking_id = ?", id);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().get("paid")).isEqualTo(true);
	}

	@Test
	void reservedPaidWebhookConfirms() throws Exception {
		EventResponse e = reservedEvent(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		User c = fx.customer();
		UUID id = holdSeats(c, e, s1, s2);

		assertThat(fx.pay(id).getStatus()).isEqualTo(200);

		assertThat(eventSeats.findById(s1).orElseThrow().getStatus()).isEqualTo(EventSeatStatus.SOLD);
		assertThat(eventSeats.findById(s2).orElseThrow().getStatus()).isEqualTo(EventSeatStatus.SOLD);
		mvc.perform(get("/api/bookings/" + id + "/tickets").header("Authorization", fx.bearer(c)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[*].section", containsInAnyOrder("Orchestra", "Orchestra")))
				.andExpect(jsonPath("$[*].row", containsInAnyOrder("A", "A")))
				.andExpect(jsonPath("$[*].number", containsInAnyOrder(1, 2)));
	}

	@Test
	void duplicateDeliveryIgnored() throws Exception {
		UUID id = holdGa(fx.customer(), gaEvent(), 2);
		String evt = newEventId();

		assertThat(fx.webhook(evt, "payment.paid", id, 2 * GA_PRICE, "PHP").getStatus()).isEqualTo(200);
		assertThat(fx.webhook(evt, "payment.paid", id, 2 * GA_PRICE, "PHP").getStatus()).isEqualTo(200);

		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(eventRows(evt)).isEqualTo(1);
		assertThat(booking(id).isRefundRequired()).isFalse();
	}

	@Test
	void badSignature401() throws Exception {
		UUID id = holdGa(fx.customer(), gaEvent(), 2);
		String evt = newEventId();
		String body = "{\"id\":\"%s\",\"type\":\"payment.paid\",\"bookingId\":\"%s\",\"amountMinor\":%d,\"currency\":\"PHP\"}"
				.formatted(evt, id, 2 * GA_PRICE);

		mvc.perform(post("/api/payments/webhook/fake").header("Fake-Signature", "wrong").contentType(APPLICATION_JSON)
				.content(body)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
		mvc.perform(post("/api/payments/webhook/fake").contentType(APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

		assertThat(eventRows(evt)).isZero();
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
	}

	@Test
	void unknownProvider404() throws Exception {
		mvc.perform(post("/api/payments/webhook/stripe").contentType(APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void otherEventTypeStoredAnd200() throws Exception {
		UUID id = holdGa(fx.customer(), gaEvent(), 2);
		String evt = newEventId();

		assertThat(fx.webhook(evt, "payment.failed", id, 2 * GA_PRICE, "PHP").getStatus()).isEqualTo(200);

		Map<String, Object> row = jdbc.queryForMap(
				"select paid, booking_id from payment_events where provider = 'fake' and provider_event_id = ?", evt);
		assertThat(row.get("paid")).isEqualTo(false);
		assertThat(row.get("booking_id")).isEqualTo(id);
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
	}

	@Test
	void unmatchedBooking200() throws Exception {
		String evt = newEventId();

		assertThat(fx.webhook(evt, "payment.paid", UUID.randomUUID(), 1000L, "PHP").getStatus()).isEqualTo(200);

		Map<String, Object> row = jdbc.queryForMap(
				"select paid, booking_id from payment_events where provider = 'fake' and provider_event_id = ?", evt);
		assertThat(row.get("paid")).isEqualTo(true);
		assertThat(row.get("booking_id")).isNull();
	}

	@Test
	void mismatchFlagsRefund() throws Exception {
		EventResponse e = gaEvent();
		long total = 2 * GA_PRICE;

		UUID wrongAmount = holdGa(fx.customer(), e, 2);
		assertThat(fx.webhook(newEventId(), "payment.paid", wrongAmount, total - 1, "PHP").getStatus()).isEqualTo(200);

		UUID wrongCurrency = holdGa(fx.customer(), e, 2);
		assertThat(fx.webhook(newEventId(), "payment.paid", wrongCurrency, total, "JPY").getStatus()).isEqualTo(200);

		UUID wrongProvider = holdGa(fx.customer(), e, 2);
		jdbc.update("update bookings set payment_provider = 'paymongo' where id = ?", wrongProvider);
		assertThat(fx.pay(wrongProvider).getStatus()).isEqualTo(200);

		for (UUID id : List.of(wrongAmount, wrongCurrency, wrongProvider)) {
			Booking b = booking(id);
			assertThat(b.getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
			assertThat(b.isRefundRequired()).isTrue();
			assertThat(tickets.findByBookingIdOrderById(id)).isEmpty();
		}
		assertThat(type(e).getSold()).isZero();
	}

	@Test
	void failureMidConfirmLeavesNoPaymentEventSoRetryWorks() throws Exception {
		EventResponse e = reservedEvent(2);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		UUID id = holdSeats(fx.customer(), e, s1, s2);
		String evt = newEventId();
		// The booking still lists s2, but the seat no longer points at it: confirm's row count check fails.
		jdbc.update("update event_seats set status = 'AVAILABLE', booking_id = null where id = ?", s2);

		assertThat(fx.webhook(evt, "payment.paid", id, 500000L, "PHP").getStatus()).isEqualTo(500);
		assertThat(eventRows(evt)).isZero();
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);

		jdbc.update("update event_seats set status = 'HELD', booking_id = ? where id = ?", id, s2);
		assertThat(fx.webhook(evt, "payment.paid", id, 500000L, "PHP").getStatus()).isEqualTo(200);
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(eventRows(evt)).isEqualTo(1);
	}

	@Test
	void otherConstraintFailureIsNotSwallowed() throws Exception {
		EventResponse e = reservedEvent(2);
		UUID s1 = seats(e).get(0);
		UUID id = holdSeats(fx.customer(), e, s1);
		String evt = newEventId();
		jdbc.update("""
				insert into tickets (id, booking_id, ticket_type_id, code, status, issued_at, event_seat_id)
				values (gen_random_uuid(), ?, ?, 'preexisting', 'VALID', now(), ?)""",
				id, e.ticketTypes().getFirst().id(), s1);

		assertThat(fx.webhook(evt, "payment.paid", id, 250000L, "PHP").getStatus()).isEqualTo(500);

		assertThat(eventRows(evt)).isZero();
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
	}

	@Test
	void crashBeforeReferenceSavedStillConfirms() throws Exception {
		UUID id = holdGa(fx.customer(), gaEvent(), 2);
		jdbc.update("update bookings set payment_reference = null where id = ?", id);

		assertThat(fx.pay(id).getStatus()).isEqualTo(200);

		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
	}

	@Test
	void noFakePayEndpoint() throws Exception {
		User c = fx.customer();
		UUID id = holdGa(c, gaEvent(), 2);

		mvc.perform(post("/api/bookings/" + id + "/fake-pay").header("Authorization", fx.bearer(c)))
				.andExpect(status().isNotFound());
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
	}
}
