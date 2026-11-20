package dev.kbrr.ticketing.venue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface VenueRepository extends JpaRepository<Venue, UUID> {

	List<Venue> findByOwnerIdOrderByName(UUID ownerId);

	Optional<Venue> findByIdAndOwnerId(UUID id, UUID ownerId);
}
