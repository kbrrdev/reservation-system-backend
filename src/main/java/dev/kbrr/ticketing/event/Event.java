package dev.kbrr.ticketing.event;

import dev.kbrr.ticketing.venue.Venue;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "events")
public class Event {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID organizerId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	private Venue venue;

	@JdbcTypeCode(SqlTypes.CHAR) // column is char(3); schema validation expects CHAR
	private String currency;

	private String title;
	private String description;

	@Enumerated(EnumType.STRING)
	private EventCategory category;

	private Instant startsAt;
	private Instant endsAt;
	private Instant saleStartsAt;
	private String seatingMode;
	private int maxTicketsPerCustomer;

	@Enumerated(EnumType.STRING)
	private EventStatus status;

	private Instant createdAt;

	@Version
	private long version;

	@OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("name")
	private List<TicketType> ticketTypes = new ArrayList<>();

	protected Event() {
	}

	Event(UUID organizerId, Venue venue, String currency, String title, String description, EventCategory category,
			Instant startsAt, Instant endsAt, Instant saleStartsAt, int maxTicketsPerCustomer, EventStatus status,
			Instant createdAt) {
		this.organizerId = organizerId;
		this.venue = venue;
		this.currency = currency;
		this.title = title;
		this.description = description;
		this.category = category;
		this.startsAt = startsAt;
		this.endsAt = endsAt;
		this.saleStartsAt = saleStartsAt;
		this.seatingMode = "GA";
		this.maxTicketsPerCustomer = maxTicketsPerCustomer;
		this.status = status;
		this.createdAt = createdAt;
	}

	void addTicketType(String name, long priceMinor, int capacity) {
		ticketTypes.add(new TicketType(this, name, priceMinor, capacity));
	}

	void update(String title, String description, EventCategory category, int maxTicketsPerCustomer) {
		this.title = title;
		this.description = description;
		this.category = category;
		this.maxTicketsPerCustomer = maxTicketsPerCustomer;
	}

	public UUID getId() { return id; }
	public UUID getOrganizerId() { return organizerId; }
	public Venue getVenue() { return venue; }
	public String getCurrency() { return currency; }
	public String getTitle() { return title; }
	public String getDescription() { return description; }
	public EventCategory getCategory() { return category; }
	public Instant getStartsAt() { return startsAt; }
	public Instant getEndsAt() { return endsAt; }
	public Instant getSaleStartsAt() { return saleStartsAt; }
	public String getSeatingMode() { return seatingMode; }
	public int getMaxTicketsPerCustomer() { return maxTicketsPerCustomer; }
	public EventStatus getStatus() { return status; }
	public Instant getCreatedAt() { return createdAt; }
	public long getVersion() { return version; }
	public List<TicketType> getTicketTypes() { return ticketTypes; }
}
