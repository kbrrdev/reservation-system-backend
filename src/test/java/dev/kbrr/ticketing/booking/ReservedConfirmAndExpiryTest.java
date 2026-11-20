package dev.kbrr.ticketing.booking;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.MutableClock;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeat;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.payment.PaymentNotification;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class ReservedConfirmAndExpiryTest {

	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired BookingService bookings;
	@Autowired PaymentConfirmationService payments;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketRepository tickets;
	@Autowired EventSeatRepository eventSeats;
	@Autowired ExpiryJob expiryJob;
	@Autowired JdbcTemplate jdbc;

	/** One section "Orchestra", row A with {@code seats} seats, ticket type Premium at 250000. */
	private EventResponse event(int seats) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID orchestra = fx.section(org, v, "Orchestra", List.of("A"), seats).id();
		return fx.reservedEvent(org, v, Map.of("Premium", List.of(orchestra)), 250000);
	}

	private List<UUID> seats(EventResponse e) {
		return eventSeats.findSeatMap(e.id()).stream().map(SeatMapRow::eventSeatId).toList();
	}

	private ResultActions hold(User u, EventResponse e, UUID... seats) throws Exception {
		String ids = List.of(seats).stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
		return mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content("{\"eventId\":\"%s\",\"eventSeatIds\":[%s]}".formatted(e.id(), ids)));
	}

	private UUID holdId(User u, EventResponse e, UUID... seats) throws Exception {
		return UUID.fromString(JsonPath.read(hold(u, e, seats).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id"));
	}

	/** A paid notification for the booking's exact total, as the webhook controller would pass it on. */
	private PaymentNotification paid(UUID id) {
		Booking b = bookingRepository.findById(id).orElseThrow();
		return new PaymentNotification("fake", "evt_" + UUID.randomUUID(), "payment.paid", true, id, b.getTotalMinor(),
				b.getCurrency(), "{}");
	}

	private ResultActions cancel(User u, UUID id) throws Exception {
		return mvc.perform(delete("/api/bookings/" + id).header("Authorization", fx.bearer(u)));
	}

	private EventSeat seat(UUID id) {
		return eventSeats.findById(id).orElseThrow();
	}

	private BookingStatus statusOf(UUID id) {
		return bookingRepository.findById(id).orElseThrow().getStatus();
	}

	/** Reserved flows never touch the ticket-type counters. */
	private void assertCountersZero(EventResponse e) {
		assertThat(jdbc.queryForObject(
				"select coalesce(sum(held),0) + coalesce(sum(sold),0) from ticket_types where event_id = ?", Long.class,
				e.id())).isZero();
	}

	@Test
	void payConfirmsSeatsAndIssuesSeatTickets() throws Exception {
		EventResponse e = event(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		User c = fx.customer();
		UUID id = holdId(c, e, s1, s2);

		assertThat(fx.pay(id).getStatus()).isEqualTo(200);
		assertThat(statusOf(id)).isEqualTo(BookingStatus.CONFIRMED);
		for (UUID s : List.of(s1, s2)) {
			assertThat(seat(s).getStatus()).isEqualTo(EventSeatStatus.SOLD);
			assertThat(seat(s).getBookingId()).isEqualTo(id);
		}

		String body = mvc.perform(get("/api/bookings/" + id + "/tickets").header("Authorization", fx.bearer(c)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[*].eventSeatId", containsInAnyOrder(s1.toString(), s2.toString())))
				.andExpect(jsonPath("$[*].section", containsInAnyOrder("Orchestra", "Orchestra")))
				.andExpect(jsonPath("$[*].row", containsInAnyOrder("A", "A")))
				.andExpect(jsonPath("$[*].number", containsInAnyOrder(1, 2)))
				.andExpect(jsonPath("$[*].ticketTypeName", containsInAnyOrder("Premium", "Premium")))
				.andReturn().getResponse().getContentAsString();
		List<String> codes = JsonPath.read(body, "$[*].code");
		assertThat(new HashSet<>(codes)).hasSize(2);
		// Seat s1 is number 1 (findSeatMap orders by seat number): the label belongs to the right seat.
		List<Integer> s1Number = JsonPath.read(body, "$[?(@.eventSeatId=='" + s1 + "')].number");
		assertThat(s1Number).containsExactly(1);
		assertCountersZero(e);
	}

	@Test
	void expiryReturnsSeats() throws Exception {
		EventResponse e = event(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		User c = fx.customer();
		UUID id = holdId(c, e, s1, s2);

		clock.advance(PAST_EXPIRY);
		expiryJob.run();

		assertThat(statusOf(id)).isEqualTo(BookingStatus.EXPIRED);
		for (UUID s : List.of(s1, s2)) {
			assertThat(seat(s).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
			assertThat(seat(s).getBookingId()).isNull();
		}
		mvc.perform(get("/api/bookings/" + id).header("Authorization", fx.bearer(c)))
				.andExpect(jsonPath("$.eventSeatIds", containsInAnyOrder(s1.toString(), s2.toString())));
		assertCountersZero(e);
		hold(fx.customer(), e, s1, s2).andExpect(status().isCreated());
	}

	@Test
	void cancelReturnsSeatsOnce() throws Exception {
		EventResponse e = event(3);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		User c = fx.customer();
		UUID id = holdId(c, e, s1, s2);

		cancel(c, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(seat(s1).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
		assertThat(seat(s2).getStatus()).isEqualTo(EventSeatStatus.AVAILABLE);
		assertThat(seat(s1).getBookingId()).isNull();
		assertThat(seat(s2).getBookingId()).isNull();
		assertCountersZero(e);
		cancel(c, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
		assertThat(eventSeats.countByEventIdAndStatus(e.id(), EventSeatStatus.AVAILABLE)).isEqualTo(3);
	}

	@Test
	void soldSeatCannotBeHeld() throws Exception {
		EventResponse e = event(3);
		UUID s1 = seats(e).get(0);
		User c = fx.customer();
		assertThat(fx.pay(holdId(c, e, s1)).getStatus()).isEqualTo(200);

		hold(fx.customer(), e, s1).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TICKETS_UNAVAILABLE"));
		assertThat(seat(s1).getStatus()).isEqualTo(EventSeatStatus.SOLD);
	}

	/** Payment and expiry race; either order ends CONFIRMED (a later payment re-claims the still-free seats). */
	@Test
	void payRaceWithExpiry() throws Exception {
		List<List<UUID>> seatsOf = new ArrayList<>();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			EventResponse e = event(2);
			UUID owner = fx.customer().getId();
			List<UUID> s = seats(e);
			seatsOf.add(s);
			ids.add(bookings.hold(owner, new HoldRequest(e.id(), null, s)).id());
		}
		clock.advance(PAST_EXPIRY);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < 20; i++) {
				UUID id = ids.get(i);
				PaymentNotification payment = paid(id);
				CountDownLatch start = new CountDownLatch(1);
				Future<?> paid = pool.submit(() -> {
					assertThat(start.await(10, SECONDS)).isTrue();
					payments.handle(payment);
					return null;
				});
				Future<Boolean> expired = pool.submit(() -> {
					assertThat(start.await(10, SECONDS)).isTrue();
					return bookings.expire(id);
				});
				start.countDown();
				try {
					paid.get(30, SECONDS);
					expired.get(30, SECONDS);
				} catch (ExecutionException ex) {
					throw new AssertionError("race " + i + " failed", ex.getCause());
				}

				List<EventSeat> s = seatsOf.get(i).stream().map(this::seat).toList();
				assertThat(statusOf(id)).as("race %d", i).isEqualTo(BookingStatus.CONFIRMED);
				assertThat(bookingRepository.findById(id).orElseThrow().isRefundRequired()).isFalse();
				assertThat(s).allSatisfy(x -> {
					assertThat(x.getStatus()).isEqualTo(EventSeatStatus.SOLD);
					assertThat(x.getBookingId()).isEqualTo(id);
				});
				assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void expireWhileOthersGrabSameSeats() throws Exception {
		EventResponse e = event(2);
		UUID s1 = seats(e).get(0);
		UUID s2 = seats(e).get(1);
		UUID x = bookings.hold(fx.customer().getId(), new HoldRequest(e.id(), null, List.of(s1, s2))).id();
		clock.advance(PAST_EXPIRY);
		List<UUID> customers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			customers.add(fx.customer().getId());
		}

		ExecutorService pool = Executors.newFixedThreadPool(11);
		List<Object> outcomes = new ArrayList<>();
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<Boolean> expired = pool.submit(() -> {
				assertThat(start.await(10, SECONDS)).isTrue();
				return bookings.expire(x);
			});
			List<Future<BookingResponse>> holds = new ArrayList<>();
			for (UUID customer : customers) {
				holds.add(pool.submit(() -> {
					assertThat(start.await(10, SECONDS)).isTrue();
					return bookings.hold(customer, new HoldRequest(e.id(), null, List.of(s2, s1)));
				}));
			}
			start.countDown();
			assertThat(expired.get(30, SECONDS)).isTrue();
			for (Future<BookingResponse> f : holds) {
				try {
					outcomes.add(f.get(30, SECONDS));
				} catch (ExecutionException ex) {
					assertThat(ex.getCause()).isInstanceOf(ApiException.class);
					outcomes.add(((ApiException) ex.getCause()).code());
				}
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(outcomes).filteredOn(o -> !(o instanceof BookingResponse)).allMatch(o -> Set.of(
				ErrorCode.TICKETS_UNAVAILABLE, ErrorCode.PREVIOUS_HOLD_RELEASING, ErrorCode.PENDING_HOLD_EXISTS)
				.contains(o));
		assertThat(statusOf(x)).isEqualTo(BookingStatus.EXPIRED);
		for (UUID s : List.of(s1, s2)) {
			EventSeat seat = seat(s);
			if (seat.getStatus() == EventSeatStatus.AVAILABLE) {
				assertThat(seat.getBookingId()).isNull();
			} else {
				assertThat(seat.getStatus()).isEqualTo(EventSeatStatus.HELD);
				assertThat(statusOf(seat.getBookingId())).isEqualTo(BookingStatus.PENDING_PAYMENT);
			}
		}
		// No seat sits in two pending bookings, and every pending booking's seats are HELD by it.
		assertThat(jdbc.queryForList("""
				select bs.event_seat_id from booking_seats bs join bookings b on b.id = bs.booking_id
				where b.event_id = ? and b.status = 'PENDING_PAYMENT'
				group by bs.event_seat_id having count(*) > 1""", e.id())).isEmpty();
		assertThat(jdbc.queryForObject("""
				select count(*) from booking_seats bs join bookings b on b.id = bs.booking_id
				join event_seats es on es.id = bs.event_seat_id
				where b.event_id = ? and b.status = 'PENDING_PAYMENT'
				and (es.status <> 'HELD' or es.booking_id <> b.id)""", Long.class, e.id())).isZero();
	}
}
