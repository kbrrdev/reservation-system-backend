package dev.kbrr.ticketing.venue;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SectionService {

	private static final int MAX_SECTION_SEATS = 5000;
	private static final int MAX_VENUE_SEATS = 20000;

	private final VenueService venues;
	private final SectionRepository sections;
	private final SeatRepository seats;

	SectionService(VenueService venues, SectionRepository sections, SeatRepository seats) {
		this.venues = venues;
		this.sections = sections;
		this.seats = seats;
	}

	@Transactional
	public SectionResponse create(UUID ownerId, UUID venueId, SectionRequest req) {
		// The row lock serializes this with event creation, so a layout can't change under a new event.
		venues.requireOwnedForUpdate(ownerId, venueId);
		if (sections.venueHasEvents(venueId)) {
			throw new ApiException(ErrorCode.VENUE_IN_USE, "Venue layout can't change once an event uses it");
		}
		List<String> rows = req.rowLabels().stream().map(r -> r.toUpperCase(Locale.ROOT)).toList();
		if (new HashSet<>(rows).size() != rows.size()) {
			throw ApiException.fieldError("rowLabels", "row labels must be unique");
		}
		if (sections.findByVenueIdOrderByName(venueId).stream().anyMatch(s -> s.getName().equals(req.name()))) {
			throw ApiException.fieldError("name", "section name already exists");
		}
		int perRow = req.seatsPerRow();
		int count = rows.size() * perRow; // at most 100 * 200, no overflow
		if (count > MAX_SECTION_SEATS) {
			throw ApiException.fieldError("seatsPerRow", "a section has at most 5000 seats");
		}
		if (seats.countByVenueId(venueId) + count > MAX_VENUE_SEATS) {
			throw ApiException.fieldError("seatsPerRow", "a venue has at most 20000 seats");
		}
		Section section = sections.save(new Section(venueId, req.name()));
		List<Seat> batch = new ArrayList<>(count);
		for (int r = 0; r < rows.size(); r++) {
			for (int n = 1; n <= perRow; n++) {
				batch.add(new Seat(section.getId(), rows.get(r), r, n));
			}
		}
		seats.saveAll(batch);
		return new SectionResponse(section.getId(), section.getName(), rows, perRow, count);
	}

	@Transactional(readOnly = true)
	public List<SectionResponse> list(UUID ownerId, UUID venueId) {
		venues.requireOwned(ownerId, venueId);
		List<Section> found = sections.findByVenueIdOrderByName(venueId);
		if (found.isEmpty()) {
			return List.of();
		}
		// Rows are not stored on the section; rebuild them from its seats.
		Map<UUID, List<String>> labels = new LinkedHashMap<>();
		Map<UUID, Integer> perRow = new LinkedHashMap<>();
		Map<UUID, Integer> total = new LinkedHashMap<>();
		for (Object[] row : seats.rowsOf(found.stream().map(Section::getId).toList())) {
			UUID id = (UUID) row[0];
			int n = ((Number) row[2]).intValue();
			labels.computeIfAbsent(id, k -> new ArrayList<>()).add((String) row[1]);
			perRow.putIfAbsent(id, n);
			total.merge(id, n, Integer::sum);
		}
		return found.stream().map(s -> new SectionResponse(s.getId(), s.getName(),
				labels.getOrDefault(s.getId(), List.of()), perRow.getOrDefault(s.getId(), 0),
				total.getOrDefault(s.getId(), 0))).toList();
	}
}
