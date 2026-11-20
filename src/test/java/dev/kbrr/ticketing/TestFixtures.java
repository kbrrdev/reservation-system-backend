package dev.kbrr.ticketing;

import dev.kbrr.ticketing.user.Role;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Test data helpers; every call creates a unique user so tests can share one database. */
public class TestFixtures {

	private final UserRepository users;
	private final JwtEncoder encoder;
	private final Clock clock;

	TestFixtures(UserRepository users, JwtEncoder encoder, Clock clock) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
	}

	public User customer() {
		return save(Set.of(Role.CUSTOMER));
	}

	public User organizer() {
		return save(Set.of(Role.CUSTOMER, Role.ORGANIZER));
	}

	public String bearer(User u) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ticketing").subject(u.getId().toString())
				.claim("email", u.getEmail()).claim("roles", u.getRoles().stream().map(Role::name).toList())
				.issuedAt(clock.instant()).expiresAt(clock.instant().plus(Duration.ofHours(1))).build();
		return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private User save(Set<Role> roles) {
		return users.save(new User(UUID.randomUUID() + "@x.test", "{noop}unused", "Test", roles, clock.instant()));
	}
}
