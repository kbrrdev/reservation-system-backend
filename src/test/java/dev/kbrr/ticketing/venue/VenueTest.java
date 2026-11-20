package dev.kbrr.ticketing.venue;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.user.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class VenueTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;

	private ResultActions create(User u, String country, String tz) throws Exception {
		return mvc.perform(post("/api/organizer/venues").header("Authorization", fx.bearer(u))
				.contentType(APPLICATION_JSON)
				.content("""
						{"name":"Arena","address":"1 Main St","city":"Manila","countryCode":"%s","timeZone":"%s"}"""
						.formatted(country, tz)));
	}

	@Test
	void organizerCreatesVenue() throws Exception {
		User org = fx.organizer();
		String body = create(org, "ph", "Asia/Manila").andExpect(status().isCreated())
				.andExpect(jsonPath("$.countryCode").value("PH")).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");
		mvc.perform(get("/api/organizer/venues").header("Authorization", fx.bearer(org)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id)));
	}

	@Test
	void disabledCountry() throws Exception {
		create(fx.organizer(), "US", "America/New_York").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("countryCode"));
	}

	@Test
	void unknownCountry() throws Exception {
		create(fx.organizer(), "ZZ", "Asia/Manila").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("countryCode"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"+08:00", "Z", "GMT+8", "Mars/Base", "asia/manila"})
	void invalidTimeZone(String tz) throws Exception {
		create(fx.organizer(), "PH", tz).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("timeZone"));
	}

	@Test
	void customerCannotCreateVenue() throws Exception {
		create(fx.customer(), "PH", "Asia/Manila").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void otherOrganizersVenueIsNotFound() throws Exception {
		String body = create(fx.organizer(), "PH", "Asia/Manila").andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");
		mvc.perform(get("/api/organizer/venues/" + id).header("Authorization", fx.bearer(fx.organizer())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
