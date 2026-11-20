package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.user.UserRepository;
import dev.kbrr.ticketing.venue.SectionRequest;
import dev.kbrr.ticketing.venue.SectionService;
import dev.kbrr.ticketing.venue.VenueRequest;
import dev.kbrr.ticketing.venue.VenueResponse;
import dev.kbrr.ticketing.venue.VenueService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Local-dev sample event only. Runs after DevUserSeeder; each sample venue is skipped if the organizer already has one with that name. */
@Component
@Profile("dev")
@Order(2)
public class DevEventSeeder implements ApplicationRunner {

	private static final ZoneId ZONE = ZoneId.of("Asia/Manila");

	private final UserRepository users;
	private final VenueService venues;
	private final SectionService sections;
	private final EventService events;
	private final Clock clock;

	public DevEventSeeder(UserRepository users, VenueService venues, SectionService sections,
			EventService events, Clock clock) {
		this.users = users;
		this.venues = venues;
		this.sections = sections;
		this.events = events;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		var organizer = users.findByEmail("organizer@example.test").orElseThrow();
		var existing = venues.list(organizer.getId()).stream().map(VenueResponse::name).toList();
		// Venue-local wall-clock times; the sale opened a minute ago so events are bookable immediately.
		LocalDateTime now = LocalDateTime.now(clock.withZone(ZONE)).truncatedTo(ChronoUnit.SECONDS);
		LocalDateTime start = now.plusDays(30);
		if (!existing.contains("Sample Arena")) {
			var venue = venues.create(organizer.getId(),
					new VenueRequest("Sample Arena", "1 Sample Street", "Manila", "PH", ZONE.getId()));
			events.create(organizer.getId(), new CreateEventRequest(venue.id(), "Sample Concert",
					"Sample data for local development.", EventCategory.CONCERT, start, start.plusHours(3),
					now.minusMinutes(1), null,
					List.of(new TicketTypeRequest("VIP", 500000L, 50, null),
							new TicketTypeRequest("Regular", 150000L, 500, null)),
					null));
		}
		if (!existing.contains("Sample Theater")) {
			var venue = venues.create(organizer.getId(),
					new VenueRequest("Sample Theater", "2 Sample Avenue", "Makati", "PH", ZONE.getId()));
			var orchestra = sections.create(organizer.getId(), venue.id(),
					new SectionRequest("Orchestra", List.of("A", "B", "C", "D", "E"), 10));
			var balcony = sections.create(organizer.getId(), venue.id(),
					new SectionRequest("Balcony", List.of("F", "G", "H"), 12));
			events.create(organizer.getId(), new CreateEventRequest(venue.id(), "Sample Play",
					"Sample reserved-seating data for local development.", EventCategory.THEATER, start,
					start.plusHours(3), now.minusMinutes(1), null,
					List.of(new TicketTypeRequest("Orchestra", 250000L, null, List.of(orchestra.id())),
							new TicketTypeRequest("Balcony", 120000L, null, List.of(balcony.id()))),
					SeatingMode.RESERVED));
		}
	}
}
