package dev.kbrr.ticketing.event;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;

@Entity
@Table(name = "ticket_types")
public class TicketType {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Event event;

	private String name;
	private long priceMinor;
	private int capacity;
	private int held;
	private int sold;

	@Version
	private long version;

	protected TicketType() {
	}

	TicketType(Event event, String name, long priceMinor, int capacity) {
		this.event = event;
		this.name = name;
		this.priceMinor = priceMinor;
		this.capacity = capacity;
	}

	public UUID getId() { return id; }
	public Event getEvent() { return event; }
	public String getName() { return name; }
	public long getPriceMinor() { return priceMinor; }
	public int getCapacity() { return capacity; }
	public int getHeld() { return held; }
	public int getSold() { return sold; }
	public long getVersion() { return version; }
}
