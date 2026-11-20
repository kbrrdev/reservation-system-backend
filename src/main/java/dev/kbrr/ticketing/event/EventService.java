package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.booking.BookingProperties;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.common.Money;
import dev.kbrr.ticketing.country.CountryProperties;
import dev.kbrr.ticketing.country.CountryProperties.Country;
import dev.kbrr.ticketing.venue.Venue;
import dev.kbrr.ticketing.venue.VenueService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

	private final EventRepository events;
	private final VenueService venues;
	private final CountryProperties countries;
	private final BookingProperties booking;
	private final Clock clock;

	EventService(EventRepository events, VenueService venues, CountryProperties countries, BookingProperties booking,
			Clock clock) {
		this.events = events;
		this.venues = venues;
		this.countries = countries;
		this.booking = booking;
		this.clock = clock;
	}

	@Transactional
	public EventResponse create(UUID organizerId, CreateEventRequest req) {
		Venue venue = venues.requireOwned(organizerId, req.venueId());
		Country country = countries.enabled(venue.getCountryCode())
				.orElseThrow(() -> ApiException.fieldError("venueId", "country is not enabled"));
		int limit = resolveLimit(req.maxTicketsPerCustomer());

		Set<String> names = new HashSet<>();
		for (TicketTypeRequest t : req.ticketTypes()) {
			if (!names.add(t.name())) {
				throw ApiException.fieldError("ticketTypes", "ticket type names must be unique");
			}
		}
		for (int i = 0; i < req.ticketTypes().size(); i++) {
			if (req.ticketTypes().get(i).priceMinor() < country.minChargeMinor()) {
				throw ApiException.fieldError("ticketTypes[" + i + "].priceMinor",
						"must be at least " + country.minChargeMinor() + " (minor units)");
			}
		}

		ZoneId zone = ZoneId.of(venue.getTimeZone());
		Instant startsAt = VenueTime.toInstant(req.startsAt(), zone, "startsAt");
		Instant endsAt = VenueTime.toInstant(req.endsAt(), zone, "endsAt");
		Instant saleStartsAt = VenueTime.toInstant(req.saleStartsAt(), zone, "saleStartsAt");
		if (!saleStartsAt.isBefore(startsAt)) {
			throw ApiException.fieldError("saleStartsAt", "must be before startsAt");
		}
		if (!startsAt.isBefore(endsAt)) {
			throw ApiException.fieldError("endsAt", "must be after startsAt");
		}
		Instant now = clock.instant();
		if (!endsAt.isAfter(now)) {
			throw ApiException.fieldError("endsAt", "must be in the future");
		}

		// shortcut: published immediately; an approval workflow would start events as DRAFT.
		Event event = new Event(organizerId, venue, country.currency(), req.title(), req.description(), req.category(),
				startsAt, endsAt, saleStartsAt, limit, EventStatus.ON_SALE, now);
		req.ticketTypes().forEach(t -> event.addTicketType(t.name(), t.priceMinor(), t.capacity()));
		return EventResponse.from(events.save(event));
	}

	@Transactional
	EventResponse update(UUID organizerId, UUID id, UpdateEventRequest req) {
		Event event = requireOwned(organizerId, id);
		event.update(req.title(), req.description(), req.category(), resolveLimit(req.maxTicketsPerCustomer()));
		return EventResponse.from(event);
	}

	@Transactional(readOnly = true)
	EventResponse getOwned(UUID organizerId, UUID id) {
		return EventResponse.from(requireOwned(organizerId, id));
	}

	@Transactional(readOnly = true)
	List<EventResponse> listOwned(UUID organizerId) {
		return events.findByOrganizerIdOrderByStartsAtDesc(organizerId).stream().map(EventResponse::from).toList();
	}

	@Transactional(readOnly = true)
	Page<EventResponse> search(String country, String city, EventCategory category, Instant from, Instant to,
			PageRequest page) {
		Specification<Event> spec = Specification.allOf(EventSpecs.onSale(), EventSpecs.country(country),
				EventSpecs.city(city), EventSpecs.category(category), EventSpecs.startsFrom(from),
				EventSpecs.startsBefore(to));
		return events.findAll(spec, page).map(EventResponse::from);
	}

	@Transactional(readOnly = true)
	EventResponse getPublic(UUID id) {
		return EventResponse.from(requireOnSale(id));
	}

	@Transactional(readOnly = true)
	AvailabilityResponse availability(UUID id) {
		Event e = requireOnSale(id);
		return new AvailabilityResponse(e.getId(), e.getTicketTypes().stream().map(t -> new AvailabilityResponse.Item(
				t.getId(), t.getName(), new Money(t.getPriceMinor(), e.getCurrency()),
				t.getCapacity() - t.getHeld() - t.getSold())).toList());
	}

	private Event requireOnSale(UUID id) {
		return events.findById(id).filter(e -> e.getStatus() == EventStatus.ON_SALE)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found"));
	}

	private Event requireOwned(UUID organizerId, UUID id) {
		return events.findByIdAndOrganizerId(id, organizerId)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found"));
	}

	private int resolveLimit(Integer requested) {
		if (requested == null) {
			return booking.defaultMaxTicketsPerCustomer();
		}
		if (requested < 1 || requested > booking.platformMaxTicketsPerCustomer()) {
			throw ApiException.fieldError("maxTicketsPerCustomer",
					"must be between 1 and " + booking.platformMaxTicketsPerCustomer());
		}
		return requested;
	}
}
