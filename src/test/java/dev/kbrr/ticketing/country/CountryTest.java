package dev.kbrr.ticketing.country;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.kbrr.ticketing.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class CountryTest {

	@Autowired MockMvc mvc;
	@Autowired CountryProperties countries;

	@Test
	void listsEnabledCountriesWithoutToken() throws Exception {
		mvc.perform(get("/api/countries")).andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].code").value("JP"))
				.andExpect(jsonPath("$[0].currency").value("JPY"))
				.andExpect(jsonPath("$[0].currencyFractionDigits").value(0))
				.andExpect(jsonPath("$[1].code").value("PH"))
				.andExpect(jsonPath("$[1].currency").value("PHP"))
				.andExpect(jsonPath("$[1].currencyFractionDigits").value(2))
				.andExpect(jsonPath("$[1].minChargeMinor").value(2000));
	}

	@Test
	void disabledCountryIsKnownButNotEnabled() {
		assertThat(countries.enabled("US")).isEmpty();
		assertThat(countries.get("US").currency()).isEqualTo("USD");
	}

	@Test
	void invalidCurrencyFailsFast() {
		assertThatThrownBy(() -> new CountryProperties.Country(true, "XXY", "x", 1))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
