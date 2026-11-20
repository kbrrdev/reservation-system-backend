package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.country.CountryProperties;
import dev.kbrr.ticketing.event.Event;
import dev.kbrr.ticketing.event.EventRepository;
import dev.kbrr.ticketing.event.EventStatus;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BookingService {

	private final UserRepository users;
	private final EventRepository events;
	private final TicketTypeRepository ticketTypes;
	private final BookingRepository bookings;
	private final CountryProperties countries;
	private final BookingProperties props;
	private final TicketRepository tickets;
	private final Clock clock;

	BookingService(UserRepository users, EventRepository events, TicketTypeRepository ticketTypes,
			BookingRepository bookings, TicketRepository tickets, CountryProperties countries,
			BookingProperties props, Clock clock) {
		this.users = users;
		this.events = events;
		this.ticketTypes = ticketTypes;
		this.bookings = bookings;
		this.tickets = tickets;
		this.countries = countries;
		this.props = props;
		this.clock = clock;
	}

	/**
	 * GA hold (spec flow 2). Lock order is user, event, then ticket types by id, the same everywhere, so
	 * concurrent bookings cannot deadlock. Any exception rolls back the counters already taken.
	 */
	@Transactional
	BookingResponse hold(UUID userId, HoldRequest req) {
		Set<UUID> ids = new HashSet<>();
		for (HoldRequest.Item item : req.items()) {
			if (!ids.add(item.ticketTypeId())) {
				throw ApiException.fieldError("items", "duplicate ticket type");
			}
		}

		// The user-row lock serializes this customer's holds, so the limit and one-pending checks below cannot race.
		users.findForUpdateById(userId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
		Event event = events.findForShareById(req.eventId()).filter(e -> e.getStatus() == EventStatus.ON_SALE)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Event not found"));
		Instant now = clock.instant();
		if (now.isBefore(event.getSaleStartsAt()) || !now.isBefore(event.getEndsAt())) {
			throw new ApiException(ErrorCode.EVENT_NOT_ON_SALE, "Tickets for this event are not on sale now");
		}

		Map<UUID, TicketType> types = ticketTypes.findAllById(ids).stream()
				.collect(Collectors.toMap(TicketType::getId, Function.identity()));
		if (types.size() != ids.size()
				|| types.values().stream().anyMatch(t -> !t.getEvent().getId().equals(event.getId()))) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Ticket type not found");
		}

		long requested = 0;
		for (HoldRequest.Item item : req.items()) {
			requested = Math.addExact(requested, item.quantity());
		}
		// Checked before any price arithmetic: past this point quantities are at most the platform limit.
		if (bookings.countActiveTickets(userId, event.getId()) + requested > event.getMaxTicketsPerCustomer()) {
			throw new ApiException(ErrorCode.TICKET_LIMIT_EXCEEDED,
					"At most " + event.getMaxTicketsPerCustomer() + " tickets per customer for this event");
		}

		Optional<Booking> pending = bookings.findPendingPurchase(userId, event.getId());
		if (pending.isPresent()) {
			// An expired hold is never released by noticing it here; only the expiry job releases it (rule 4).
			if (!pending.get().getExpiresAt().isAfter(now)) {
				throw new ApiException(ErrorCode.PREVIOUS_HOLD_RELEASING,
						"Your previous hold is being released, retry in a few seconds");
			}
			throw new ApiException(ErrorCode.PENDING_HOLD_EXISTS, "You already have a pending booking for this event");
		}

		// Read everything from managed entities first: the bulk updates below clear the persistence context.
		Booking booking = new Booking(userId, event.getId(), BookingType.PURCHASE, event.getCurrency(),
				now.plus(props.holdDuration()), countries.get(event.getVenue().getCountryCode()).paymentProvider(),
				now);
		// Java UUID.compareTo order of ticket type ids is the global lock order (confirm/release use it too).
		List<HoldRequest.Item> sorted = req.items().stream()
				.sorted(Comparator.comparing(HoldRequest.Item::ticketTypeId)).toList();
		sorted.forEach(i -> booking.addItem(i.ticketTypeId(), i.quantity(),
				types.get(i.ticketTypeId()).getPriceMinor()));

		List<UUID> unavailable = new ArrayList<>();
		for (HoldRequest.Item item : sorted) {
			if (ticketTypes.hold(item.ticketTypeId(), item.quantity()) == 0) {
				unavailable.add(item.ticketTypeId());
			}
		}
		if (!unavailable.isEmpty()) {
			throw new ApiException(ErrorCode.TICKETS_UNAVAILABLE, "Not enough tickets left",
					Map.of("unavailable", unavailable));
		}
		return BookingResponse.from(bookings.save(booking));
	}

	@Transactional(readOnly = true)
	BookingResponse get(UUID userId, UUID id) {
		return bookings.findById(id).filter(b -> b.getUserId().equals(userId)).map(BookingResponse::from)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Booking not found"));
	}

	/** Newest first; id breaks createdAt ties so paging is stable. */
	@Transactional(readOnly = true)
	Page<BookingResponse> list(UUID userId, int page, int size) {
		return bookings.findByUserIdAndTypeOrderByCreatedAtDesc(userId, BookingType.PURCHASE,
				PageRequest.of(page, size, Sort.by("id"))).map(BookingResponse::from);
	}

	/**
	 * Confirm step of spec flow 3, triggered by the fake payment until M4. Lock first, then decide: user, event
	 * (shared), booking, then ticket types. A booking past expiresAt that the job hasn't expired yet still confirms.
	 */
	@Transactional
	BookingResponse confirmFake(UUID userId, UUID id) {
		UUID eventId = bookings.findEventIdOwnedBy(id, userId).orElseThrow(BookingService::bookingNotFound);
		users.findForUpdateById(userId).orElseThrow();
		events.findForShareById(eventId).orElseThrow();
		Booking booking = bookings.findForUpdateById(id).orElseThrow();
		if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
			throw notPending();
		}
		List<BookingItem> items = lockOrder(booking); // read before the bulk updates detach the booking

		expectOneRow(bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED), id);
		for (BookingItem item : items) {
			expectOneRow(ticketTypes.confirm(item.getTicketTypeId(), item.getQuantity()), id);
		}
		Instant now = clock.instant();
		tickets.saveAll(items.stream().flatMap(i -> IntStream.range(0, i.getQuantity())
				.mapToObj(n -> new Ticket(id, i.getTicketTypeId(), TicketCodes.newCode(), now))).toList());
		return BookingResponse.from(bookings.findById(id).orElseThrow());
	}

	/** Owner cancels a pending booking (spec flow 5). No event lock needed: user, booking, ticket types. */
	@Transactional
	BookingResponse cancel(UUID userId, UUID id) {
		bookings.findEventIdOwnedBy(id, userId).orElseThrow(BookingService::bookingNotFound);
		users.findForUpdateById(userId).orElseThrow();
		List<BookingItem> items = lockOrder(bookings.findForUpdateById(id).orElseThrow());
		if (bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CANCELLED) == 0) {
			throw notPending();
		}
		release(items, id);
		Booking booking = bookings.findById(id).orElseThrow();
		booking.markCancelled(clock.instant(), userId);
		return BookingResponse.from(booking);
	}

	/**
	 * Spec flow 4. The conditional update is the whole decision: if a payment or cancel got there first it changes
	 * no rows and this backs off, so the held counters are released exactly once.
	 */
	@Transactional
	boolean expire(UUID id) {
		if (bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED) == 0) {
			return false;
		}
		release(lockOrder(bookings.findById(id).orElseThrow()), id);
		return true;
	}

	@Transactional(readOnly = true)
	List<TicketResponse> tickets(UUID userId, UUID id) {
		bookings.findEventIdOwnedBy(id, userId).orElseThrow(BookingService::bookingNotFound);
		List<Ticket> issued = tickets.findByBookingIdOrderById(id);
		Map<UUID, String> names = ticketTypes.findAllById(issued.stream().map(Ticket::getTicketTypeId)
				.collect(Collectors.toSet())).stream().collect(Collectors.toMap(TicketType::getId, TicketType::getName));
		return issued.stream().map(t -> new TicketResponse(t.getId(), t.getCode(), t.getTicketTypeId(),
				names.get(t.getTicketTypeId()), t.getStatus(), t.getIssuedAt())).toList();
	}

	private void release(List<BookingItem> items, UUID bookingId) {
		for (BookingItem item : items) {
			expectOneRow(ticketTypes.release(item.getTicketTypeId(), item.getQuantity()), bookingId);
		}
	}

	/** Java UUID.compareTo order of ticket type ids is the global lock order (the same sort as in hold). */
	private static List<BookingItem> lockOrder(Booking booking) {
		return booking.getItems().stream().sorted(Comparator.comparing(BookingItem::getTicketTypeId)).toList();
	}

	/** Our own locks make this exactly 1 row; anything else is a bug, so roll back rather than skew the counters. */
	private static void expectOneRow(int rows, UUID bookingId) {
		if (rows != 1) {
			throw new IllegalStateException("Expected 1 row changed for booking " + bookingId + ", got " + rows);
		}
	}

	private static ApiException bookingNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Booking not found");
	}

	private static ApiException notPending() {
		return new ApiException(ErrorCode.BOOKING_NOT_PENDING, "Booking is no longer pending payment");
	}
}
