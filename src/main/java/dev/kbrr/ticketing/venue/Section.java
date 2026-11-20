package dev.kbrr.ticketing.venue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "sections")
public class Section {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID venueId;
	private String name;

	protected Section() {
	}

	Section(UUID venueId, String name) {
		this.venueId = venueId;
		this.name = name;
	}

	public UUID getId() { return id; }
	public UUID getVenueId() { return venueId; }
	public String getName() { return name; }
}
