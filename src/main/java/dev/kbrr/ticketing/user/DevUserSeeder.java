package dev.kbrr.ticketing.user;

import java.time.Clock;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Local-dev accounts only; the bean exists solely under the "dev" profile. Idempotent: skips existing emails. */
@Component
@Profile("dev")
public class DevUserSeeder implements ApplicationRunner {

	private final UserRepository users;
	private final PasswordEncoder encoder;
	private final Clock clock;
	private final DevSeedProperties props;

	public DevUserSeeder(UserRepository users, PasswordEncoder encoder, Clock clock, DevSeedProperties props) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
		this.props = props;
	}

	@Override
	public void run(ApplicationArguments args) {
		seed("admin@example.test", "Dev Admin", Set.of(Role.CUSTOMER, Role.ADMIN));
		seed("organizer@example.test", "Dev Organizer", Set.of(Role.CUSTOMER, Role.ORGANIZER));
		seed("customer@example.test", "Dev Customer", Set.of(Role.CUSTOMER));
	}

	private void seed(String email, String name, Set<Role> roles) {
		if (!users.existsByEmail(email)) {
			users.save(new User(email, encoder.encode(props.password()), name, roles, clock.instant()));
		}
	}
}
