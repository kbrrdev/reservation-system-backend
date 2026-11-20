package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.common.Money;
import dev.kbrr.ticketing.venue.Venue;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record EventResponse(UUID id, String title, String description, EventCategory category, EventStatus status,
		String currency, OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime saleStartsAt, String timeZone,
		UUID venueId, String venueName, String city, String countryCode, int maxTicketsPerCustomer,
		List<TicketTypeResponse> ticketTypes) {

	/** Call inside a transaction: venue and ticket types are lazy. */
	static EventResponse from(Event e) {
		Venue v = e.getVenue();
		ZoneId zone = ZoneId.of(v.getTimeZone());
		return new EventResponse(e.getId(), e.getTitle(), e.getDescription(), e.getCategory(), e.getStatus(),
				e.getCurrency(), VenueTime.toVenueTime(e.getStartsAt(), zone), VenueTime.toVenueTime(e.getEndsAt(), zone),
				VenueTime.toVenueTime(e.getSaleStartsAt(), zone), v.getTimeZone(), v.getId(), v.getName(), v.getCity(),
				v.getCountryCode(), e.getMaxTicketsPerCustomer(),
				e.getTicketTypes().stream().map(t -> new TicketTypeResponse(t.getId(), t.getName(),
						new Money(t.getPriceMinor(), e.getCurrency()), t.getCapacity())).toList());
	}
}
