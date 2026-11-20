package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.event.EventRepository;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.SeatingMode;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves a no-longer-pending booking's inventory: sold (payment) or given back (expire, cancel). MANDATORY: the
 * caller already holds the booking lock and has moved the status, so these updates must commit with it. All reads
 * of the booking happen before the first bulk update, which clears the persistence context.
 */
@Component
class BookingInventory {

	private final EventRepository events;
	private final TicketTypeRepository ticketTypes;
	private final SeatInventory seatInventory;
	private final EventSeatRepository eventSeats;
	private final TicketRepository tickets;

	BookingInventory(EventRepository events, TicketTypeRepository ticketTypes, SeatInventory seatInventory,
			EventSeatRepository eventSeats, TicketRepository tickets) {
		this.events = events;
		this.ticketTypes = ticketTypes;
		this.seatInventory = seatInventory;
		this.eventSeats = eventSeats;
		this.tickets = tickets;
	}

	/** HELD → SOLD and issue tickets: one per seat (reserved) or {@code quantity} per item (GA). */
	@Transactional(propagation = Propagation.MANDATORY)
	void sell(Booking booking, Instant now) {
		UUID id = booking.getId();
		boolean reserved = isReserved(booking);
		int seatCount = booking.getEventSeatIds().size();
		List<BookingItem> items = lockOrder(booking);
		if (reserved) {
			tickets.saveAll(seatInventory.confirm(id, seatCount).stream()
					.map(s -> new Ticket(id, s.getTicketTypeId(), s.getId(), TicketCodes.newCode(), now)).toList());
			return;
		}
		for (BookingItem item : items) {
			expectOneRow(ticketTypes.confirm(item.getTicketTypeId(), item.getQuantity()), id);
		}
		tickets.saveAll(items.stream().flatMap(i -> IntStream.range(0, i.getQuantity())
				.mapToObj(n -> new Ticket(id, i.getTicketTypeId(), TicketCodes.newCode(), now))).toList());
	}

	/** Gives back the hold: its seats (reserved) or its ticket-type counters (GA). */
	@Transactional(propagation = Propagation.MANDATORY)
	void release(Booking booking) {
		UUID id = booking.getId();
		if (isReserved(booking)) {
			seatInventory.release(id, booking.getEventSeatIds().size());
			return;
		}
		for (BookingItem item : lockOrder(booking)) { // the list is built before the first update detaches booking
			expectOneRow(ticketTypes.release(item.getTicketTypeId(), item.getQuantity()), id);
		}
	}

	/**
	 * Re-takes, as HELD for this booking, the inventory an expired or cancelled booking recorded. All or nothing:
	 * everything is locked and checked first, so on {@code false} nothing changed.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean reclaim(Booking booking) {
		UUID id = booking.getId();
		if (isReserved(booking)) {
			List<UUID> seatIds = List.copyOf(booking.getEventSeatIds());
			if (!eventSeats.lockByIds(booking.getEventId(), seatIds).stream()
					.allMatch(s -> s.getStatus() == EventSeatStatus.AVAILABLE)) {
				return false;
			}
			seatInventory.hold(seatIds, id); // a missing seat shows up here as a short count: IllegalStateException
			return true;
		}
		List<BookingItem> items = lockOrder(booking); // read before hold clears the persistence context
		for (BookingItem item : items) {
			TicketType t = ticketTypes.findForUpdateById(item.getTicketTypeId()).orElseThrow();
			if (t.getCapacity() - t.getHeld() - t.getSold() < item.getQuantity()) {
				return false;
			}
		}
		for (BookingItem item : items) {
			expectOneRow(ticketTypes.hold(item.getTicketTypeId(), item.getQuantity()), id);
		}
		return true;
	}

	/** Decided by the event's mode before any inventory is touched; reserved never uses the ticket-type counters. */
	@Transactional(propagation = Propagation.MANDATORY)
	boolean isReserved(Booking booking) {
		return events.findSeatingModeById(booking.getEventId()).orElseThrow() == SeatingMode.RESERVED;
	}

	/** Java UUID.compareTo order of ticket type ids is the global lock order (the same sort as in hold). */
	private static List<BookingItem> lockOrder(Booking booking) {
		return booking.getItems().stream().sorted(Comparator.comparing(BookingItem::getTicketTypeId)).toList();
	}

	/** Our own locks make this exactly 1 row; anything else is a bug, so roll back rather than skew the counters. */
	static void expectOneRow(int rows, UUID bookingId) {
		if (rows != 1) {
			throw new IllegalStateException("Expected 1 row changed for booking " + bookingId + ", got " + rows);
		}
	}
}
