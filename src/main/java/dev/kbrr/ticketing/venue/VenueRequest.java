package dev.kbrr.ticketing.venue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

public record VenueRequest(
		@NotBlank @Size(max = 200) String name,
		@NotBlank @Size(max = 300) String address,
		@NotBlank @Size(max = 100) String city,
		@NotBlank @Pattern(regexp = "[A-Za-z]{2}") String countryCode,
		@NotBlank String timeZone) {

	public VenueRequest {
		countryCode = countryCode == null ? null : countryCode.toUpperCase(Locale.ROOT);
	}
}
