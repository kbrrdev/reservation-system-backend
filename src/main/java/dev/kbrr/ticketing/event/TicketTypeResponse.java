package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.common.Money;
import java.util.List;
import java.util.UUID;

public record TicketTypeResponse(UUID id, String name, Money price, int capacity, List<UUID> sectionIds) {
}
