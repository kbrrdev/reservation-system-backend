package dev.kbrr.ticketing.booking;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface BookingRepository extends JpaRepository<Booking, UUID> {

	/** Status changes only from the expected state; the caller that gets 1 row back owns the transition. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Booking b set b.status = :next, b.version = b.version + 1
			where b.id = :id and b.status = :expected""")
	int updateStatus(UUID id, BookingStatus expected, BookingStatus next);

	/** Tickets counting towards the per-customer limit: pending and confirmed purchases. */
	@Query("""
			select coalesce(sum(i.quantity), 0L) from BookingItem i
			where i.booking.userId = :userId and i.booking.eventId = :eventId
			and i.booking.type = dev.kbrr.ticketing.booking.BookingType.PURCHASE
			and i.booking.status in (dev.kbrr.ticketing.booking.BookingStatus.PENDING_PAYMENT,
			                         dev.kbrr.ticketing.booking.BookingStatus.CONFIRMED)""")
	long countActiveTickets(UUID userId, UUID eventId);

	@Query("""
			select b from Booking b where b.userId = :userId and b.eventId = :eventId
			and b.type = dev.kbrr.ticketing.booking.BookingType.PURCHASE
			and b.status = dev.kbrr.ticketing.booking.BookingStatus.PENDING_PAYMENT""")
	Optional<Booking> findPendingPurchase(UUID userId, UUID eventId);

	/** Locks the booking so its status can't change until commit (lock order: after user and event). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Booking> findForUpdateById(UUID id);

	/** Ownership check that doesn't load the entity, so the locked read that follows returns fresh state. */
	@Query("select b.eventId from Booking b where b.id = :id and b.userId = :userId")
	Optional<UUID> findEventIdOwnedBy(UUID id, UUID userId);

	@Query("""
			select b.id from Booking b
			where b.status = dev.kbrr.ticketing.booking.BookingStatus.PENDING_PAYMENT and b.expiresAt < :now
			order by b.expiresAt""")
	List<UUID> findExpiredPendingIds(Instant now, Pageable limit);

	Page<Booking> findByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, BookingType type, Pageable pageable);
}
