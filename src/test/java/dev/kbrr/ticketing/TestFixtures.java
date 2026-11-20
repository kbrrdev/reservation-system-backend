package dev.kbrr.ticketing;

import dev.kbrr.ticketing.event.CreateEventRequest;
import dev.kbrr.ticketing.event.EventCategory;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.event.EventService;
import dev.kbrr.ticketing.event.TicketTypeRequest;
import dev.kbrr.ticketing.user.Role;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.user.UserRepository;
import dev.kbrr.ticketing.venue.SectionRequest;
import dev.kbrr.ticketing.venue.SectionResponse;
import dev.kbrr.ticketing.venue.SectionService;
import dev.kbrr.ticketing.venue.VenueRequest;
import dev.kbrr.ticketing.venue.VenueResponse;
import dev.kbrr.ticketing.venue.VenueService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/** Test data helpers; every call creates a unique user so tests can share one database. */
public class TestFixtures {

	private final UserRepository users;
	private final JwtEncoder encoder;
	private final Clock clock;
	private final VenueService venues;
	private final EventService events;
	private final SectionService sections;

	TestFixtures(UserRepository users, JwtEncoder encoder, Clock clock, VenueService venues, EventService events,
			SectionService sections) {
		this.users = users;
		this.encoder = encoder;
		this.clock = clock;
		this.venues = venues;
		this.events = events;
		this.sections = sections;
	}

	public User customer() {
		return save(Set.of(Role.CUSTOMER));
	}

	public User organizer() {
		return save(Set.of(Role.CUSTOMER, Role.ORGANIZER));
	}

	public String bearer(User u) {
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer("ticketing").subject(u.getId().toString())
				.claim("email", u.getEmail()).claim("roles", u.getRoles().stream().map(Role::name).toList())
				.issuedAt(clock.instant()).expiresAt(clock.instant().plus(Duration.ofHours(1))).build();
		return "Bearer " + encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	public VenueResponse venue(User organizer, String countryCode, String timeZone) {
		return venue(organizer, countryCode, timeZone, "Manila");
	}

	public VenueResponse venue(User organizer, String countryCode, String timeZone, String city) {
		return venues.create(organizer.getId(), new VenueRequest("Arena", "1 Main St", city, countryCode, timeZone));
	}

	public SectionResponse section(User organizer, VenueResponse venue, String name, List<String> rows,
			int seatsPerRow) {
		return sections.create(organizer.getId(), venue.id(), new SectionRequest(name, rows, seatsPerRow));
	}

	/** One "Regular" ticket type; sale started 1h ago, starts in 30 days, lasts 3h (venue-local, from the clock). */
	public EventResponse gaEvent(User organizer, VenueResponse venue, int capacity, long priceMinor) {
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(venue.timeZone()))
				.truncatedTo(ChronoUnit.MINUTES);
		return gaEvent(organizer, venue, capacity, priceMinor, 8, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	public EventResponse gaEvent(User organizer, VenueResponse venue, int capacity, long priceMinor, int maxTickets,
			LocalDateTime saleStartsAt, LocalDateTime startsAt, LocalDateTime endsAt) {
		return events.create(organizer.getId(), new CreateEventRequest(venue.id(), "Event " + UUID.randomUUID(), null,
				EventCategory.CONCERT, startsAt, endsAt, saleStartsAt, maxTickets,
				List.of(new TicketTypeRequest("Regular", priceMinor, capacity))));
	}

	private User save(Set<Role> roles) {
		return users.save(new User(UUID.randomUUID() + "@x.test", "{noop}unused", "Test", roles, clock.instant()));
	}
}
