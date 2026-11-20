package dev.kbrr.ticketing.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

record HoldRequest(@NotNull UUID eventId, @NotEmpty @Size(max = 20) List<@NotNull @Valid Item> items) {

	record Item(@NotNull UUID ticketTypeId, @NotNull @Min(1) Integer quantity) {
	}
}
