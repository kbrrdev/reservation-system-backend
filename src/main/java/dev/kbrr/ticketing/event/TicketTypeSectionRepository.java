package dev.kbrr.ticketing.event;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTypeSectionRepository extends JpaRepository<TicketTypeSection, UUID> {

	List<TicketTypeSection> findByEventId(UUID eventId);
}
