package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.booking.BookingProperties;
import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.common.Money;
import dev.kbrr.ticketing.country.CountryProperties;
import dev.kbrr.ticketing.country.CountryProperties.Country;
import dev.kbrr.ticketing.venue.SeatRepository;
import dev.kbrr.ticketing.venue.Section;
import dev.kbrr.ticketing.venue.SectionRepository;
import dev.kbrr.ticketing.venue.Venue;
import dev.kbrr.ticketing.venue.VenueService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

	private final EventRepository events;
	private final TicketTypeSectionRepository ticketTypeSections;
	private final EventSeatRepository eventSeats;
	private final SectionRepository sections;
	private final SeatRepository seats;
	private final VenueService venues;
	private final CountryProperties countries;
	private final BookingProperties booking;
	private final Clock clock;

	EventService(EventRepository events, TicketTypeSectionRepository ticketTypeSections, EventSeatRepository eventSeats,
			SectionRepository sections, SeatRepository seats, VenueService venues, CountryProperties countries,
			BookingProperties booking, Clock clock) {
		this.events = events;
		this.ticketTypeSections = ticketTypeSections;
		this.eventSeats = eventSeats;
		this.sections = sections;
		this.seats = seats;
		this.venues = venues;
		this.countries = countries;
		this.booking = booking;
		this.clock = clock;
	}

	@Transactional
	public EventResponse create(UUID organizerId, CreateEventRequest req) {
		Venue venue = // Row lock on the venue serialises event creation against section edits, so the seat set cannot change mid-create.
		venues.requireOwnedForUpdate(organizerId, req.venueId());
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

		SeatingMode mode = req.seatingMode() == null ? SeatingMode.GA : req.seatingMode();
		Map<String, Integer> capacities = mode == SeatingMode.RESERVED ? validateReserved(venue, req)
				: validateGa(req);

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
				mode, startsAt, endsAt, saleStartsAt, limit, EventStatus.ON_SALE, now);
		req.ticketTypes().forEach(t -> event.addTicketType(t.name(), t.priceMinor(), capacities.get(t.name())));
		events.saveAndFlush(event); // ticket type ids exist from here
		if (mode == SeatingMode.GA) {
			return respond(event);
		}
		Map<String, UUID> typeIds = event.getTicketTypes().stream().collect(Collectors.toMap(TicketType::getName,
				TicketType::getId));
		ticketTypeSections.saveAll(req.ticketTypes().stream().flatMap(t -> t.sectionIds().stream()
				.map(s -> new TicketTypeSection(event.getId(), typeIds.get(t.name()), s))).toList());
		ticketTypeSections.flush();
		int generated = eventSeats.generate(event.getId());
		if (generated != seats.countByVenueId(venue.getId())) {
			throw new IllegalStateException("generated " + generated + " event seats, venue has a different seat count");
		}
		return respond(event);
	}

	/** Capacity is required and sections are not allowed; returns capacity by ticket type name. */
	private Map<String, Integer> validateGa(CreateEventRequest req) {
		Map<String, Integer> capacities = new HashMap<>();
		for (int i = 0; i < req.ticketTypes().size(); i++) {
			TicketTypeRequest t = req.ticketTypes().get(i);
			if (t.capacity() == null) {
				throw ApiException.fieldError("ticketTypes[" + i + "].capacity", "required for general admission");
			}
			if (t.sectionIds() != null && !t.sectionIds().isEmpty()) {
				throw ApiException.fieldError("ticketTypes[" + i + "].sectionIds", "only for reserved seating");
			}
			capacities.put(t.name(), t.capacity());
		}
		return capacities;
	}

	/** Every venue section maps to exactly one ticket type; returns capacity (seat count) by ticket type name. */
	private Map<String, Integer> validateReserved(Venue venue, CreateEventRequest req) {
		Set<UUID> venueSections = sections.findByVenueIdOrderByName(venue.getId()).stream().map(Section::getId)
				.collect(Collectors.toSet());
		if (venueSections.isEmpty()) {
			throw ApiException.fieldError("venueId", "venue has no sections");
		}
		Set<UUID> mapped = new HashSet<>();
		boolean duplicate = false;
		Map<String, Integer> capacities = new HashMap<>();
		for (int i = 0; i < req.ticketTypes().size(); i++) {
			TicketTypeRequest t = req.ticketTypes().get(i);
			if (t.capacity() != null) {
				throw ApiException.fieldError("ticketTypes[" + i + "].capacity", "must be omitted for reserved seating");
			}
			if (t.sectionIds() == null || t.sectionIds().isEmpty()) {
				throw ApiException.fieldError("ticketTypes[" + i + "].sectionIds", "required for reserved seating");
			}
			if (!venueSections.containsAll(t.sectionIds())) {
				throw ApiException.fieldError("ticketTypes[" + i + "].sectionIds", "unknown section");
			}
			for (UUID s : t.sectionIds()) {
				duplicate |= !mapped.add(s);
			}
			capacities.put(t.name(), (int) seats.countBySectionIdIn(t.sectionIds()));
		}
		if (duplicate) {
			throw ApiException.fieldError("ticketTypes", "each section belongs to exactly one ticket type");
		}
		if (!mapped.equals(venueSections)) {
			throw ApiException.fieldError("ticketTypes", "every section must be mapped to a ticket type");
		}
		return capacities;
	}

	/** Maps to a response; reserved events need their section mapping (one extra query). Call inside a transaction. */
	private EventResponse respond(Event e) {
		Map<UUID, List<UUID>> byType = e.getSeatingMode() == SeatingMode.RESERVED
				? ticketTypeSections.findByEventId(e.getId()).stream().collect(Collectors.groupingBy(
						TicketTypeSection::getTicketTypeId, Collectors.mapping(TicketTypeSection::getSectionId,
								Collectors.toList())))
				: Map.of();
		return EventResponse.from(e, byType);
	}

	@Transactional
	EventResponse update(UUID organizerId, UUID id, UpdateEventRequest req) {
		Event event = requireOwned(organizerId, id);
		event.update(req.title(), req.description(), req.category(), resolveLimit(req.maxTicketsPerCustomer()));
		return respond(event);
	}

	@Transactional(readOnly = true)
	EventResponse getOwned(UUID organizerId, UUID id) {
		return respond(requireOwned(organizerId, id));
	}

	@Transactional(readOnly = true)
	List<EventResponse> listOwned(UUID organizerId) {
		return events.findByOrganizerIdOrderByStartsAtDesc(organizerId).stream().map(this::respond).toList();
	}

	@Transactional(readOnly = true)
	Page<EventResponse> search(String country, String city, EventCategory category, Instant from, Instant to,
			PageRequest page) {
		Specification<Event> spec = Specification.allOf(EventSpecs.onSale(), EventSpecs.country(country),
				EventSpecs.city(city), EventSpecs.category(category), EventSpecs.startsFrom(from),
				EventSpecs.startsBefore(to));
		return events.findAll(spec, page).map(this::respond);
	}

	@Transactional(readOnly = true)
	EventResponse getPublic(UUID id) {
		return respond(requireOnSale(id));
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
