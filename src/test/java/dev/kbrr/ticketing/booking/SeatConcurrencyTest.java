package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventSeat;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Calls the service directly from many threads so the seat row locks, not MockMvc, decide the outcome. */
@IntegrationTest
class SeatConcurrencyTest {

	@Autowired BookingService bookings;
	@Autowired EventSeatRepository eventSeats;
	@Autowired TestFixtures fx;
	@Autowired JdbcTemplate jdbc;

	/** One section, one row of {@code seats} seats, all one ticket type. */
	private EventResponse event(int seats) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		UUID section = fx.section(org, v, "Main", List.of("A"), seats).id();
		return fx.reservedEvent(org, v, Map.of("Std", List.of(section)), 100000);
	}

	/**
	 * Starts every hold at once behind a gate. Each outcome is the response on success or the ApiException's code;
	 * any other exception (a deadlock, an IllegalStateException) fails the test.
	 */
	private List<Object> race(List<HoldRequest> requests) throws Exception {
		List<UUID> customers = new ArrayList<>();
		for (int i = 0; i < requests.size(); i++) {
			customers.add(fx.customer().getId());
		}
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<BookingResponse>> futures = new ArrayList<>();
			for (int i = 0; i < requests.size(); i++) {
				UUID customer = customers.get(i);
				HoldRequest req = requests.get(i);
				futures.add(pool.submit(() -> {
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return bookings.hold(customer, req);
				}));
			}
			start.countDown();
			List<Object> outcomes = new ArrayList<>();
			for (Future<BookingResponse> f : futures) {
				try {
					outcomes.add(f.get(30, TimeUnit.SECONDS));
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

	private List<UUID> seatIds(EventResponse e) {
		return eventSeats.findSeatMap(e.id()).stream().map(SeatMapRow::eventSeatId).toList();
	}

	@Test
	void sameSeatExactlyOneWins() throws Exception {
		EventResponse e = event(1);
		UUID seat = seatIds(e).getFirst();

		List<Object> outcomes = race(Collections.nCopies(20, new HoldRequest(e.id(), null, List.of(seat))));

		List<BookingResponse> wins = outcomes.stream().filter(BookingResponse.class::isInstance)
				.map(BookingResponse.class::cast).toList();
		assertThat(wins).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o == ErrorCode.TICKETS_UNAVAILABLE).hasSize(19);
		EventSeat s = eventSeats.findById(seat).orElseThrow();
		assertThat(s.getStatus()).isEqualTo(EventSeatStatus.HELD);
		assertThat(s.getBookingId()).isEqualTo(wins.getFirst().id());
	}

	@Test
	void overlappingSetsNeverDeadlock() throws Exception {
		EventResponse e = event(4);
		List<UUID> seats = seatIds(e);
		List<HoldRequest> requests = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			List<UUID> shuffled = new ArrayList<>(seats);
			Collections.shuffle(shuffled);
			requests.add(new HoldRequest(e.id(), null, List.of(shuffled.get(0), shuffled.get(1))));
		}

		List<Object> outcomes = race(requests);

		List<BookingResponse> wins = outcomes.stream().filter(BookingResponse.class::isInstance)
				.map(BookingResponse.class::cast).toList();
		assertThat(outcomes).filteredOn(o -> !(o instanceof BookingResponse))
				.allMatch(o -> o == ErrorCode.TICKETS_UNAVAILABLE);
		assertThat(wins).isNotEmpty();
		assertThat(eventSeats.countByEventIdAndStatus(e.id(), EventSeatStatus.HELD)).isEqualTo(2L * wins.size());
		for (BookingResponse win : wins) {
			for (UUID seat : win.eventSeatIds()) {
				assertThat(eventSeats.findById(seat).orElseThrow().getBookingId()).isEqualTo(win.id());
			}
		}
		assertThat(jdbc.queryForList("""
				select bs.event_seat_id from booking_seats bs join bookings b on b.id = bs.booking_id
				where b.event_id = ? and b.status = 'PENDING_PAYMENT'
				group by bs.event_seat_id having count(*) > 1""", e.id())).isEmpty();
	}
}
