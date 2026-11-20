package dev.kbrr.ticketing.event;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * GA counters only move through these conditional updates (never load-modify-save), so the row lock taken by
 * the UPDATE plus the re-checked WHERE clause make overselling impossible. Each returns the rows changed (0 or 1).
 */
public interface TicketTypeRepository extends JpaRepository<TicketType, UUID> {

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update TicketType t set t.held = t.held + :q, t.version = t.version + 1
			where t.id = :id and t.capacity - t.held - t.sold >= :q""")
	int hold(UUID id, int q);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update TicketType t set t.held = t.held - :q, t.sold = t.sold + :q, t.version = t.version + 1
			where t.id = :id and t.held >= :q""")
	int confirm(UUID id, int q);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update TicketType t set t.held = t.held - :q, t.version = t.version + 1
			where t.id = :id and t.held >= :q""")
	int release(UUID id, int q);
}
