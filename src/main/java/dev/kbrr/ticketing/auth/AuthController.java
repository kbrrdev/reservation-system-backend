package dev.kbrr.ticketing.auth;

import dev.kbrr.ticketing.user.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
class AuthController {

	private final AuthService auth;

	AuthController(AuthService auth) {
		this.auth = auth;
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest req) {
		return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(auth.register(req)));
	}

	@PostMapping("/login")
	TokenResponse login(@Valid @RequestBody LoginRequest req) {
		return TokenResponse.from(auth.login(req));
	}
}
