package dev.kbrr.ticketing.venue;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/organizer/venues")
class VenueController {

	private final VenueService venues;

	VenueController(VenueService venues) {
		this.venues = venues;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	VenueResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VenueRequest req) {
		return venues.create(UUID.fromString(jwt.getSubject()), req);
	}

	@GetMapping
	List<VenueResponse> list(@AuthenticationPrincipal Jwt jwt) {
		return venues.list(UUID.fromString(jwt.getSubject()));
	}

	@GetMapping("/{id}")
	VenueResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
		return venues.get(UUID.fromString(jwt.getSubject()), id);
	}
}
