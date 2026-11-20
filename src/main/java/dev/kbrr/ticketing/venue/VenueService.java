package dev.kbrr.ticketing.venue;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.country.CountryProperties;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VenueService {

	private final VenueRepository venues;
	private final CountryProperties countries;

	VenueService(VenueRepository venues, CountryProperties countries) {
		this.venues = venues;
		this.countries = countries;
	}

	public VenueResponse create(UUID ownerId, VenueRequest req) {
		if (countries.enabled(req.countryCode()).isEmpty()) {
			throw ApiException.fieldError("countryCode", "country is not enabled");
		}
		// Case-sensitive on purpose: "asia/manila" is rejected, and offsets like "+08:00" are not region ids.
		if (!ZoneId.getAvailableZoneIds().contains(req.timeZone())) {
			throw ApiException.fieldError("timeZone", "must be an IANA region id like Asia/Manila");
		}
		return VenueResponse.from(venues.save(new Venue(ownerId, req.name(), req.address(), req.city(),
				req.countryCode(), req.timeZone())));
	}

	public List<VenueResponse> list(UUID ownerId) {
		return venues.findByOwnerIdOrderByName(ownerId).stream().map(VenueResponse::from).toList();
	}

	VenueResponse get(UUID ownerId, UUID id) {
		return VenueResponse.from(requireOwned(ownerId, id));
	}

	/** Another owner's venue looks the same as a missing one, so ids cannot be probed. */
	public Venue requireOwned(UUID ownerId, UUID id) {
		return venues.findByIdAndOwnerId(id, ownerId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Venue not found"));
	}

	/** Takes the venue row lock; call inside a transaction. */
	public Venue requireOwnedForUpdate(UUID ownerId, UUID id) {
		return venues.findForUpdateByIdAndOwnerId(id, ownerId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Venue not found"));
	}
}
