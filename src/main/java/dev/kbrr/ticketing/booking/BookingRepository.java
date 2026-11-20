package dev.kbrr.ticketing.booking;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

	Page<Booking> findByUserIdAndTypeOrderByCreatedAtDesc(UUID userId, BookingType type, Pageable pageable);
}
