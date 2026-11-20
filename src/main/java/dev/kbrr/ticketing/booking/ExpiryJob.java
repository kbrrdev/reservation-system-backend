package dev.kbrr.ticketing.booking;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
	private final int batchSize;

	@Autowired
	ExpiryJob(BookingRepository bookings, BookingService service, Clock clock, BookingProperties props) {
		this(bookings, service, clock, props.expiryBatchSize());
	}

	ExpiryJob(BookingRepository bookings, BookingService service, Clock clock, int batchSize) {
		this.bookings = bookings;
		this.service = service;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	/**
	 * A booking that expires, or that a payment already took, leaves the pending set; one that throws stays in front
	 * of the rest (same order), so the next fetch skips the {@code failed} ones. {@code now} is fixed for the run, so
	 * the set only shrinks and the loop ends.
	 */
	@Scheduled(fixedDelayString = "${ticketing.booking.expiry-interval}")
	public void run() {
		Instant now = clock.instant();
		int failed = 0;
		List<UUID> batch;
		do {
			batch = bookings.findExpiredPendingIds(now, failed, batchSize);
			for (UUID id : batch) {
				try {
					service.expire(id);
				} catch (RuntimeException e) {
					failed++;
					log.error("Could not expire booking {}", id, e);
				}
			}
		} while (batch.size() == batchSize);
	}
}
