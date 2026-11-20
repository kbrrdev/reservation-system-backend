package dev.kbrr.ticketing.user;

import java.util.Set;
import java.util.UUID;

public record UserResponse(UUID id, String email, String name, Set<Role> roles) {

	public static UserResponse from(User u) {
		return new UserResponse(u.getId(), u.getEmail(), u.getName(), Set.copyOf(u.getRoles()));
	}
}
