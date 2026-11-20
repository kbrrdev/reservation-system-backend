package dev.kbrr.ticketing.booking;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases overdue holds (spec flow 4), the only place besides cancel that does. Each booking expires in its own
 * transaction (the call goes through the service proxy), so one failure doesn't roll back or stop the others.
 */
@Component
class ExpiryJob {

	private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);

	private final BookingRepository bookings;
	private final BookingService service;
	private final Clock clock;

	ExpiryJob(BookingRepository bookings, BookingService service, Clock clock) {
		this.bookings = bookings;
		this.service = service;
		this.clock = clock;
	}

	// shortcut: one batch of 100 per run; a bigger backlog drains over the next runs. Loop batches if that lags.
	@Scheduled(fixedDelayString = "${ticketing.booking.expiry-interval}")
	public void run() {
		for (UUID id : bookings.findExpiredPendingIds(clock.instant(), PageRequest.of(0, 100))) {
			try {
				service.expire(id);
			} catch (RuntimeException e) {
				log.error("Could not expire booking {}", id, e);
			}
		}
	}
}
