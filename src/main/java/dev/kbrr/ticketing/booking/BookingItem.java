package dev.kbrr.ticketing.booking;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
class BookingItem {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Booking booking;

	private UUID ticketTypeId;
	private int quantity;
	private long unitPriceMinor; // price snapshot: later price edits don't change what this booking costs

	protected BookingItem() {
	}

	BookingItem(Booking booking, UUID ticketTypeId, int quantity, long unitPriceMinor) {
		this.booking = booking;
		this.ticketTypeId = ticketTypeId;
		this.quantity = quantity;
		this.unitPriceMinor = unitPriceMinor;
	}

	UUID getTicketTypeId() { return ticketTypeId; }
	int getQuantity() { return quantity; }
	long getUnitPriceMinor() { return unitPriceMinor; }
}
