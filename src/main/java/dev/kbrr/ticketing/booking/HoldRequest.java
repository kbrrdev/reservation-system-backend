package dev.kbrr.ticketing.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Either {@code items} (general admission) or {@code eventSeatIds} (reserved seating); the service checks which. */
record HoldRequest(@NotNull UUID eventId, @Size(max = 20) List<@NotNull @Valid Item> items,
		@Size(max = 50) List<@NotNull UUID> eventSeatIds) {

	record Item(@NotNull UUID ticketTypeId, @NotNull @Min(1) Integer quantity) {
	}
}
