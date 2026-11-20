package dev.kbrr.ticketing.event;

import java.util.UUID;

public record SeatMapRow(UUID eventSeatId, UUID sectionId, String sectionName, String rowLabel, int rowIndex,
		int seatNumber, EventSeatStatus status, UUID ticketTypeId) {
}
