package dev.kbrr.ticketing.event;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TicketTypeRepository extends JpaRepository<TicketType, UUID> {
}
