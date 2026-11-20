package dev.kbrr.ticketing.event;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/events")
class OrganizerEventController {

	private final EventService events;

	OrganizerEventController(EventService events) {
		this.events = events;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	EventResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateEventRequest req) {
		return events.create(UUID.fromString(jwt.getSubject()), req);
	}

	@PutMapping("/{id}")
	EventResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
			@Valid @RequestBody UpdateEventRequest req) {
		return events.update(UUID.fromString(jwt.getSubject()), id, req);
	}

	@GetMapping
	List<EventResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return events.listOwned(UUID.fromString(jwt.getSubject()));
	}

	@GetMapping("/{id}")
	EventResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return events.getOwned(UUID.fromString(jwt.getSubject()), id);
	}
}
