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

	/** Locks the requested seats; any that are missing or belong to another event make the whole request a 404. */
	@Transactional(propagation = Propagation.MANDATORY)
	List<EventSeat> lockForHold(UUID eventId, Set<UUID> seatIds) {
		List<EventSeat> locked = seats.lockByIds(seatIds);
		if (locked.size() != seatIds.size() || locked.stream().anyMatch(s -> !s.getEventId().equals(eventId))) {
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
}
