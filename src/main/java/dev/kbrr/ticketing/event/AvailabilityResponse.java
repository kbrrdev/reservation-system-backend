package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.common.Money;
import java.util.List;
import java.util.UUID;

public record AvailabilityResponse(UUID eventId, SeatingMode seatingMode, List<Item> ticketTypes,
		List<Section> sections) {

	public record Item(UUID ticketTypeId, String name, Money price, int remaining) {
	}

	public record Section(UUID sectionId, String name, UUID ticketTypeId, Money price, List<SeatView> seats) {
	}

	public record SeatView(UUID eventSeatId, String row, int number, EventSeatStatus status) {
	}
}
