package dev.kbrr.ticketing.user;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;

	UserService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public User get(UUID id) {
		return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
	}

	// Set semantics make this idempotent; dirty checking flushes the new role row at commit.
	@Transactional
	public User addOrganizerRole(UUID id) {
		User user = get(id);
		user.addRole(Role.ORGANIZER);
		return user;
	}
}
