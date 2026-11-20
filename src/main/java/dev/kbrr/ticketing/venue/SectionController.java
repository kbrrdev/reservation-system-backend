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
@RequestMapping("/api/organizer/venues/{venueId}/sections")
class SectionController {

	private final SectionService sections;

	SectionController(SectionService sections) {
		this.sections = sections;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	SectionResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID venueId,
			@Valid @RequestBody SectionRequest req) {
		return sections.create(UUID.fromString(jwt.getSubject()), venueId, req);
	}

	@GetMapping
	List<SectionResponse> list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID venueId) {
		return sections.list(UUID.fromString(jwt.getSubject()), venueId);
	}
}
