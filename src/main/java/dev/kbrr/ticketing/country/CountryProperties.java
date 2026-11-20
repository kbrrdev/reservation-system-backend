package dev.kbrr.ticketing.country;

import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ticketing")
public record CountryProperties(Map<String, Country> countries) {

	public CountryProperties {
		countries = countries == null ? Map.of() : Map.copyOf(countries);
	}

	public record Country(boolean enabled, String currency, String paymentProvider, long minChargeMinor) {

		public Country {
			Currency.getInstance(currency); // throws IllegalArgumentException on a bad code, failing startup
			if (minChargeMinor < 1) {
				throw new IllegalArgumentException("minChargeMinor must be at least 1");
			}
		}
	}

	/** Present only if configured and enabled; gates new venues/events. */
	public Optional<Country> enabled(String code) {
		return Optional.ofNullable(countries.get(code)).filter(Country::enabled);
	}

	/** Any configured country, enabled or not (existing venues keep working). */
	public Country get(String code) {
		Country c = countries.get(code);
		if (c == null) {
			throw new IllegalStateException("Unknown country " + code);
		}
		return c;
	}
}
