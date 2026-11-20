package dev.kbrr.ticketing.user;

import static org.assertj.core.api.Assertions.assertThat;

import dev.kbrr.ticketing.IntegrationTest;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

@IntegrationTest
class DevUserSeederTest {

	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;
	@Autowired Clock clock;

	@Test
	void seedsThreeUsersOnce() throws Exception {
		var seeder = new DevUserSeeder(userRepository, passwordEncoder, clock, new DevSeedProperties("devpassword123"));
		long before = userRepository.count();

		seeder.run(null);
		seeder.run(null);

		assertThat(userRepository.count()).isEqualTo(before + 3);
		User admin = userRepository.findByEmail("admin@example.test").orElseThrow();
		assertThat(admin.getRoles()).containsExactlyInAnyOrder(Role.CUSTOMER, Role.ADMIN);
		assertThat(passwordEncoder.matches("devpassword123", admin.getPasswordHash())).isTrue();
	}
}
