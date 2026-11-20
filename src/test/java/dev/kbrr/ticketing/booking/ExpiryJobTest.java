package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import org.junit.jupiter.api.Test;

class ExpiryJobTest {

	@Test
	void nonPositiveBatchSizeRejected() {
		for (int size : new int[] {0, -1}) {
			assertThatThrownBy(() -> new ExpiryJob(null, null, Clock.systemUTC(), size))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("ticketing.booking.expiry-batch-size must be positive");
		}
	}
}
