package dev.kbrr.ticketing.country;

import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CountryController {

	private final CountryProperties countries;

	CountryController(CountryProperties countries) {
		this.countries = countries;
	}

	@GetMapping("/api/countries")
	List<CountryResponse> list() {
		return countries.countries().entrySet().stream()
				.filter(e -> e.getValue().enabled())
				.sorted(Map.Entry.comparingByKey())
				.map(e -> new CountryResponse(e.getKey(), e.getValue().currency(),
						Currency.getInstance(e.getValue().currency()).getDefaultFractionDigits(),
						e.getValue().minChargeMinor()))
				.toList();
	}
}
