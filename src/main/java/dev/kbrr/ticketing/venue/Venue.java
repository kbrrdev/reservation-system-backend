package dev.kbrr.ticketing.venue;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "venues")
public class Venue {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID ownerId;
	private String name;
	private String address;
	private String city;

	@JdbcTypeCode(SqlTypes.CHAR) // column is char(2); schema validation expects CHAR
	private String countryCode;

	private String timeZone;

	@Version
	private long version;

	protected Venue() {
	}

	Venue(UUID ownerId, String name, String address, String city, String countryCode, String timeZone) {
		this.ownerId = ownerId;
		this.name = name;
		this.address = address;
		this.city = city;
		this.countryCode = countryCode;
		this.timeZone = timeZone;
	}

	public UUID getId() { return id; }
	public UUID getOwnerId() { return ownerId; }
	public String getName() { return name; }
	public String getAddress() { return address; }
	public String getCity() { return city; }
	public String getCountryCode() { return countryCode; }
	public String getTimeZone() { return timeZone; }
	public long getVersion() { return version; }
}
