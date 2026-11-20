package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.user.UserRepository;
import dev.kbrr.ticketing.venue.VenueRequest;
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

/** Local-dev sample event only. Runs after DevUserSeeder; skips if the organizer already owns a venue. */
@Component
@Profile("dev")
@Order(2)
public class DevEventSeeder implements ApplicationRunner {

	private static final ZoneId ZONE = ZoneId.of("Asia/Manila");

	private final UserRepository users;
	private final VenueService venues;
	private final EventService events;
	private final Clock clock;

	public DevEventSeeder(UserRepository users, VenueService venues, EventService events, Clock clock) {
		this.users = users;
		this.venues = venues;
		this.events = events;
		this.clock = clock;
	}

	@Override
	public void run(ApplicationArguments args) {
		var organizer = users.findByEmail("organizer@example.test").orElseThrow();
		if (!venues.list(organizer.getId()).isEmpty()) {
			return;
		}
		var venue = venues.create(organizer.getId(),
				new VenueRequest("Sample Arena", "1 Sample Street", "Manila", "PH", ZONE.getId()));
		// Venue-local wall-clock times; the sale opened a minute ago so the event is bookable immediately.
		LocalDateTime now = LocalDateTime.now(clock.withZone(ZONE)).truncatedTo(ChronoUnit.SECONDS);
		LocalDateTime start = now.plusDays(30);
		events.create(organizer.getId(), new CreateEventRequest(venue.id(), "Sample Concert",
				"Sample data for local development.", EventCategory.CONCERT, start, start.plusHours(3),
				now.minusMinutes(1), null,
				List.of(new TicketTypeRequest("VIP", 500000L, 50, null),
						new TicketTypeRequest("Regular", 150000L, 500, null)),
				null));
	}
}
