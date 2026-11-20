package dev.kbrr.ticketing.event;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Rows are created by {@link EventSeatRepository#generate} and changed only by bulk queries (no @Version). */
@Entity
@Table(name = "event_seats")
public class EventSeat {

	@Id
	private UUID id;

	private UUID eventId;
	private UUID seatId;
	private UUID ticketTypeId;

	@Enumerated(EnumType.STRING)
	private EventSeatStatus status;

	private UUID bookingId;

	protected EventSeat() {
	}

	public UUID getId() { return id; }
	public UUID getEventId() { return eventId; }
	public UUID getSeatId() { return seatId; }
	public UUID getTicketTypeId() { return ticketTypeId; }
	public EventSeatStatus getStatus() { return status; }
	public UUID getBookingId() { return bookingId; }
}
