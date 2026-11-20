package dev.kbrr.ticketing.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	/** Serializes one customer's bookings: concurrent holds by the same user run one at a time. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<User> findForUpdateById(UUID id);
}
