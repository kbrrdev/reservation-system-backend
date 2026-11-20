package dev.kbrr.ticketing.venue;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@IntegrationTest
class SectionTest {

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired SeatRepository seats;

	private ResultActions addSection(User u, UUID venueId, String name, String rowsJson, int seatsPerRow) throws Exception {
		return mvc.perform(post("/api/organizer/venues/" + venueId + "/sections")
				.header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content("""
						{"name":"%s","rowLabels":%s,"seatsPerRow":%d}""".formatted(name, rowsJson, seatsPerRow)));
	}

	private static String rows(int n) {
		return IntStream.range(0, n).mapToObj(i -> "\"R" + i + "\"").collect(Collectors.joining(",", "[", "]"));
	}

	@Test
	void createsSectionWithSeats() throws Exception {
		User org = fx.organizer();
		UUID venueId = fx.venue(org, "PH", "Asia/Manila").id();
		String body = addSection(org, venueId, "Orchestra", "[\"a\",\"B\"]", 10).andExpect(status().isCreated())
				.andExpect(jsonPath("$.rowLabels[0]").value("A")).andExpect(jsonPath("$.rowLabels[1]").value("B"))
				.andExpect(jsonPath("$.seatsPerRow").value(10)).andExpect(jsonPath("$.seatCount").value(20))
				.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		mvc.perform(get("/api/organizer/venues/" + venueId + "/sections").header("Authorization", fx.bearer(org)))
				.andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(id.toString())))
				.andExpect(jsonPath("$[0].rowLabels[1]").value("B")).andExpect(jsonPath("$[0].seatsPerRow").value(10))
				.andExpect(jsonPath("$[0].seatCount").value(20));
		assertThat(seats.countBySectionIdIn(List.of(id))).isEqualTo(20);
	}

	@Test
	void duplicateRowLabelsAfterUpperCasing() throws Exception {
		User org = fx.organizer();
		addSection(org, fx.venue(org, "PH", "Asia/Manila").id(), "S", "[\"A\",\"a\"]", 5).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("rowLabels"));
	}

	@Test
	void duplicateSectionName() throws Exception {
		User org = fx.organizer();
		UUID venueId = fx.venue(org, "PH", "Asia/Manila").id();
		addSection(org, venueId, "S", "[\"A\"]", 5).andExpect(status().isCreated());
		addSection(org, venueId, "S", "[\"A\"]", 5).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("name"));
	}

	@Test
	void sectionTooLarge() throws Exception {
		User org = fx.organizer();
		addSection(org, fx.venue(org, "PH", "Asia/Manila").id(), "S", rows(100), 51).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("seatsPerRow"));
	}

	@Test
	void venueTooLarge() throws Exception {
		User org = fx.organizer();
		UUID venueId = fx.venue(org, "PH", "Asia/Manila").id();
		for (int i = 0; i < 4; i++) {
			addSection(org, venueId, "S" + i, rows(25), 200).andExpect(status().isCreated());
		}
		addSection(org, venueId, "Extra", "[\"A\"]", 1).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("seatsPerRow"));
	}

	@Test
	void invalidRowLabel() throws Exception {
		User org = fx.organizer();
		addSection(org, fx.venue(org, "PH", "Asia/Manila").id(), "S", "[\"A-1\"]", 5).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void venueInUse() throws Exception {
		User org = fx.organizer();
		var venue = fx.venue(org, "PH", "Asia/Manila");
		fx.gaEvent(org, venue, 10, 5000);
		addSection(org, venue.id(), "S", "[\"A\"]", 5).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("VENUE_IN_USE"));
	}

	@Test
	void otherOrganizersVenue() throws Exception {
		UUID venueId = fx.venue(fx.organizer(), "PH", "Asia/Manila").id();
		addSection(fx.organizer(), venueId, "S", "[\"A\"]", 5).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}
}
