package dev.kbrr.ticketing.venue;

import java.util.List;
import java.util.UUID;

public record SectionResponse(UUID id, String name, List<String> rowLabels, int seatsPerRow, int seatCount) {
}
