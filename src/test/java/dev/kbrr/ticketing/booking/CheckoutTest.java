package dev.kbrr.ticketing.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.kbrr.ticketing.IntegrationTest;
import dev.kbrr.ticketing.MutableClock;
import dev.kbrr.ticketing.TestFixtures;
import dev.kbrr.ticketing.event.EventResponse;
import dev.kbrr.ticketing.payment.CheckoutRequest;
import dev.kbrr.ticketing.payment.fake.FakePaymentGateway;
import dev.kbrr.ticketing.user.User;
import dev.kbrr.ticketing.venue.VenueResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class CheckoutTest {

	private static final Duration PAST_EXPIRY = Duration.ofMinutes(10).plusSeconds(1);

	@Autowired MockMvc mvc;
	@Autowired TestFixtures fx;
	@Autowired MutableClock clock;
	@Autowired BookingService bookings;
	@Autowired BookingRepository bookingRepository;
	@Autowired ExpiryJob expiryJob;
	@Autowired FakePaymentGateway fake;
	@Autowired PlatformTransactionManager txManager;

	private EventResponse event(int capacity) {
		User org = fx.organizer();
		VenueResponse v = fx.venue(org, "PH", "Asia/Manila");
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of(v.timeZone()));
		return fx.gaEvent(org, v, capacity, 150000, 8, now.minusHours(1), now.plusDays(30),
				now.plusDays(30).plusHours(3));
	}

	private ResultActions hold(User u, EventResponse e, int quantity) throws Exception {
		return mvc.perform(post("/api/bookings").header("Authorization", fx.bearer(u)).contentType(APPLICATION_JSON)
				.content("{\"eventId\":\"%s\",\"items\":[{\"ticketTypeId\":\"%s\",\"quantity\":%d}]}"
						.formatted(e.id(), e.ticketTypes().getFirst().id(), quantity)));
	}

	private UUID holdId(User u, EventResponse e, int quantity) throws Exception {
		return UUID.fromString(JsonPath.read(hold(u, e, quantity).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString(), "$.id"));
	}

	private void remaining(EventResponse e, int expected) throws Exception {
		mvc.perform(get("/api/events/" + e.id() + "/availability"))
				.andExpect(jsonPath("$.ticketTypes[0].remaining").value(expected));
	}

	@Test
	void holdReturnsCheckoutUrlAndSavesReference() throws Exception {
		EventResponse e = event(10);
		User c = fx.customer();
		String body = hold(c, e, 2).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		String url = "https://checkout.fake.test/fake_cs_" + id;
		assertThat((String) JsonPath.read(body, "$.checkoutUrl")).isEqualTo(url);

		CheckoutRequest sent = fake.checkouts().stream().filter(r -> r.bookingId().equals(id)).findFirst()
				.orElseThrow();
		assertThat(sent.amountMinor()).isEqualTo(300000);
		assertThat(sent.currency()).isEqualTo("PHP");

		mvc.perform(get("/api/bookings/" + id).header("Authorization", fx.bearer(c))).andExpect(status().isOk())
				.andExpect(jsonPath("$.checkoutUrl").value(url));
		Booking b = bookingRepository.findById(id).orElseThrow();
		assertThat(b.getPaymentReference()).isEqualTo("fake_cs_" + id);
		assertThat(b.getPaymentProvider()).isEqualTo("fake");
	}

	@Test
	void providerFailureCancelsAndReleases() throws Exception {
		EventResponse e = event(10);
		User c = fx.customer();
		fake.failNextCheckout();
		hold(c, e, 2).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("PAYMENT_PROVIDER_UNAVAILABLE"));

		mvc.perform(get("/api/bookings").header("Authorization", fx.bearer(c))).andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].status").value("CANCELLED"));
		remaining(e, 10);
		hold(c, e, 2).andExpect(status().isCreated());
	}

	@Test
	void expireAndCancelExpireTheCheckoutAfterCommit() throws Exception {
		EventResponse e = event(10);
		User a = fx.customer();
		UUID idA = holdId(a, e, 1);
		clock.advance(PAST_EXPIRY);
		expiryJob.run();
		assertThat(fake.expiredSessions()).contains("fake_cs_" + idA);

		User b = fx.customer();
		UUID idB = holdId(b, e, 1);
		mvc.perform(delete("/api/bookings/" + idB).header("Authorization", fx.bearer(b))).andExpect(status().isOk());
		assertThat(fake.expiredSessions()).contains("fake_cs_" + idB);
	}

	@Test
	void rolledBackExpireCallsNoProvider() throws Exception {
		EventResponse e = event(10);
		User c = fx.customer();
		UUID id = holdId(c, e, 1);
		clock.advance(PAST_EXPIRY);

		new TransactionTemplate(txManager).executeWithoutResult(status -> {
			assertThat(bookings.expire(id)).isTrue();
			status.setRollbackOnly();
		});

		assertThat(bookingRepository.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING_PAYMENT);
		assertThat(fake.expiredSessions()).doesNotContain("fake_cs_" + id);
		// the expiry job in other tests may run later; this booking is theirs to expire, so don't leave it pending
		expiryJob.run();
	}
}
