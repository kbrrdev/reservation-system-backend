package dev.kbrr.ticketing.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class LoginTest {

	@Autowired MockMvc mvc;
	@Autowired JwtDecoder decoder;
	@Autowired Clock clock;

	private String register(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password1\",\"name\":\"A\"}".formatted(email)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private ResultActions login(String email, String password) throws Exception {
		return mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	@Test
	void loginReturnsUsableToken() throws Exception {
		String email = UUID.randomUUID() + "@x.test";
		register(email);
		String body = login(email, "password1").andExpect(status().isOk())
				.andExpect(jsonPath("$.tokenType").value("Bearer")).andReturn().getResponse().getContentAsString();
		Instant expiresAt = Instant.parse(JsonPath.read(body, "$.expiresAt"));
		assertThat(expiresAt).isCloseTo(clock.instant().plus(Duration.ofHours(1)), within(1, ChronoUnit.MINUTES));
		String token = JsonPath.read(body, "$.accessToken");
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
	}

	@Test
	void loginAnyEmailCase() throws Exception {
		String email = UUID.randomUUID() + "@x.test";
		register(email);
		login("  " + email.toUpperCase() + " ", "password1").andExpect(status().isOk());
	}

	@Test
	void wrongPasswordAndUnknownEmailLookTheSame() throws Exception {
		String email = UUID.randomUUID() + "@x.test";
		register(email);
		String wrong = login(email, "nope-nope").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
		String unknown = login(UUID.randomUUID() + "@x.test", "password1").andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
		assertThat((String) JsonPath.read(wrong, "$.detail")).isEqualTo(JsonPath.read(unknown, "$.detail"));
	}

	@Test
	void tokenCarriesRoles() throws Exception {
		String email = UUID.randomUUID() + "@x.test";
		String id = register(email);
		String body = login(email, "password1").andReturn().getResponse().getContentAsString();
		Jwt jwt = decoder.decode(JsonPath.read(body, "$.accessToken"));
		assertThat(jwt.getClaimAsStringList("roles")).isEqualTo(List.of("CUSTOMER"));
		assertThat(jwt.getClaimAsString("email")).isEqualTo(email);
		assertThat(jwt.getSubject()).isEqualTo(id);
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("ticketing");
	}
}
