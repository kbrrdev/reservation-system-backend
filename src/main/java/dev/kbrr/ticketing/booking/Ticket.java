package dev.kbrr.ticketing.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets")
class Ticket {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID bookingId;
	private UUID ticketTypeId;
	private UUID eventSeatId; // null for general admission
	private String code;

	@Enumerated(EnumType.STRING)
	private TicketStatus status;

	private Instant issuedAt;

	protected Ticket() {
	}

	Ticket(UUID bookingId, UUID ticketTypeId, String code, Instant issuedAt) {
		this(bookingId, ticketTypeId, null, code, issuedAt);
	}

	Ticket(UUID bookingId, UUID ticketTypeId, UUID eventSeatId, String code, Instant issuedAt) {
		this.bookingId = bookingId;
		this.ticketTypeId = ticketTypeId;
		this.eventSeatId = eventSeatId;
		this.code = code;
		this.status = TicketStatus.VALID;
		this.issuedAt = issuedAt;
	}

	UUID getId() { return id; }
	UUID getBookingId() { return bookingId; }
	UUID getTicketTypeId() { return ticketTypeId; }
	UUID getEventSeatId() { return eventSeatId; }
	String getCode() { return code; }
	TicketStatus getStatus() { return status; }
	Instant getIssuedAt() { return issuedAt; }
}
