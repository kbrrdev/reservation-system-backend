package dev.kbrr.ticketing.venue;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface VenueRepository extends JpaRepository<Venue, UUID> {

	List<Venue> findByOwnerIdOrderByName(UUID ownerId);

	Optional<Venue> findByIdAndOwnerId(UUID id, UUID ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Venue> findForUpdateByIdAndOwnerId(UUID id, UUID ownerId);
}
