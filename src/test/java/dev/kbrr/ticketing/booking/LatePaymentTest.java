package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.MutableClock;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeat;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LatePaymentTest {

	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketRepository tickets;
	@Autowired TicketTypeRepository ticketTypes;
	@Autowired EventSeatRepository eventSeats;
	@Autowired ExpiryJob expiryJob;
	@Autowired JdbcTemplate jdbc;

	private EventResponse gaEvent(int capacity, int maxTickets) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, maxTickets, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
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
						.formatted(e.id(), e.ticketTypes().getFirst().id(), quantity))));
	}

	private UUID holdSeats(User u, EventResponse e, UUID... seats) throws Exception {
		String ids = List.of(seats).stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
		return created(mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u))
				.contentType(APPLICATION_JSON).content("{\"eventId\":\"%s\",\"eventSeatIds\":[%s]}".formatted(e.id(), ids))));
	}

	private static UUID created(ResultActions r) throws Exception {
		return UUID.fromString(JsonPath.read(r.andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString(), "$.id"));
	}

	private void expire() {
		clock.advance(PAST_EXPIRY);
		expiryJob.run();
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

	private EventSeat seat(UUID id) {
		return eventSeats.findById(id).orElseThrow();
	}

	private void assertPaid200(UUID id) throws Exception {
		assertThat(fx.pay(id).getStatus()).isEqualTo(200);
	}

	@Test
	void latePaymentReclaimsGa() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID id = holdGa(fx.customer(), e, 2);
		expire();
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.EXPIRED);

		assertPaid200(id);

		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking(id).isRefundRequired()).isFalse();
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isEqualTo(2);
	}

	@Test
	void latePaymentReclaimsSeats() throws Exception {
		EventResponse e = reservedEvent(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		UUID id = holdSeats(fx.customer(), e, s1, s2);
		expire();
		assertThat(seat(s1).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);

		assertPaid200(id);

		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking(id).isRefundRequired()).isFalse();
		for (UUID s : List.of(s1, s2)) {
			assertThat(seat(s).getStatus()).isEqualTo(EventSeatStatus.SOLD);
			assertThat(seat(s).getBookingId()).isEqualTo(id);
		}
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
	}

	@Test
	void latePaymentAfterCancelReclaims() throws Exception {
		EventResponse e = gaEvent(10, 8);
		User c = fx.customer();
		UUID id = holdGa(c, e, 2);
		mvc.perform(delete("/api/bookings/" + id).header("Authorization", fx.bearer(c))).andExpect(status().isOk());

		assertPaid200(id);

		Booking b = booking(id);
		assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(b.isRefundRequired()).isFalse();
		assertThat(b.getCancelledBy()).isEqualTo(c.getId()); // history stays
		assertThat(type(e).getSold()).isEqualTo(2);
		assertThat(type(e).getHeld()).isZero();
	}

	@Test
	void latePaymentSeatTakenFlagsRefund() throws Exception {
		EventResponse e = reservedEvent(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		UUID x = holdSeats(fx.customer(), e, s1, s2);
		expire();
		UUID b = holdSeats(fx.customer(), e, s2);

		assertPaid200(x);

		assertThat(booking(x).getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(booking(x).isRefundRequired()).isTrue();
		assertThat(seat(s1).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE); // nothing half-taken
		assertThat(seat(s1).getBookingId()).isNull();
		assertThat(seat(s2).getStatus()).isEqualTo(EventSeatStatus.HELD);
		assertThat(seat(s2).getBookingId()).isEqualTo(b);
		assertThat(tickets.findByBookingIdOrderById(x)).isEmpty();
	}

	@Test
	void latePaymentSoldOutGaFlagsRefund() throws Exception {
		EventResponse e = gaEvent(2, 8);
		UUID x = holdGa(fx.customer(), e, 2);
		expire();
		holdGa(fx.customer(), e, 2);

		assertPaid200(x);

		assertThat(booking(x).getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(booking(x).isRefundRequired()).isTrue();
		assertThat(type(e).getHeld()).isEqualTo(2);
		assertThat(type(e).getSold()).isZero();
		assertThat(tickets.findByBookingIdOrderById(x)).isEmpty();
	}

	@Test
	void latePaymentOverLimitFlagsRefund() throws Exception {
		EventResponse e = gaEvent(10, 2);
		User c = fx.customer();
		UUID x = holdGa(c, e, 2);
		expire();
		holdGa(c, e, 2);

		assertPaid200(x);

		assertThat(booking(x).getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(booking(x).isRefundRequired()).isTrue();
		assertThat(bookingRepository.countActiveTickets(c.getId(), e.id())).isEqualTo(2);
		assertThat(type(e).getHeld()).isEqualTo(2);
		assertThat(type(e).getSold()).isZero();
	}

	@Test
	void latePaymentAfterEventEndedFlagsRefund() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID x = holdGa(fx.customer(), e, 2);
		expire();
		Instant now = clock.instant();
		jdbc.update("update events set sale_starts_at = ?, starts_at = ?, ends_at = ? where id = ?",
				Timestamp.from(now.minus(Duration.ofHours(4))), Timestamp.from(now.minus(Duration.ofHours(3))),
				Timestamp.from(now), e.id());

		assertPaid200(x);

		assertThat(booking(x).getStatus()).isEqualTo(BookingStatus.EXPIRED);
		assertThat(booking(x).isRefundRequired()).isTrue();
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isZero();
	}

	@Test
	void doublePaymentFlagsRefund() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID id = holdGa(fx.customer(), e, 2);
		assertPaid200(id);
		assertThat(booking(id).isRefundRequired()).isFalse();

		assertPaid200(id); // a second paid event with a new event id

		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking(id).isRefundRequired()).isTrue();
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(type(e).getSold()).isEqualTo(2);
	}

	@Test
	void cancelledEventFlagsRefundAndReleasesPending() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID id = holdGa(fx.customer(), e, 2);
		jdbc.update("update events set status = 'CANCELLED' where id = ?", e.id());

		assertPaid200(id);

		Booking b = booking(id);
		assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
		assertThat(b.isRefundRequired()).isTrue();
		assertThat(b.getCancelledAt()).isEqualTo(clock.instant());
		assertThat(b.getCancelledBy()).isNull();
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isZero();
		assertThat(tickets.findByBookingIdOrderById(id)).isEmpty();
	}
}
