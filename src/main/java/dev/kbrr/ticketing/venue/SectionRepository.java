package dev.kbrr.ticketing.venue;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SectionRepository extends JpaRepository<Section, UUID> {

	List<Section> findByVenueIdOrderByName(UUID venueId);

	// JPQL string, so the venue package does not import the event package.
	@Query("select count(e) > 0 from Event e where e.venue.id = :venueId")
	boolean venueHasEvents(UUID venueId);
}
