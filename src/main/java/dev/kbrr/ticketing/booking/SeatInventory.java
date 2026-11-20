package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.event.EventSeat;
import dev.kbrr.ticketing.event.EventSeatRepository;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes {@code event_seats} for bookings. Seats are always locked first by a query ordered by
 * id (Postgres uuid order), so overlapping seat sets lock in the same order and can't deadlock. MANDATORY: the
 * locks only mean something inside the caller's transaction.
 */
@Component
class SeatInventory {

	private final EventSeatRepository seats;

	SeatInventory(EventSeatRepository seats) {
		this.seats = seats;
	}

	/** Locks the requested seats of the event; any that are missing or belong to another event make the whole request a 404. */
	@Transactional(propagation = Propagation.MANDATORY)
	List<EventSeat> lockForHold(UUID eventId, Set<UUID> seatIds) {
		List<EventSeat> locked = seats.lockByIds(eventId, seatIds);
		if (locked.size() != seatIds.size()) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Seat not found");
		}
		return locked;
	}

	/** Seats were locked and seen AVAILABLE by {@link #lockForHold}, so anything but a full match is a bug. */
	@Transactional(propagation = Propagation.MANDATORY)
	void hold(Collection<UUID> seatIds, UUID bookingId) {
		int rows = seats.markHeld(seatIds, bookingId);
		if (rows != seatIds.size()) {
			throw new IllegalStateException(
					"Expected " + seatIds.size() + " seats held for booking " + bookingId + ", got " + rows);
		}
	}

	/**
	 * HELD → SOLD for the booking's seats. The caller holds the booking lock, so the seats are exactly the ones it
	 * held; returns them as read under the lock, since markSold clears the persistence context.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	List<EventSeat> confirm(UUID bookingId, int expected) {
		List<EventSeat> locked = seats.lockByBookingId(bookingId);
		expectRows(locked.size(), expected, "locked", bookingId);
		expectRows(seats.markSold(bookingId), expected, "sold", bookingId);
		return locked;
	}

	/** HELD → AVAILABLE for the booking's seats (expire, cancel). */
	@Transactional(propagation = Propagation.MANDATORY)
	void release(UUID bookingId, int expected) {
		expectRows(seats.lockByBookingId(bookingId).size(), expected, "locked", bookingId);
		expectRows(seats.markAvailable(bookingId), expected, "released", bookingId);
	}

	private static void expectRows(int rows, int expected, String what, UUID bookingId) {
		if (rows != expected) {
			throw new IllegalStateException(
					"Expected " + expected + " seats " + what + " for booking " + bookingId + ", got " + rows);
		}
	}
}
