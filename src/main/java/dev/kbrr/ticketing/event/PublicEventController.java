package dev.kbrr.ticketing.event;

import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
class PublicEventController {

	private final EventService events;

	PublicEventController(EventService events) {
		this.events = events;
	}

	/** Sort is fixed (startsAt, id) so paging is stable and clients cannot sort by arbitrary fields. */
	@GetMapping
	PagedModel<EventResponse> search(@RequestParam(required = false) String country,
			@RequestParam(required = false) String city, @RequestParam(required = false) EventCategory category,
			@RequestParam(required = false) @DateTimeFormat(iso = DATE_TIME) OffsetDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DATE_TIME) OffsetDateTime to,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return new PagedModel<>(events.search(country, city, category, from == null ? null : from.toInstant(),
				to == null ? null : to.toInstant(), PageRequest.of(page, size, Sort.by("startsAt", "id"))));
	}

	@GetMapping("/{id}")
	EventResponse get(@PathVariable UUID id) {
		return events.getPublic(id);
	}

	@GetMapping("/{id}/availability")
	AvailabilityResponse availability(@PathVariable UUID id) {
		return events.availability(id);
	}
}
