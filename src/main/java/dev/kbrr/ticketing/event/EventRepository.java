package dev.kbrr.ticketing.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	List<Event> findByOrganizerIdOrderByStartsAtDesc(UUID organizerId);

	Optional<Event> findByIdAndOrganizerId(UUID id, UUID organizerId);
}
