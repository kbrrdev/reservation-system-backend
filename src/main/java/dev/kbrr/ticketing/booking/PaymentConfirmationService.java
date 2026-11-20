package dev.kbrr.ticketing.booking;

import dev.kbrr.ticketing.event.EventRepository;
import dev.kbrr.ticketing.payment.PaymentEvent;
import dev.kbrr.ticketing.payment.PaymentEventRepository;
import dev.kbrr.ticketing.payment.PaymentNotification;
import dev.kbrr.ticketing.payment.PaymentNotificationHandler;
import dev.kbrr.ticketing.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only path that confirms a booking (spec flow 3). One transaction: the payment event row and the booking change
 * commit together or not at all, so a duplicate delivery fails on the event's unique key, and a failed attempt leaves
 * nothing behind that would block the provider's retry.
 */
@Service
public class PaymentConfirmationService implements PaymentNotificationHandler {

	private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);

	private final PaymentEventRepository paymentEvents;
	private final BookingRepository bookings;
	private final UserRepository users;
	private final EventRepository events;
	private final BookingInventory inventory;
	private final Clock clock;

	PaymentConfirmationService(PaymentEventRepository paymentEvents, BookingRepository bookings, UserRepository users,
			EventRepository events, BookingInventory inventory, Clock clock) {
		this.paymentEvents = paymentEvents;
		this.bookings = bookings;
		this.users = users;
		this.events = events;
		this.inventory = inventory;
		this.clock = clock;
	}

	@Override
	@Transactional
	public void handle(PaymentNotification n) {
		Instant now = clock.instant();
		// existsById is a count query: the booking entity must not be loaded before the locked read below.
		UUID id = n.bookingId() != null && bookings.existsById(n.bookingId()) ? n.bookingId() : null;
		paymentEvents.saveAndFlush(new PaymentEvent(n, id, now)); // first: a duplicate delivery stops here
		if (!n.paid()) {
			log.info("payment event {} {} of type {} stored, nothing to do", n.provider(), n.providerEventId(), n.type());
			return;
		}
		if (id == null) {
			log.error("paid event matches no booking: {} {} booking {}", n.provider(), n.providerEventId(),
					n.bookingId());
			return;
		}

		// Lock order: user, event (shared), booking; seats / ticket types follow inside the inventory.
		BookingRepository.OwnerAndEvent keys = bookings.findOwnerAndEvent(id).orElseThrow();
		users.findForUpdateById(keys.getUserId()).orElseThrow();
		events.findForShareById(keys.getEventId()).orElseThrow();
		Booking booking = bookings.findForUpdateById(id).orElseThrow();

		if (n.amountMinor() == null || n.amountMinor() != booking.getTotalMinor()
				|| !booking.getCurrency().equals(n.currency()) || !booking.getPaymentProvider().equals(n.provider())) {
			log.error("payment {} {} does not match booking {}, refund required", n.provider(), n.providerEventId(), id);
			BookingInventory.expectOneRow(bookings.flagRefund(id), id);
			return;
		}
		if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
			// shortcut: every non-pending state just flags a refund; Task 4 refines CONFIRMED / EXPIRED / CANCELLED.
			log.error("payment {} {} for booking {} in state {}, refund required", n.provider(), n.providerEventId(),
					id, booking.getStatus());
			BookingInventory.expectOneRow(bookings.flagRefund(id), id);
			return;
		}
		BookingInventory.expectOneRow(
				bookings.updateStatus(id, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMED), id);
		inventory.sell(bookings.findById(id).orElseThrow(), now); // re-read: updateStatus cleared the context
	}
}
