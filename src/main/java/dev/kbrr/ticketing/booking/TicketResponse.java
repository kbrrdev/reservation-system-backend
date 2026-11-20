package dev.kbrr.ticketing.booking;

import java.time.Instant;
import java.util.UUID;

/** The seat fields are null for general admission. */
record TicketResponse(UUID id, String code, UUID ticketTypeId, String ticketTypeName, TicketStatus status,
		Instant issuedAt, UUID eventSeatId, String section, String row, Integer number) {
}
