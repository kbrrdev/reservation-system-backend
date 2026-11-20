package dev.kbrr.ticketing.venue;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

	long countBySectionIdIn(Collection<UUID> sectionIds);

	@Query("select count(s) from Seat s join Section sec on s.sectionId = sec.id where sec.venueId = :venueId")
	long countByVenueId(UUID venueId);

	/** One row per (section, row): [sectionId, rowLabel, seat count], rows in layout order. */
	@Query("""
			select s.sectionId, s.rowLabel, count(s) from Seat s where s.sectionId in :sectionIds
			group by s.sectionId, s.rowLabel, s.rowIndex order by s.rowIndex""")
	List<Object[]> rowsOf(Collection<UUID> sectionIds);
}
