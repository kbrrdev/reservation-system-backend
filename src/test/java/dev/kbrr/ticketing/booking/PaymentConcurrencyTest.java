package dev.kbrr.ticketing.booking;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.MutableClock;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.payment.PaymentNotification;
import dev.kbrr.ticketing.payment.fake.FakePaymentGateway;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Payment races at service level, so the database locks (not MockMvc) decide the outcome. */
@IntegrationTest
class PaymentConcurrencyTest {

	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired BookingService bookings;
	@Autowired BookingCheckout checkout;
	@Autowired PaymentConfirmationService payments;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketRepository tickets;
	@Autowired TicketTypeRepository ticketTypes;
	@Autowired EventSeatRepository eventSeats;
	@Autowired FakePaymentGateway fake;
	@Autowired PlatformTransactionManager txManager;
	@Autowired JdbcTemplate jdbc;

	private EventResponse gaEvent(int capacity, int maxTickets) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, maxTickets, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	private static HoldRequest ga(EventResponse e, int quantity) {
		return new HoldRequest(e.id(), List.of(new HoldRequest.Item(e.ticketTypes().getFirst().id(), quantity)), null);
	}

	private TicketType type(EventResponse e) {
		return ticketTypes.findById(e.ticketTypes().getFirst().id()).orElseThrow();
	}

	private Booking booking(UUID id) {
		return bookingRepository.findById(id).orElseThrow();
	}

	private PaymentNotification paid(UUID id) {
		Booking b = booking(id);
		return new PaymentNotification("fake", "evt_" + UUID.randomUUID(), "payment.paid", true, id, b.getTotalMinor(),
				b.getCurrency(), "{}");
	}

	/** The outcome of a call: its result, or the ApiException's code. Anything else (a deadlock) fails the test. */
	private static Object outcome(Future<?> f) throws Exception {
		try {
			return f.get(30, SECONDS);
		} catch (ExecutionException e) {
			assertThat(e.getCause()).isInstanceOf(ApiException.class);
			return ((ApiException) e.getCause()).code();
		}
	}

	/** Runs every task at once behind a start gate. */
	private static List<Future<Object>> race(ExecutorService pool, List<Callable<Object>> tasks) {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Object>> futures = new ArrayList<>();
		for (Callable<Object> task : tasks) {
			futures.add(pool.submit(() -> {
				assertThat(start.await(10, SECONDS)).isTrue();
				return task.call();
			}));
		}
		start.countDown();
		return futures;
	}

