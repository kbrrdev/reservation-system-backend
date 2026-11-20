package dev.kbrr.ticketing.payment;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

	boolean existsByProviderAndProviderEventId(String provider, String providerEventId);

	@Query("""
			select e from PaymentEvent e
			where e.paid = true and e.bookingId is null and e.refundHandled = false
			order by e.receivedAt desc""")
	Page<PaymentEvent> findUnmatched(Pageable pageable);

	@Modifying
	@Query("""
			update PaymentEvent e set e.refundHandled = true
			where e.id = :id and e.refundHandled = false and e.paid = true and e.bookingId is null""")
	int markRefundHandled(@Param("id") UUID id);
}
