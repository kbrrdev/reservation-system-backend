package dev.kbrr.ticketing.booking;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bookings")
class Booking {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID userId;
	private UUID eventId;

	@Enumerated(EnumType.STRING)
	private BookingType type;

	@Enumerated(EnumType.STRING)
	private BookingStatus status;

	private long totalMinor;

	@JdbcTypeCode(SqlTypes.CHAR) // column is char(3); schema validation expects CHAR
	private String currency;

	private Instant expiresAt;
	private String paymentProvider;
	private Instant createdAt;
	private Instant cancelledAt;
	private UUID cancelledBy;

	@Version
	private long version;

	@OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
	private List<BookingItem> items = new ArrayList<>();

	protected Booking() {
	}

	Booking(UUID userId, UUID eventId, BookingType type, String currency, Instant expiresAt, String paymentProvider,
			Instant createdAt) {
		this.userId = userId;
		this.eventId = eventId;
		this.type = type;
		this.status = BookingStatus.PENDING_PAYMENT;
		this.currency = currency;
		this.expiresAt = expiresAt;
		this.paymentProvider = paymentProvider;
		this.createdAt = createdAt;
	}

	/** Exact arithmetic: an overflow fails loudly instead of wrapping to a wrong (even negative) total. */
	void addItem(UUID ticketTypeId, int quantity, long unitPriceMinor) {
		items.add(new BookingItem(this, ticketTypeId, quantity, unitPriceMinor));
		totalMinor = Math.addExact(totalMinor, Math.multiplyExact(unitPriceMinor, quantity));
	}

	UUID getId() { return id; }
	UUID getUserId() { return userId; }
	UUID getEventId() { return eventId; }
	BookingType getType() { return type; }
	BookingStatus getStatus() { return status; }
	long getTotalMinor() { return totalMinor; }
	String getCurrency() { return currency; }
	Instant getExpiresAt() { return expiresAt; }
	String getPaymentProvider() { return paymentProvider; }
	Instant getCreatedAt() { return createdAt; }
	Instant getCancelledAt() { return cancelledAt; }
	UUID getCancelledBy() { return cancelledBy; }
	long getVersion() { return version; }
	List<BookingItem> getItems() { return items; }
}
