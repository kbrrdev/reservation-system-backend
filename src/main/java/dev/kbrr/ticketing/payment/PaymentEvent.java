package dev.kbrr.ticketing.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_events")
public class PaymentEvent {

	@Id
	private UUID id;

	private String provider;
	private String providerEventId;
	private String type;
	private boolean paid;
	private Long amountMinor;

	@JdbcTypeCode(SqlTypes.CHAR) // column is char(3); schema validation expects CHAR
	private String currency;

	private Instant receivedAt;
	private String payload;
	private UUID bookingId;
	private boolean refundHandled;

	protected PaymentEvent() {
	}

	public PaymentEvent(PaymentNotification n, UUID matchedBookingId, Instant receivedAt) {
		this.id = UUID.randomUUID();
		this.provider = n.provider();
		this.providerEventId = n.providerEventId();
		this.type = n.type();
		this.paid = n.paid();
		this.amountMinor = n.amountMinor();
		this.currency = n.currency();
		this.receivedAt = receivedAt;
		this.payload = n.payload();
		this.bookingId = matchedBookingId;
	}

	public UUID getId() { return id; }
	public String getProvider() { return provider; }
	public String getProviderEventId() { return providerEventId; }
	public String getType() { return type; }
	public boolean isPaid() { return paid; }
	public Long getAmountMinor() { return amountMinor; }
	public String getCurrency() { return currency; }
	public Instant getReceivedAt() { return receivedAt; }
	public String getPayload() { return payload; }
	public UUID getBookingId() { return bookingId; }
	public boolean isRefundHandled() { return refundHandled; }
}