	/** Polls until {@code n} backends of this database wait on a lock; deterministic instead of a sleep. */
	private void awaitLockWaiters(int n) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (jdbc.queryForObject("""
				select count(*) from pg_stat_activity
				where wait_event_type = 'Lock' and datname = current_database()""", Integer.class) < n) {
			assertThat(System.nanoTime()).as("%d lock waiters within 10 s", n).isLessThan(deadline);
			Thread.sleep(20);
		}
	}

	@Test
	void webhookVsExpiryNeverLosesPayment() throws Exception {
		List<EventResponse> events = new ArrayList<>();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			EventResponse e = gaEvent(10, 8);
			events.add(e);
			ids.add(bookings.hold(fx.customer().getId(), ga(e, 2)).id());
		}
		clock.advance(PAST_EXPIRY);

		List<Callable<Object>> tasks = new ArrayList<>();
		for (UUID id : ids) {
			PaymentNotification payment = paid(id);
			tasks.add(() -> {
				payments.handle(payment);
				return null;
			});
			tasks.add(() -> bookings.expire(id));
		}
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			for (Future<Object> f : race(pool, tasks)) {
				f.get(30, SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		for (int i = 0; i < ids.size(); i++) {
			UUID id = ids.get(i);
			assertThat(booking(id).getStatus()).as("booking %d", i).isEqualTo(BookingStatus.CONFIRMED);
			assertThat(booking(id).isRefundRequired()).isFalse();
			assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
			assertThat(type(events.get(i)).getHeld()).isZero();
			assertThat(type(events.get(i)).getSold()).isEqualTo(2);
		}
	}

	/**
	 * The test transaction holds the booking lock while the payment, then the expiry, queue behind it. Postgres
	 * grants the lock in queue order, so the payment confirms first and the expiry finds nothing to do.
	 */
	@Test
	void payWinsDeterministically() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID id = checkout.holdAndCheckout(fx.customer().getId(), ga(e, 2)).id();
		String reference = booking(id).getPaymentReference();
		assertThat(reference).isNotNull(); // else the expiredSessions assertion below proves nothing
		clock.advance(PAST_EXPIRY);
		PaymentNotification payment = paid(id);

		TransactionTemplate tx = new TransactionTemplate(txManager);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch commit = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			Future<?> holder = pool.submit(() -> tx.executeWithoutResult(s -> {
				bookingRepository.findForUpdateById(id).orElseThrow();
				locked.countDown();
				try {
					assertThat(commit.await(30, SECONDS)).isTrue();
				} catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
			}));
			assertThat(locked.await(10, SECONDS)).isTrue();

			Future<?> paying = pool.submit(() -> payments.handle(payment));
			awaitLockWaiters(1);
			Future<Boolean> expiring = pool.submit(() -> bookings.expire(id));
			awaitLockWaiters(2);

			commit.countDown();
			holder.get(30, SECONDS);
			paying.get(30, SECONDS);
			assertThat(expiring.get(30, SECONDS)).isFalse();
		} finally {
			commit.countDown();
			pool.shutdownNow();
		}

		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(booking(id).isRefundRequired()).isFalse();
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(fake.expiredSessions()).doesNotContain(reference);
	}

	@Test
	void duplicateDeliveriesAtOnceProcessedOnce() throws Exception {
		EventResponse e = gaEvent(10, 8);
		UUID id = bookings.hold(fx.customer().getId(), ga(e, 2)).id();
		Booking b = booking(id);
		String eventId = "evt_" + UUID.randomUUID();

		List<Callable<Object>> tasks = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			tasks.add(() -> fx.webhook(eventId, "payment.paid", id, b.getTotalMinor(), b.getCurrency()).getStatus());
		}
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		List<Object> statuses = new ArrayList<>();
		try {
			for (Future<Object> f : race(pool, tasks)) {
				statuses.add(f.get(30, SECONDS));
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(statuses).containsOnly(200);
		assertThat(jdbc.queryForObject(
				"select count(*) from payment_events where provider = 'fake' and provider_event_id = ?", Integer.class,
				eventId)).isEqualTo(1);
		assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
		assertThat(tickets.findByBookingIdOrderById(id)).hasSize(2);
		assertThat(type(e).getSold()).isEqualTo(2);
	}

	/** The user lock, which the payment takes first, serializes the late re-claim against the new hold's limit check. */
	@Test
	void latePaymentVsRebookNeverExceedsLimit() throws Exception {
		EventResponse e = gaEvent(10, 2);
		UUID customer = fx.customer().getId();
		UUID x = bookings.hold(customer, ga(e, 2)).id();
		clock.advance(PAST_EXPIRY);
		assertThat(bookings.expire(x)).isTrue();
		PaymentNotification payment = paid(x);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		Object holdOutcome;
		try {
			List<Future<Object>> f = race(pool, List.of(() -> {
				payments.handle(payment);
				return null;
			}, () -> bookings.hold(customer, ga(e, 2))));
			f.get(0).get(30, SECONDS);
			holdOutcome = outcome(f.get(1));
		} finally {
			pool.shutdownNow();
		}

		assertThat(bookingRepository.countActiveTickets(customer, e.id())).isLessThanOrEqualTo(2);
		Booking xb = booking(x);
		if (holdOutcome instanceof BookingResponse) {
			assertThat(xb.getStatus()).isEqualTo(BookingStatus.EXPIRED);
			assertThat(xb.isRefundRequired()).isTrue();
			assertThat(tickets.findByBookingIdOrderById(x)).isEmpty();
		} else {
			assertThat(holdOutcome).isEqualTo(ErrorCode.TICKET_LIMIT_EXCEEDED);
			assertThat(xb.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
			assertThat(xb.isRefundRequired()).isFalse();
			assertThat(tickets.findByBookingIdOrderById(x)).hasSize(2);
		}
		assertThat(type(e).getHeld() + type(e).getSold()).isEqualTo(2);
	}

	@Test
	void cancelWhileOthersGrabSameSeats() throws Exception {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID section = fx.section(org, v, "Main", List.of("A"), 4).id();
		EventResponse e = fx.reservedEvent(org, v, Map.of("Std", List.of(section)), 100000);
		List<UUID> all = eventSeats.findSeatMap(e.id()).stream().map(SeatMapRow::eventSeatId).toList();
		UUID s1 = all.get(0);
		UUID s2 = all.get(1);
		User owner = fx.customer();
		UUID x = bookings.hold(owner.getId(), new HoldRequest(e.id(), null, List.of(s1, s2))).id();
		List<UUID> customers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			customers.add(fx.customer().getId());
		}

		List<Callable<Object>> tasks = new ArrayList<>();
		tasks.add(() -> bookings.cancel(owner.getId(), x));
		for (UUID c : customers) {
			tasks.add(() -> bookings.hold(c, new HoldRequest(e.id(), null, List.of(s2, s1))));
		}
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		List<Object> outcomes = new ArrayList<>();
		try {
			for (Future<Object> f : race(pool, tasks)) {
				outcomes.add(outcome(f));
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(outcomes.getFirst()).isInstanceOf(BookingResponse.class); // the cancel always succeeds
		assertThat(booking(x).getStatus()).isEqualTo(BookingStatus.CANCELLED);
		List<Object> holds = outcomes.subList(1, outcomes.size());
		assertThat(holds).filteredOn(o -> !(o instanceof BookingResponse))
				.allMatch(o -> o == ErrorCode.TICKETS_UNAVAILABLE || o == ErrorCode.PENDING_HOLD_EXISTS);
		assertThat(holds).filteredOn(BookingResponse.class::isInstance).hasSizeLessThanOrEqualTo(1);
		for (UUID seat : all) {
			Map<String, Object> row = jdbc.queryForMap("select status, booking_id from event_seats where id = ?", seat);
			if ("AVAILABLE".equals(row.get("status"))) {
				assertThat(row.get("booking_id")).isNull();
			} else {
				assertThat(row.get("status")).isEqualTo("HELD");
				UUID holder = (UUID) row.get("booking_id");
				assertThat(booking(holder).getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
				assertThat(jdbc.queryForObject("""
						select count(*) from booking_seats bs join bookings b on b.id = bs.booking_id
						where bs.event_seat_id = ? and b.status = 'PENDING_PAYMENT'""", Integer.class, seat)).isEqualTo(1);
			}
		}
	}
}
