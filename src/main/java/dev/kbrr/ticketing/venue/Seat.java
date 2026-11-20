package dev.kbrr.ticketing.venue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "seats")
public class Seat {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID sectionId;
	private String rowLabel;
	private int rowIndex; // 0-based position of the row in the request
	private int seatNumber; // 1-based

	protected Seat() {
	}

	Seat(UUID sectionId, String rowLabel, int rowIndex, int seatNumber) {
		this.sectionId = sectionId;
		this.rowLabel = rowLabel;
		this.rowIndex = rowIndex;
		this.seatNumber = seatNumber;
	}

	public UUID getId() { return id; }
	public UUID getSectionId() { return sectionId; }
	public String getRowLabel() { return rowLabel; }
	public int getRowIndex() { return rowIndex; }
	public int getSeatNumber() { return seatNumber; }
}
