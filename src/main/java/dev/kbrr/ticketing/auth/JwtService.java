package dev.kbrr.ticketing.auth;

import dev.kbrr.ticketing.user.Role;
import dev.kbrr.ticketing.user.User;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
class JwtService {

	private final JwtEncoder encoder;
	private final JwtProperties props;
	private final Clock clock;

	JwtService(JwtEncoder encoder, JwtProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	IssuedToken issue(User user) {
		Instant now = clock.instant();
		Instant expiresAt = now.plus(props.ttl());
		List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ticketing").subject(user.getId().toString())
				.issuedAt(now).expiresAt(expiresAt).claim("email", user.getEmail()).claim("roles", roles).build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new IssuedToken(token, expiresAt);
	}
}
