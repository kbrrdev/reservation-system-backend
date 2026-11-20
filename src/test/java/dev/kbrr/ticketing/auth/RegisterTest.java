package dev.kbrr.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.user.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class RegisterTest {

	@Autowired MockMvc mvc;
	@Autowired UserRepository userRepository;
	@Autowired PasswordEncoder passwordEncoder;

	private static String unique() {
		return UUID.randomUUID() + "@x.test";
	}

	private ResultActions register(String email, String password, boolean organizer) throws Exception {
		String body = "{\"email\":\"%s\",\"password\":\"%s\",\"name\":\"A\",\"organizer\":%s}"
				.formatted(email, password, organizer);
		return mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON).content(body));
	}

	@Test
	void organizerFieldIsOptional() throws Exception {
		mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password1\",\"name\":\"A\"}".formatted(unique())))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.roles", containsInAnyOrder("CUSTOMER")));
	}

	@Test
	void registersCustomer() throws Exception {
		register(unique(), "password1", false).andExpect(status().isCreated())
				.andExpect(jsonPath("$.roles[0]").value("CUSTOMER")).andExpect(jsonPath("$.roles.length()").value(1))
				.andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")))
				.andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void registersOrganizer() throws Exception {
		register(unique(), "password1", true).andExpect(status().isCreated())
				.andExpect(jsonPath("$.roles", containsInAnyOrder("CUSTOMER", "ORGANIZER")));
	}

	@Test
	void normalizesEmail() throws Exception {
		String local = UUID.randomUUID().toString();
		register("  " + local.toUpperCase() + "@Example.COM ", "password1", false).andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(local + "@example.com"));
	}

	@Test
	void duplicateEmailAnyCase() throws Exception {
		String email = unique();
		register(email, "password1", false).andExpect(status().isCreated());
		register(email.toUpperCase(), "password1", false).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
	}

	@Test
	void shortPassword() throws Exception {
		register(unique(), "short1", false).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[*].field", hasItem("password")));
	}

	@Test
	void passwordOver72Bytes() throws Exception {
		register(unique(), "a".repeat(73), false).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		register(unique(), "😀".repeat(19), false).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void invalidEmail() throws Exception {
		register("not-an-email", "password1", false).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void passwordIsHashed() throws Exception {
		String email = unique();
		register(email, "password1", false).andExpect(status().isCreated());
		String hash = userRepository.findByEmail(email).orElseThrow().getPasswordHash();
		assertThat(hash).isNotEqualTo("password1");
		assertThat(passwordEncoder.matches("password1", hash)).isTrue();
	}

	@Test
	void concurrentDuplicateRegistration() throws Exception {
		String email = unique();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return register(email, "password1", false).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get());
			}
			Collections.sort(statuses);
			assertThat(statuses).containsExactly(201, 409);
			assertThat(userRepository.findAll().stream().filter(u -> u.getEmail().equals(email))).hasSize(1);
		} finally {
			pool.shutdownNow();
		}
	}
}
