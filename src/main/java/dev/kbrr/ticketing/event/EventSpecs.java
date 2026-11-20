package dev.kbrr.ticketing.event;

import java.time.Instant;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** One specification per public filter; each is a no-op (unrestricted) when its parameter is absent. */
final class EventSpecs {

	private EventSpecs() {
	}

	static Specification<Event> onSale() {
		return (r, q, cb) -> cb.equal(r.get("status"), EventStatus.ON_SALE);
	}

	static Specification<Event> country(String code) {
		return code == null ? Specification.unrestricted()
				: (r, q, cb) -> cb.equal(r.get("venue").get("countryCode"), code.strip().toUpperCase(Locale.ROOT));
	}

	static Specification<Event> city(String city) {
		return city == null ? Specification.unrestricted()
				: (r, q, cb) -> cb.equal(cb.lower(r.get("venue").get("city")), city.strip().toLowerCase(Locale.ROOT));
	}

	static Specification<Event> category(EventCategory category) {
		return category == null ? Specification.unrestricted() : (r, q, cb) -> cb.equal(r.get("category"), category);
	}

	static Specification<Event> startsFrom(Instant from) {
		return from == null ? Specification.unrestricted() : (r, q, cb) -> cb.greaterThanOrEqualTo(r.get("startsAt"), from);
	}

	static Specification<Event> startsBefore(Instant to) {
		return to == null ? Specification.unrestricted() : (r, q, cb) -> cb.lessThan(r.get("startsAt"), to);
	}
}
