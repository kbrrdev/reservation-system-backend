package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.common.ApiException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

final class VenueTime {

	private VenueTime() {
	}

	/** On a DST overlap the earlier offset wins (atZone default); a gap time does not exist, so reject it. */
	static Instant toInstant(LocalDateTime local, ZoneId zone, String field) {
		if (zone.getRules().getValidOffsets(local).isEmpty()) {
			throw ApiException.fieldError(field, "does not exist in " + zone + " (daylight-saving gap)");
		}
		return local.atZone(zone).toInstant();
	}

	static OffsetDateTime toVenueTime(Instant t, ZoneId zone) {
		return t.atZone(zone).toOffsetDateTime();
	}
}
