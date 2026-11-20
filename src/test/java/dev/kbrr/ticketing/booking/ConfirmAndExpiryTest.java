package dev.kbrr.ticketing.booking;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
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
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class ConfirmAndExpiryTest {

	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired BookingService bookings;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketRepository tickets;
	@Autowired TicketTypeRepository ticketTypes;
	@Autowired ExpiryJob expiryJob;
	@Autowired PlatformTransactionManager txManager;

	private EventResponse event(int capacity, int maxTickets) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, maxTickets, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	private static UUID typeOf(EventResponse e) {
		return e.ticketTypes().getFirst().id();
	}

	private ResultActions hold(User u, EventResponse e, int quantity) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content("{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}"
						.formatted(e.id(), typeOf(e), quantity)));
	}

	private UUID holdId(User u, EventResponse e, int quantity) throws Exception {
		return UUID.fromString(JsonPath.read(hold(u, e, quantity).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id"));
	}

	private ResultActions fakePay(User u, UUID id) throws Exception {
		return mvc.perform(post("/api/bookings/" + id + "/fake-pay").header("Authorization", fx.bearer(u)));
	}

	private ResultActions cancel(User u, UUID id) throws Exception {
		return mvc.perform(delete("/api/bookings/" + id).header("Authorization", fx.bearer(u)));
	}

	private TicketType type(EventResponse e) {
		return ticketTypes.findById(typeOf(e)).orElseThrow();
	}

	private BookingStatus statusOf(UUID id) {
		return bookingRepository.findById(id).orElseThrow().getStatus();
	}

	@Test
	void fakePayConfirmsAndIssuesTickets() throws Exception {
		EventResponse e = event(10, 8);
		User c = fx.customer();
		UUID id = holdId(c, e, 3);

		fakePay(c, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
				.andExpect(jsonPath("$.items[0].quantity").value(3));
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isEqualTo(3);

		String body = mvc.perform(get("/api/bookings/" + id + "/tickets").header("Authorization", fx.bearer(c)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
				.andExpect(jsonPath("$[0].ticketTypeId").value(typeOf(e).toString()))
				.andExpect(jsonPath("$[0].ticketTypeName").value("Regular"))
				.andReturn().getResponse().getContentAsString();
		List<String> statuses = JsonPath.read(body, "$[*].status");
		assertThat(statuses).containsOnly("VALID");
		List<String> codes = JsonPath.read(body, "$[*].code");
		assertThat(new HashSet<>(codes)).hasSize(3);
		assertThat(codes).allSatisfy(code -> {
			assertThat(code).hasSize(22);
			assertThat(Base64.getUrlDecoder().decode(code)).hasSize(16);
		});
	}

	@Test
	void ticketCodesAreUnique() {
		Set<String> codes = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			codes.add(TicketCodes.newCode());
		}
		assertThat(codes).hasSize(1000);
	}

	@Test
	void expiryJobReleasesHold() throws Exception {
		EventResponse e = event(2, 8);
		User c = fx.customer();
		UUID id = holdId(c, e, 2);

		clock.advance(PAST_EXPIRY);
		expiryJob.run();

		assertThat(statusOf(id)).isEqualTo(BookingStatus.EXPIRED);
		assertThat(type(e).getHeld()).isZero();
		mvc.perform(get("/api/events/" + e.id() + "/availability"))
				.andExpect(jsonPath("$.ticketTypes[0].remaining").value(2));
		hold(c, e, 2).andExpect(status().isCreated());
	}

	@Test
	void payOrCancelAfterExpiry() throws Exception {
		EventResponse e = event(10, 8);
		User c = fx.customer();
		UUID id = holdId(c, e, 2);
		clock.advance(PAST_EXPIRY);
		assertThat(bookings.expire(id)).isTrue();

		fakePay(c, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
		cancel(c, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
		assertThat(type(e).getHeld()).isZero();
		assertThat(statusOf(id)).isEqualTo(BookingStatus.EXPIRED);
	}

	@Test
	void cancelReleasesOnce() throws Exception {
		EventResponse e = event(10, 8);
		User c = fx.customer();
		UUID id = holdId(c, e, 2);

		cancel(c, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(type(e).getHeld()).isZero();
		Booking b = bookingRepository.findById(id).orElseThrow();
		assertThat(b.getCancelledBy()).isEqualTo(c.getId());
		assertThat(b.getCancelledAt()).isEqualTo(clock.instant());

		cancel(c, id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
		assertThat(type(e).getHeld()).isZero();
	}

	@Test
	void expireSkipsConfirmed() throws Exception {
		EventResponse e = event(10, 8);
		User c = fx.customer();
		UUID id = holdId(c, e, 2);
		fakePay(c, id).andExpect(status().isOk());

		assertThat(bookings.expire(id)).isFalse();
		assertThat(statusOf(id)).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(type(e).getSold()).isEqualTo(2);
		assertThat(type(e).getHeld()).isZero();
	}

	@Test
	void othersCannotPayOrCancel() throws Exception {
		EventResponse e = event(10, 8);
		UUID id = holdId(fx.customer(), e, 2);
		User other = fx.customer();

		fakePay(other, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		cancel(other, id).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(get("/api/bookings/" + id + "/tickets").header("Authorization", fx.bearer(other)))
				.andExpect(status().isNotFound());
		assertThat(statusOf(id)).isEqualTo(BookingStatus.PENDING_PAYMENT);
		assertThat(type(e).getHeld()).isEqualTo(2);
	}

	@Test
	void limitCountsConfirmedTickets() throws Exception {
		EventResponse e = event(10, 3);
		User c = fx.customer();
		fakePay(c, holdId(c, e, 2)).andExpect(status().isOk());

		hold(c, e, 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TICKET_LIMIT_EXCEEDED"));
		hold(c, e, 1).andExpect(status().isCreated());
	}

	/**
	 * Deterministic interleaving: the confirming transaction holds the booking row lock, so expiry waits on it,
	 * then sees CONFIRMED and backs off. Fails if findForUpdateById stopped locking (expire would not block).
	 */
	@Test
	void expireWaitsForConfirmHoldingBookingLock() throws Exception {
		EventResponse e = event(10, 8);
		UUID owner = fx.customer().getId();
		UUID id = bookings.hold(owner, new HoldRequest(e.id(), List.of(new HoldRequest.Item(typeOf(e), 2)), null)).id();
		clock.advance(PAST_EXPIRY);

		TransactionTemplate tx = new TransactionTemplate(txManager);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch confirm = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> payer = pool.submit(() -> tx.executeWithoutResult(s -> {
				bookingRepository.findForUpdateById(id).orElseThrow();
				locked.countDown();
				try {
					assertThat(confirm.await(10, SECONDS)).isTrue();
				} catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
				bookings.confirmFake(owner, id); // joins this transaction, so the lock is held until commit
			}));
			assertThat(locked.await(10, SECONDS)).isTrue();

			Future<Boolean> expired = pool.submit(() -> bookings.expire(id));
			Thread.sleep(500);
			assertThat(expired.isDone()).as("expire must block on the booking row lock").isFalse();

			confirm.countDown();
			payer.get(30, SECONDS);
			assertThat(expired.get(30, SECONDS)).isFalse();
		} finally {
			pool.shutdownNow();
		}

		assertThat(statusOf(id)).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(type(e).getHeld()).isZero();
		assertThat(type(e).getSold()).isEqualTo(2);
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
	}

	/** Smoke test: fake-pay and expiry race on the same overdue booking; exactly one wins, counters move once. */
	@Test
	void payRaceWithExpiry() throws Exception {
		int quantity = 2;
		List<EventResponse> events = new ArrayList<>();
		List<UUID> owners = new ArrayList<>();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			EventResponse e = event(10, 8);
			UUID owner = fx.customer().getId();
			events.add(e);
			owners.add(owner);
			ids.add(bookings.hold(owner, new HoldRequest(e.id(), List.of(new HoldRequest.Item(typeOf(e), quantity)), null))
					.id());
		}
		clock.advance(PAST_EXPIRY);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int i = 0; i < 20; i++) {
				UUID id = ids.get(i);
				UUID owner = owners.get(i);
				CountDownLatch start = new CountDownLatch(1);
				Future<Boolean> paid = pool.submit(() -> {
					start.await(10, SECONDS);
					try {
						bookings.confirmFake(owner, id);
						return true;
					} catch (ApiException ex) {
						assertThat(ex.code()).isEqualTo(ErrorCode.BOOKING_NOT_PENDING);
						return false;
					}
				});
				Future<Boolean> expired = pool.submit(() -> {
					start.await(10, SECONDS);
					return bookings.expire(id);
				});
				start.countDown();
				boolean payWon;
				boolean expireWon;
				try {
					payWon = paid.get(30, SECONDS);
					expireWon = expired.get(30, SECONDS);
				} catch (ExecutionException ex) {
					throw new AssertionError("race " + i + " failed", ex.getCause());
				}

				assertThat(payWon).as("exactly one side wins, race %d", i).isNotEqualTo(expireWon);
				TicketType t = type(events.get(i));
				assertThat(t.getHeld()).isZero();
				if (payWon) {
					assertThat(statusOf(id)).isEqualTo(BookingStatus.CONFIRMED);
					assertThat(t.getSold()).isEqualTo(quantity);
					assertThat(tickets.findByBookingIdOrderById(id)).hasSize(quantity);
				} else {
					assertThat(statusOf(id)).isEqualTo(BookingStatus.EXPIRED);
					assertThat(t.getSold()).isZero();
					assertThat(tickets.findByBookingIdOrderById(id)).isEmpty();
				}
			}
		} finally {
			pool.shutdownNow();
		}
	}
}
