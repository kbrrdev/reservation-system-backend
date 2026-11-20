package dev.kbrr.ticketing.booking;

import java.time.Instant;
import java.util.UUID;

record TicketResponse(UUID id, String code, UUID ticketTypeId, String ticketTypeName, TicketStatus status,
		Instant issuedAt) {
}
