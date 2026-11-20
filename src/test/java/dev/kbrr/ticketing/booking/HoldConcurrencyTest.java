package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Calls the service directly from many threads so the database locks, not MockMvc, decide the outcome. */
@IntegrationTest
class HoldConcurrencyTest {

	@Autowired BookingService bookings;
	@Autowired BookingRepository bookingRepository;
	@Autowired TicketTypeRepository ticketTypes;
	@Autowired TestFixtures fx;
	@Autowired Clock clock;

	private EventResponse event(int capacity, int maxTickets) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, maxTickets, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	/** Starts every hold at once behind a gate; each outcome is null for a success, else the error code. */
	private List<ErrorCode> race(List<UUID> userIds, HoldRequest req) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(userIds.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<BookingResponse>> futures = new ArrayList<>();
			for (UUID userId : userIds) {
				futures.add(pool.submit(() -> {
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return bookings.hold(userId, req);
				}));
			}
			start.countDown();
			List<ErrorCode> outcomes = new ArrayList<>();
			for (Future<BookingResponse> f : futures) {
				try {
					f.get(30, TimeUnit.SECONDS);
					outcomes.add(null);
				} catch (ExecutionException e) {
					assertThat(e.getCause()).isInstanceOf(ApiException.class);
					outcomes.add(((ApiException) e.getCause()).code());
				}
			}
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void gaNeverOversells() throws Exception {
		EventResponse e = event(10, 8);
		UUID type = e.ticketTypes().getFirst().id();
		List<UUID> customers = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			customers.add(fx.customer().getId());
		}

		List<ErrorCode> outcomes = race(customers, new HoldRequest(e.id(), List.of(new HoldRequest.Item(type, 1))));

		assertThat(outcomes).filteredOn(o -> o == null).hasSize(10);
		assertThat(outcomes).filteredOn(o -> o == ErrorCode.TICKETS_UNAVAILABLE).hasSize(20);
		TicketType t = ticketTypes.findById(type).orElseThrow();
		assertThat(t.getHeld()).isEqualTo(10);
		assertThat(t.getSold()).isZero();
	}

	@Test
	void oneCustomerParallelHoldsRespectLimit() throws Exception {
		EventResponse e = event(100, 3);
		UUID customer = fx.customer().getId();

		List<ErrorCode> outcomes = race(Collections.nCopies(8, customer),
				new HoldRequest(e.id(), List.of(new HoldRequest.Item(e.ticketTypes().getFirst().id(), 2))));

		assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o != null).hasSize(7)
				.allMatch(o -> o == ErrorCode.PENDING_HOLD_EXISTS || o == ErrorCode.TICKET_LIMIT_EXCEEDED);
		assertThat(bookingRepository.countActiveTickets(customer, e.id())).isEqualTo(2);
	}
}
