package dev.kbrr.ticketing;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Test clock that stands still until advanced; tests only move it forward, by less than 1 hour. */
public class MutableClock extends Clock {

	// Microseconds match timestamptz precision, so instants survive a database round trip unchanged.
	private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

	public void advance(Duration d) {
		now = now.plus(d);
	}

	@Override
	public Instant instant() {
		return now;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}
}
