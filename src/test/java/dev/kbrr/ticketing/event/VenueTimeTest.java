package dev.kbrr.ticketing.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VenueTimeTest {

	@Test
	void convertsWithVenueZone() {
		assertThat(VenueTime.toInstant(LocalDateTime.parse("2026-12-31T20:00"), ZoneId.of("Asia/Manila"), "startsAt"))
				.isEqualTo(Instant.parse("2026-12-31T12:00:00Z"));
	}

	@Test
	void dstGapIsRejected() {
		assertThatThrownBy(() -> VenueTime.toInstant(LocalDateTime.parse("2027-03-14T02:30"),
				ZoneId.of("America/New_York"), "startsAt"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
					Map<?, ?> first = (Map<?, ?>) ((List<?>) e.properties().get("errors")).get(0);
					assertThat(first.get("field")).isEqualTo("startsAt");
				});
	}

	@Test
	void dstOverlapTakesEarlierOffset() {
		assertThat(VenueTime.toInstant(LocalDateTime.parse("2026-11-01T01:30"), ZoneId.of("America/New_York"), "startsAt"))
				.isEqualTo(Instant.parse("2026-11-01T05:30:00Z"));
	}
}
