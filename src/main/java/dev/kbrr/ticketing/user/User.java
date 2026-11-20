package dev.kbrr.ticketing.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private String email;
	private String passwordHash;
	private String name;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
	@Column(name = "role")
	@Enumerated(EnumType.STRING)
	private Set<Role> roles = EnumSet.noneOf(Role.class);

	private Instant createdAt;

	protected User() {
	}

	public User(String email, String passwordHash, String name, Set<Role> roles, Instant createdAt) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.roles = EnumSet.copyOf(roles);
		this.createdAt = createdAt;
	}

	public static String normalizeEmail(String raw) {
		return raw.strip().toLowerCase(Locale.ROOT);
	}

	public void addRole(Role role) {
		roles.add(role);
	}

	public UUID getId() { return id; }
	public String getEmail() { return email; }
	public String getPasswordHash() { return passwordHash; }
	public String getName() { return name; }
	public Set<Role> getRoles() { return roles; }
	public Instant getCreatedAt() { return createdAt; }
}
