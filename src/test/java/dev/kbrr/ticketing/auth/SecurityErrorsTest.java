package dev.kbrr.ticketing.auth;

import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import dev.kbrr.ticketing.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class SecurityErrorsTest {

	@Autowired MockMvc mvc;
	@Autowired JwtEncoder encoder;
	@Autowired Clock clock;

	private String token(JwtEncoder enc, List<String> roles, Duration age, Duration ttl) {
		return token(enc, "ticketing", roles, age, ttl);
	}

	private String token(JwtEncoder enc, String issuer, List<String> roles, Duration age, Duration ttl) {
		Instant iat = clock.instant().minus(age);
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(issuer).subject("1")
				.claim("email", "a@b.c").claim("roles", roles).issuedAt(iat).expiresAt(iat.plus(ttl)).build();
		return enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private String valid(String... roles) {
		return token(encoder, List.of(roles), Duration.ZERO, Duration.ofHours(1));
	}

	@Test
	void noToken() throws Exception {
		mvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void garbageToken() throws Exception {
		mvc.perform(get("/api/me").header("Authorization", "Bearer not.a.jwt"))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void tokenSignedWithOtherSecret() throws Exception {
		var key = new SecretKeySpec("another-secret-another-secret-another!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		String t = token(new NimbusJwtEncoder(new ImmutableSecret<>(key)), List.of("CUSTOMER"), Duration.ZERO, Duration.ofHours(1));
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + t))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void expiredToken() throws Exception {
		String t = token(encoder, List.of("CUSTOMER"), Duration.ofHours(2), Duration.ofHours(1));
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + t))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void wrongIssuer() throws Exception {
		String t = token(encoder, "evil", List.of("CUSTOMER"), Duration.ZERO, Duration.ofHours(1));
		mvc.perform(get("/api/me").header("Authorization", "Bearer " + t))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void customerOnAdminPath() throws Exception {
		mvc.perform(get("/api/admin/anything").header("Authorization", "Bearer " + valid("CUSTOMER")))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void unknownPathWithValidToken() throws Exception {
		mvc.perform(get("/api/does-not-exist").header("Authorization", "Bearer " + valid("CUSTOMER")))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void publicPathsOpen() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON).content("{}"))
				.andExpect(status().is4xxClientError()).andExpect(status().is(not(401)));
	}
}
