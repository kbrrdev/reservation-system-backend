package dev.kbrr.ticketing.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record TicketTypeRequest(
		@NotBlank @Size(max = 100) String name,
		@NotNull @PositiveOrZero @Max(10_000_000_000L) Long priceMinor,
		@Min(1) @Max(1_000_000) Integer capacity,
		@Size(max = 50) List<@NotNull UUID> sectionIds) {
}
