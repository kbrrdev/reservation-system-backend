package dev.kbrr.ticketing.event;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "ticket_type_sections")
public class TicketTypeSection {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID eventId;
	private UUID ticketTypeId;
	private UUID sectionId;

	protected TicketTypeSection() {
	}

	TicketTypeSection(UUID eventId, UUID ticketTypeId, UUID sectionId) {
		this.eventId = eventId;
		this.ticketTypeId = ticketTypeId;
		this.sectionId = sectionId;
	}

	public UUID getId() { return id; }
	public UUID getEventId() { return eventId; }
	public UUID getTicketTypeId() { return ticketTypeId; }
	public UUID getSectionId() { return sectionId; }
}
