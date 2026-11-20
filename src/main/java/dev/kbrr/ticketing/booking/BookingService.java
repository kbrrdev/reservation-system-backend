package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.common.ApiException;
import dev.kbrr.ticketing.common.ErrorCode;
import dev.kbrr.ticketing.country.CountryProperties;
import dev.kbrr.ticketing.event.Event;
import dev.kbrr.ticketing.event.EventRepository;
import dev.kbrr.ticketing.event.EventSeat;
import dev.kbrr.ticketing.event.EventSeatRepository;
import dev.kbrr.ticketing.event.EventSeatStatus;
import dev.kbrr.ticketing.event.EventStatus;
import dev.kbrr.ticketing.event.SeatMapRow;
import dev.kbrr.ticketing.event.SeatingMode;
import dev.kbrr.ticketing.event.TicketType;
import dev.kbrr.ticketing.event.TicketTypeRepository;
import dev.kbrr.ticketing.payment.CheckoutExpiryRequested;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
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
	private final SeatInventory seatInventory;
	private final EventSeatRepository eventSeats;
	private final Clock clock;
	private final ApplicationEventPublisher publisher;
	private final BookingInventory inventory;

	BookingService(UserRepository users, EventRepository events, TicketTypeRepository ticketTypes,
			BookingRepository bookings, TicketRepository tickets, CountryProperties countries,
			BookingProperties props, SeatInventory seatInventory, EventSeatRepository eventSeats, Clock clock,
			ApplicationEventPublisher publisher, BookingInventory inventory) {
		this.users = users;
		this.events = events;
		this.ticketTypes = ticketTypes;
		this.bookings = bookings;
		this.tickets = tickets;
		this.seatInventory = seatInventory;
		this.eventSeats = eventSeats;
		this.countries = countries;
		this.props = props;
		this.clock = clock;
		this.publisher = publisher;
		this.inventory = inventory;
	}

	/**
	 * Hold (spec flow 2), GA or reserved. Lock order is user, event (shared), then ticket types by Java UUID order
	 * (GA) or seats by Postgres id order (reserved), the same everywhere, so concurrent bookings cannot deadlock.
	 * Any exception rolls back whatever was already taken.
	 */
	@Transactional
	BookingResponse hold(UUID userId, HoldRequest req) {
		List<HoldRequest.Item> items = req.items() == null ? List.of() : req.items();
		List<UUID> seatList = req.eventSeatIds() == null ? List.of() : req.eventSeatIds();
		if (items.isEmpty() == seatList.isEmpty()) {
			throw ApiException.fieldError("items",
					"send either items (general admission) or eventSeatIds (reserved seating)");
		}
		Set<UUID> seatIds = new HashSet<>(seatList);
		if (seatIds.size() != seatList.size()) {
			throw ApiException.fieldError("eventSeatIds", "duplicate seat");
		}
		Set<UUID> ids = new HashSet<>();
		for (HoldRequest.Item item : items) {
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
		// Before any inventory is touched: a reserved event's ticket-type counters are never used.
		boolean reserved = !seatIds.isEmpty();
		if (reserved != (event.getSeatingMode() == SeatingMode.RESERVED)) {
			throw new ApiException(ErrorCode.NOT_FOUND, reserved ? "Seat not found" : "Ticket type not found");
		}
		if (reserved) {
			return holdSeats(userId, event, now, seatIds);
		}

		Map<UUID, TicketType> types = ticketTypes.findAllById(ids).stream()
				.collect(Collectors.toMap(TicketType::getId, Function.identity()));
		if (types.size() != ids.size()
				|| types.values().stream().anyMatch(t -> !t.getEvent().getId().equals(event.getId()))) {
			throw new ApiException(ErrorCode.NOT_FOUND, "Ticket type not found");
		}

		long requested = 0;
		for (HoldRequest.Item item : items) {
			requested = Math.addExact(requested, item.quantity());
		}
		// Checked before any price arithmetic: past this point quantities are at most the platform limit.
		checkPendingAndLimit(userId, event, now, requested);

		// Read everything from managed entities first: the bulk updates below clear the persistence context.
		Booking booking = newPurchase(userId, event, now);
		// Java UUID.compareTo order of ticket type ids is the global lock order (confirm/release use it too).
		List<HoldRequest.Item> sorted = items.stream()
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

	/**
	 * Reserved branch of {@link #hold}. The seat row locks make the AVAILABLE check final until commit, so the
	 * guarded markHeld afterwards must change every seat; "already taken" is reported before anything changes.
	 */
	private BookingResponse holdSeats(UUID userId, Event event, Instant now, Set<UUID> seatIds) {
		checkPendingAndLimit(userId, event, now, seatIds.size());
		Booking booking = newPurchase(userId, event, now); // reads the venue before markHeld clears the context

		List<EventSeat> seats = seatInventory.lockForHold(event.getId(), seatIds);
		List<UUID> unavailable = seats.stream().filter(s -> s.getStatus() != EventSeatStatus.AVAILABLE)
				.map(EventSeat::getId).toList();
		if (!unavailable.isEmpty()) {
			throw new ApiException(ErrorCode.TICKETS_UNAVAILABLE, "Some seats are no longer available",
					Map.of("unavailable", unavailable));
		}

		Map<UUID, Long> countByType = seats.stream()
				.collect(Collectors.groupingBy(EventSeat::getTicketTypeId, TreeMap::new, Collectors.counting()));
		Map<UUID, Long> prices = ticketTypes.findAllById(countByType.keySet()).stream()
				.collect(Collectors.toMap(TicketType::getId, TicketType::getPriceMinor));
		countByType.forEach((typeId, n) -> booking.addItem(typeId, n.intValue(), prices.get(typeId)));
		seats.forEach(s -> booking.addSeat(s.getId()));
		// The row must exist before markHeld points event_seats.booking_id at it (FK).
		UUID bookingId = bookings.saveAndFlush(booking).getId();

		seatInventory.hold(seatIds, bookingId);
		return BookingResponse.from(bookings.findById(bookingId).orElseThrow());
	}

	private void checkPendingAndLimit(UUID userId, Event event, Instant now, long requested) {
		Optional<Booking> pending = bookings.findPendingPurchase(userId, event.getId());
		if (pending.isPresent()) {
			// An expired hold is never released by noticing it here; only the expiry job releases it (rule 4).
			if (!pending.get().getExpiresAt().isAfter(now)) {
				throw new ApiException(ErrorCode.PREVIOUS_HOLD_RELEASING,
						"Your previous hold is being released, retry in a few seconds");
			}
			throw new ApiException(ErrorCode.PENDING_HOLD_EXISTS, "You already have a pending booking for this event");
		}
		if (bookings.countActiveTickets(userId, event.getId()) + requested > event.getMaxTicketsPerCustomer()) {
			throw new ApiException(ErrorCode.TICKET_LIMIT_EXCEEDED,
					"At most " + event.getMaxTicketsPerCustomer() + " tickets per customer for this event");
		}
	}

	private Booking newPurchase(UUID userId, Event event, Instant now) {
		return new Booking(userId, event.getId(), BookingType.PURCHASE, event.getCurrency(),
				now.plus(props.holdDuration()), countries.get(event.getVenue().getCountryCode()).paymentProvider(),
				now);
	}

	@Transactional(readOnly = true)
	BookingResponse get(UUID userId, UUID id) {
		return bookings.findById(id).filter(b -> b.getUserId().equals(userId)).map(BookingResponse::from)
				.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Booking not found"));
	}

	@Transactional(readOnly = true)
	Page<AdminRefundResponse> listRefundRequired(int page, int size) {
		return bookings.findByRefundRequiredTrueOrderByCreatedAtDesc(PageRequest.of(page, size, Sort.by("id")))
				.map(AdminRefundResponse::from);
	}

	/**
	 * Spec rule 9: the admin clears the flag only against the version they saw. The explicit compare catches a change
	 * before the load; flushing here lets @Version catch one between load and flush inside this call (409 either way).
	 */
	@Transactional
	AdminRefundResponse refundHandled(UUID id, long version) {
		Booking b = bookings.findById(id).orElseThrow(BookingService::bookingNotFound);
		if (b.getVersion() != version) {
			throw new ApiException(ErrorCode.STALE_UPDATE, "Changed by someone else, reload and retry");
		}
		if (!b.isRefundRequired()) {
			throw new ApiException(ErrorCode.REFUND_ALREADY_HANDLED, "Refund already handled");
		}
		b.markRefundHandled();
		return AdminRefundResponse.from(bookings.saveAndFlush(b));
	}

	/** Newest first; id breaks createdAt ties so paging is stable. */
	@Transactional(readOnly = true)
	Page<BookingResponse> list(UUID userId, int page, int size) {
		return bookings.findByUserIdAndTypeOrderByCreatedAtDesc(userId, BookingType.PURCHASE,
				PageRequest.of(page, size, Sort.by("id"))).map(BookingResponse::from);
	}

	/** Owner cancels a pending booking (spec flow 5). No event lock needed: user, booking, ticket types / seats. */
	@Transactional
	BookingResponse cancel(UUID userId, UUID id) {
		bookings.findEventIdOwnedBy(id, userId).orElseThrow(BookingService::bookingNotFound);
		users.findForUpdateById(userId).orElseThrow();
		bookings.findForUpdateById(id).orElseThrow();
		if (bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CANCELLED) == 0) {
			throw notPending();
		}
		inventory.release(bookings.findById(id).orElseThrow());
		Booking booking = bookings.findById(id).orElseThrow();
		booking.markCancelled(clock.instant(), userId);
		requestCheckoutExpiry(booking);
		return BookingResponse.from(booking);
	}

	record CheckoutData(String provider, CheckoutRequest request) {
	}

	@Transactional(readOnly = true)
	CheckoutData checkoutData(UUID bookingId) {
		Booking b = bookings.findById(bookingId).orElseThrow();
		String title = events.findById(b.getEventId()).orElseThrow().getTitle();
		return new CheckoutData(b.getPaymentProvider(),
				new CheckoutRequest(b.getId(), b.getTotalMinor(), b.getCurrency(), "Tickets for " + title));
	}

	@Transactional
	void attachCheckout(UUID id, String sessionId, String checkoutUrl) {
		bookings.setCheckout(id, sessionId, checkoutUrl); // 0 rows is fine: the booking may be cancelled meanwhile
	}

	/** The provider call failed before any session existed, so nothing to expire. Lock order as in cancel. */
	@Transactional
	void cancelAfterCheckoutFailure(UUID id) {
		UUID userId = bookings.findById(id).orElseThrow().getUserId();
		users.findForUpdateById(userId).orElseThrow();
		bookings.findForUpdateById(id).orElseThrow();
		if (bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CANCELLED) == 0) {
			return; // paid or expired in the meantime: nothing to undo
		}
		inventory.release(bookings.findById(id).orElseThrow());
		bookings.findById(id).orElseThrow().markCancelled(clock.instant(), null);
	}

	/** Published inside the transaction; the listener acts only after commit, so a rollback calls no provider. */
	private void requestCheckoutExpiry(Booking b) {
		if (b.getPaymentReference() != null) {
			publisher.publishEvent(new CheckoutExpiryRequested(b.getPaymentProvider(), b.getPaymentReference()));
		}
	}

	/**
	 * Spec flow 4. The conditional update is the whole decision: if a payment or cancel got there first it changes
	 * no rows and this backs off, so the hold is released exactly once. Locks: booking (by the update), then ticket
	 * types or seats.
	 */
	@Transactional
	boolean expire(UUID id) {
		if (bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.EXPIRED) == 0) {
			return false;
		}
		Booking booking = bookings.findById(id).orElseThrow();
		inventory.release(booking);
		requestCheckoutExpiry(booking);
		return true;
	}

	@Transactional(readOnly = true)
	List<TicketResponse> tickets(UUID userId, UUID id) {
		bookings.findEventIdOwnedBy(id, userId).orElseThrow(BookingService::bookingNotFound);
		List<Ticket> issued = tickets.findByBookingIdOrderById(id);
		Map<UUID, String> names = ticketTypes.findAllById(issued.stream().map(Ticket::getTicketTypeId)
				.collect(Collectors.toSet())).stream().collect(Collectors.toMap(TicketType::getId, TicketType::getName));
		List<UUID> seatIds = issued.stream().map(Ticket::getEventSeatId).filter(Objects::nonNull).toList();
		Map<UUID, SeatMapRow> seats = seatIds.isEmpty() ? Map.of() : eventSeats.findSeatLabels(seatIds).stream()
				.collect(Collectors.toMap(SeatMapRow::eventSeatId, Function.identity()));
		return issued.stream().map(t -> {
			SeatMapRow s = t.getEventSeatId() == null ? null : seats.get(t.getEventSeatId());
			return new TicketResponse(t.getId(), t.getCode(), t.getTicketTypeId(), names.get(t.getTicketTypeId()),
					t.getStatus(), t.getIssuedAt(), t.getEventSeatId(), s == null ? null : s.sectionName(),
					s == null ? null : s.rowLabel(), s == null ? null : s.seatNumber());
		}).toList();
	}

	private static ApiException bookingNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "Booking not found");
	}

	private static ApiException notPending() {
		return new ApiException(ErrorCode.BOOKING_NOT_PENDING, "Booking is no longer pending payment");
	}
}
