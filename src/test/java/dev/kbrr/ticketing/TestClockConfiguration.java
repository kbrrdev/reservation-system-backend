package dev.kbrr.ticketing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
class TestClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock();
	}
}
