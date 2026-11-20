package dev.kbrr.ticketing.user;

import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
class MeController {

	private final UserService users;

	MeController(UserService users) {
		this.users = users;
	}

	@GetMapping
	UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return UserResponse.from(users.get(UUID.fromString(jwt.getSubject())));
	}

	@PostMapping("/organizer")
	UserResponse becomeOrganizer(@AuthenticationPrincipal Jwt jwt) {
		return UserResponse.from(users.addOrganizerRole(UUID.fromString(jwt.getSubject())));
	}
}
