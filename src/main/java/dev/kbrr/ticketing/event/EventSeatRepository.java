package dev.kbrr.ticketing.event;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

	/** One AVAILABLE row per seat of every section mapped for the event; returns the rows inserted. */
	@Modifying
	@Query(nativeQuery = true, value = """
			insert into event_seats (id, event_id, seat_id, ticket_type_id, status)
			select gen_random_uuid(), tts.event_id, s.id, tts.ticket_type_id, 'AVAILABLE'
			from ticket_type_sections tts join seats s on s.section_id = tts.section_id
			where tts.event_id = :eventId""")
	int generate(UUID eventId);

	long countByEventIdAndStatus(UUID eventId, EventSeatStatus status);

	// shortcut: returns every seat at once; split per section before using 10k+ seat layouts.
	@Query("""
			select new dev.kbrr.ticketing.event.SeatMapRow(es.id, sec.id, sec.name, s.rowLabel, s.rowIndex, s.seatNumber,
			es.status, es.ticketTypeId)
			from EventSeat es join Seat s on s.id = es.seatId join Section sec on sec.id = s.sectionId
			where es.eventId = :eventId order by sec.name, s.rowIndex, s.seatNumber""")
	List<SeatMapRow> findSeatMap(UUID eventId);
}
