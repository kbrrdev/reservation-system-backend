package dev.kbrr.ticketing.booking;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface TicketRepository extends JpaRepository<Ticket, UUID> {

	List<Ticket> findByBookingIdOrderById(UUID bookingId);
}
