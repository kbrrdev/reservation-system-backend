package dev.kbrr.ticketing.auth;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.user.Role;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
class AuthService {

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final Clock clock;

	AuthService(UserRepository users, PasswordEncoder encoder, Clock clock) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
	}

	// Not @Transactional: saveAndFlush runs in its own repository transaction, so a unique-constraint
	// violation surfaces here (catchable) instead of at an outer commit.
	User register(RegisterRequest req) {
		// BCrypt silently ignores bytes past 72, so reject instead of truncating.
		if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED, "password must be at most 72 bytes");
		}
		String email = User.normalizeEmail(req.email());
		if (users.existsByEmail(email)) {
			throw emailTaken();
		}
		Set<Role> roles = EnumSet.of(Role.CUSTOMER);
		if (req.organizer()) {
			roles.add(Role.ORGANIZER);
		}
		User user = new User(email, encoder.encode(req.password()), req.name(), roles, Instant.now(clock));
		try {
			return users.saveAndFlush(user);
		} catch (DataIntegrityViolationException e) {
			throw emailTaken(); // concurrent registration lost the race on the unique index
		}
	}

	private static ApiException emailTaken() {
		return new ApiException(ErrorCode.EMAIL_TAKEN, "email already registered");
	}
}
