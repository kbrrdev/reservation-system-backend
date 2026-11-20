package dev.kbrr.ticketing.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class MeTest {

	@Autowired MockMvc mvc;
	@Autowired JwtEncoder encoder;
	@Autowired JwtDecoder decoder;
	@Autowired Clock clock;

	private String registerAndLogin(String email) throws Exception {
		mvc.perform(post("/api/auth/register").contentType(APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password1\",\"name\":\"A\",\"organizer\":false}".formatted(email)))
				.andExpect(status().isCreated());
		return login(email);
	}

	private String login(String email) throws Exception {
		String body = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"password1\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.accessToken");
	}

	@Test
	void becomeOrganizer() throws Exception {
		String email = UUID.randomUUID() + "@x.test";
		String token = registerAndLogin(email);
		mvc.perform(post("/api/me/organizer").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andExpect(jsonPath("$.roles", containsInAnyOrder("CUSTOMER", "ORGANIZER")));
		Jwt fresh = decoder.decode(login(email));
		assertThat(fresh.getClaimAsStringList("roles")).containsExactlyInAnyOrder("CUSTOMER", "ORGANIZER");
	}

	@Test
	void becomeOrganizerTwiceIsIdempotent() throws Exception {
		String token = registerAndLogin(UUID.randomUUID() + "@x.test");
		for (int i = 0; i < 2; i++) {
			mvc.perform(post("/api/me/organizer").header("Authorization", "Bearer " + token))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.roles", containsInAnyOrder("CUSTOMER", "ORGANIZER")));
		}
	}

	@Test
	void meForDeletedUser() throws Exception {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ticketing").subject(UUID.randomUUID().toString())
				.claim("email", "gone@x.test").claim("roles", List.of("CUSTOMER")).issuedAt(clock.instant())
				.expiresAt(clock.instant().plus(Duration.ofHours(1))).build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
