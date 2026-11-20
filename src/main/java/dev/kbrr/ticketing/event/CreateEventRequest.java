package dev.kbrr.ticketing.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// Schedule fields are venue-local wall-clock times; the pattern rejects any offset or Z suffix.
public record CreateEventRequest(
		@NotNull UUID venueId,
		@NotBlank @Size(max = 200) String title,
		@Size(max = 5000) String description,
		@NotNull EventCategory category,
		@NotNull @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm[:ss]") LocalDateTime startsAt,
		@NotNull @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm[:ss]") LocalDateTime endsAt,
		@NotNull @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm[:ss]") LocalDateTime saleStartsAt,
		Integer maxTicketsPerCustomer,
		@NotEmpty @Size(max = 20) List<@NotNull @Valid TicketTypeRequest> ticketTypes) {
}
