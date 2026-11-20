package dev.kbrr.ticketing.venue;

import java.util.UUID;

record VenueResponse(UUID id, String name, String address, String city, String countryCode, String timeZone) {

	static VenueResponse from(Venue v) {
		return new VenueResponse(v.getId(), v.getName(), v.getAddress(), v.getCity(), v.getCountryCode(),
				v.getTimeZone());
	}
}
