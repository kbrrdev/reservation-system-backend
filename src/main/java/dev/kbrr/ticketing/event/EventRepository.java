package dev.kbrr.ticketing.event;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventRepository extends JpaRepository<Event, UUID>, JpaSpecificationExecutor<Event> {

	List<Event> findByOrganizerIdOrderByStartsAtDesc(UUID organizerId);

	Optional<Event> findByIdAndOrganizerId(UUID id, UUID organizerId);

	/** {@code FOR SHARE}: holds run alongside each other but wait for (and are blocked by) an event cancel. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	Optional<Event> findForShareById(UUID id);

	@Query("select e.seatingMode from Event e where e.id = :eventId")
	Optional<SeatingMode> findSeatingModeById(UUID eventId);
}
