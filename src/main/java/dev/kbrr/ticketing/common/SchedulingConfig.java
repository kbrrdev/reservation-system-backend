package dev.kbrr.ticketing.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** On by default; tests switch it off and run jobs by hand against the test clock. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "ticketing.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig {
}
