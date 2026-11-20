package dev.kbrr.ticketing.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank @Email @Size(max = 254) String email,
		@NotBlank @Size(min = 8) String password,
		@NotBlank @Size(max = 100) String name,
		boolean organizer) {

	// @Email rejects surrounding whitespace; trim first so "  a@b.c " is accepted and normalized.
	public RegisterRequest {
		email = email == null ? null : email.strip();
	}
}
