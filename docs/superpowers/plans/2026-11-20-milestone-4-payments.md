# Milestone 4 — Payments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A hold returns a real checkout URL from the event country's payment provider (PayMongo test mode for PH). Only a
signature-verified, idempotent provider webhook confirms a booking. Late and double payments re-claim or are flagged for
refund. Admins see and clear refund flags. The fake-pay endpoint is gone.

**Architecture:**
- **`payment` package (provider-agnostic, no `booking` imports):**
  - The `PaymentGateway` interface, with one bean per provider, collected into a `PaymentGateways` map keyed by provider name.
  - The `PaymentEvent` entity, the webhook controller, and an after-commit listener that expires checkouts.
  - Adapters: `payment/paymongo` (real) and `payment/fake` (dev and test only).
- **`booking` package implements the payment side:**
  - `PaymentNotificationHandler`, a `payment` interface, is implemented in `booking`. This is dependency inversion: `booking` → `payment`, never the reverse.
  - Inventory moves are pulled out of `BookingService` into `BookingInventory` and shared by holds, webhooks, expiry and cancel.
- **Checkout creation runs outside any transaction:** `BookingCheckout` commits the hold (its own transaction), calls the
  gateway, then saves the session id in a second short transaction (spec rule 8).

**Tech Stack:** as M3, plus Spring `RestClient` (no new dependency) and Jackson 3 (`tools.jackson.databind`).

**Spec:** [docs/superpowers/specs/2026-11-19-event-ticketing-design.md](../specs/2026-11-19-event-ticketing-design.md).
Relevant sections:
- the milestone 4 row;
- Domain model → Booking (`payment_provider`, `payment_reference`, `refund_required`), PaymentEvent, Countries;
- Concurrency rules 1–3 and 6–9;
- flow 2 "After commit", flow 3 (all of it), flows 4–5 "after commit";
- API outline → Admin payments/bookings, Provider;
- Docker & configuration (`PAYMONGO_*`);
- Testing → M4.

Builds on M2 and M3: read the **Done notes** in `docs/superpowers/plans/2026-11-20-milestone-2-events-ga-holds.md` and
`…/2026-11-20-milestone-3-reserved-seating.md` (including "final review fixes", which lists the carry-overs done here).

## Global Constraints

- **Carried from M1–M3:** UUID ids, DTOs only, `ProblemDetail` + `code`, Flyway-only schema, injected `Clock`, M2 code style (tabs).
- **Locking (carried from M1–M3):**
  - Lock order: user → event (share) → booking → seats / ticket types.
  - Seats are locked only via JPQL `order by es.id`; ticket types are locked in Java `UUID.compareTo` order.
  - Every guarded update checks its row count.
  - Bulk updates `SET version = version + 1`.
- **No external call inside a transaction** (spec rule 8). Provider calls (`createCheckout`, `expireCheckout`) happen either
  outside any transaction (`BookingCheckout`) or in a `@TransactionalEventListener(phase = AFTER_COMMIT)`. A failure after
  commit is logged and never needed for correctness.
- **The client never confirms a booking.** Only `PaymentNotificationHandler.handle` (called by the webhook controller)
  confirms one.
- **The webhook body is raw `byte[]`.** The adapter verifies the signature over those exact bytes before any JSON parsing.
- **Idempotency:**
  - `PaymentEvent` is inserted first with `saveAndFlush`, in the same transaction as the work.
  - A duplicate (provider, provider event id) → `200`, no-op.
  - Any other failure rolls the insert back, so the provider's retry is processed again.
- **Refund flag:** `refund_required = true` via a bulk update that bumps `version`. It is never cleared except by the admin
  `refund-handled` endpoint, and that endpoint checks the version the admin saw (spec rule 9).
- **Secrets:** `PAYMONGO_SECRET_KEY` and `PAYMONGO_WEBHOOK_SECRET` come only from env vars / `.env` (git-ignored).
  A blank webhook secret rejects every PayMongo webhook (`401`); never verify against an empty key.
- Integration tests share one DB, one `MutableClock` and one `FakePaymentGateway`:
  - Create fresh users, venues and events per test.
  - Assert on your own booking / session ids, never on global counts.
  - Every concurrent wait has a timeout.
- Commands use Git Bash (`./mvnw`); integration tests need Docker running.

## Review Focus

1. **A paid webhook that arrives twice, or twice at once:** processed exactly once.
   - Tickets are issued once, and the second delivery gets `200`.
   - A *different* constraint failure during the work (not the `payment_events` unique key) must **not** be swallowed as a
     duplicate. It returns `500`, so the provider retries.
   - Tests in Tasks 3 and 7.
2. **Payment for a booking in any state:**
   - pending → confirmed;
   - expired or cancelled → re-claimed, or refund flagged with the booking unchanged;
   - confirmed → refund flagged;
   - event cancelled → refund flagged, and a pending booking is cancelled and released;
   - amount, currency or provider mismatch → refund flagged.
   - Inventory is never left half-taken. Tests in Tasks 3 and 4.
3. **Tampered or re-serialized webhook bodies, wrong or missing signature header, blank secret:** `401` before any parsing
   or DB write. Unknown provider path → `404`. Tests in Tasks 3 and 6.
4. **Checkout creation fails or the app dies around it:**
   - Provider error → booking `CANCELLED`, inventory released, `502 PAYMENT_PROVIDER_UNAVAILABLE`.
   - Session created but its id never saved → the webhook still confirms via metadata.
   - A rolled-back expire or cancel never calls the provider.
   - Tests in Task 2.
5. **Races at the edges:**
   - webhook vs expiry at the boundary → the payment is never lost;
   - a late payment vs a rebook up to the limit → the limit is never exceeded;
   - cancel vs new holds on the same seats → no seat stuck or doubled.
   - Tests in Task 7.

---

## File Structure

```
src/main/resources/db/migration/V9__payments.sql
src/main/resources/application.yml, application-dev.yml (modify), src/test/resources/application-test.yml (modify)
src/main/java/dev/kbrr/ticketing/
  common/ErrorCode.java (modify), auth/SecurityConfig.java (modify: permit webhook)
  payment/  PaymentGateway, CheckoutRequest, Checkout, PaymentNotification, InvalidSignatureException, PaymentGateways,
            PaymentNotificationHandler, PaymentEvent, PaymentEventRepository, PaymentWebhookController,
            CheckoutExpiryRequested, CheckoutExpiryListener, AdminPaymentController, UnmatchedPaymentResponse
  payment/fake/      FakePaymentGateway, FakePaymentProperties
  payment/paymongo/  PaymongoGateway, PaymongoProperties
  booking/  BookingInventory, BookingCheckout, PaymentConfirmationService, AdminBookingController, AdminRefundResponse,
            RefundHandledRequest (new); Booking, BookingRepository, BookingService, BookingResponse, BookingController,
            BookingProperties, ExpiryJob (modify); FakePaymentController (delete)
  event/TicketTypeRepository.java (modify: findForUpdateById)
src/test/java/dev/kbrr/ticketing/
  TestFixtures.java (modify: admin(), pay(...), webhook(...))
  payment/PaymentGatewaysTest, payment/paymongo/PaymongoGatewayTest, payment/PaymongoWebhookIT
  booking/CheckoutTest, booking/WebhookTest, booking/LatePaymentTest, booking/AdminRefundTest, booking/PaymentConcurrencyTest
  booking/ConfirmAndExpiryTest, ReservedConfirmAndExpiryTest (modify: pay through the webhook)
src/test/resources/paymongo/checkout_session_payment_paid.json
```

---

### Task 1: Payment foundation (schema, gateway contract, registry, fake gateway)

**Files:**
- Create:
  - `V9__payments.sql`
  - `payment/{PaymentGateway,CheckoutRequest,Checkout,PaymentNotification,InvalidSignatureException,PaymentGateways,PaymentEvent,PaymentEventRepository}.java`
  - `payment/fake/{FakePaymentGateway,FakePaymentProperties}.java`
- Modify: `booking/Booking.java` (new columns), `common/ErrorCode.java`, `application.yml`, `application-dev.yml`, `application-test.yml`
- Test: `payment/PaymentGatewaysTest.java`

**Interfaces:**
- Produces (all public, package `payment`):
  - `interface PaymentGateway`:
    - `String provider()`
    - `Checkout createCheckout(CheckoutRequest req)`
    - `void expireCheckout(String sessionId)`
    - `PaymentNotification parseWebhook(byte[] body, HttpHeaders headers)`: throws `InvalidSignatureException` on a bad,
      missing or unverifiable signature, before parsing.
  - Records:
    - `record CheckoutRequest(UUID bookingId, long amountMinor, String currency, String description)`
    - `record Checkout(String sessionId, String checkoutUrl)`
    - `record PaymentNotification(String provider, String providerEventId, String type, boolean paid, UUID bookingId,
      Long amountMinor, String currency, String payload)`. `bookingId`, `amountMinor` and `currency` are nullable
      (unparseable or missing → null).
  - `class InvalidSignatureException extends RuntimeException`.
  - `@Component PaymentGateways(List<PaymentGateway> all, CountryProperties countries)`:
    - `Optional<PaymentGateway> find(String provider)` and `PaymentGateway get(String provider)` (`IllegalStateException` if missing).
    - The constructor fails startup on two gateways with the same `provider()`, or on a configured country (enabled or not)
      whose `paymentProvider` has no gateway.
  - `@Entity PaymentEvent` (`payment_events`):
    - fields: `id`, `provider`, `providerEventId`, `type`, `boolean paid`, `Long amountMinor`, `String currency`,
      `Instant receivedAt`, `String payload`, `UUID bookingId`, `boolean refundHandled`;
    - constructor `PaymentEvent(PaymentNotification n, UUID matchedBookingId, Instant receivedAt)`.
  - `PaymentEventRepository`:
    - `boolean existsByProviderAndProviderEventId(String, String)`;
    - `Page<PaymentEvent> findUnmatched(Pageable)`: paid, `bookingId` null, not `refundHandled`, newest first;
    - `@Modifying int markRefundHandled(UUID id)`: `where id = :id and refundHandled = false and paid = true and bookingId is null`.
  - `Booking` gains:
    - `String paymentReference`, `String checkoutUrl`, `boolean refundRequired`, with getters;
    - `void markRefundHandled()`, which sets `refundRequired = false` (saved through `@Version`).
  - `ErrorCode`: `INVALID_SIGNATURE(401)`, `PAYMENT_PROVIDER_UNAVAILABLE(502)`, `REFUND_ALREADY_HANDLED(409)`.
  - `FakePaymentGateway` (`payment.fake`, provider `"fake"`, `@ConditionalOnBooleanProperty("ticketing.payments.fake.enabled")`):
    - **Checkouts:**
      - `createCheckout` returns `Checkout("fake_cs_" + bookingId, "https://checkout.fake.test/fake_cs_" + bookingId)`.
      - It records each `CheckoutRequest`, or throws `IllegalStateException("fake provider down")` once after `failNextCheckout()`.
      - `expireCheckout` records the session id.
    - **Webhooks:**
      - `parseWebhook` requires header `Fake-Signature` equal to `ticketing.payments.fake.webhook-secret`; otherwise
        `InvalidSignatureException`.
      - Body JSON: `{"id","type","bookingId","amountMinor","currency"}`. `paid` = `type.equals("payment.paid")`.
    - **Thread-safe test accessors:** `List<CheckoutRequest> checkouts()`, `List<String> expiredSessions()`, `void failNextCheckout()`.
    - `record FakePaymentProperties(boolean enabled, String webhookSecret)` under prefix `ticketing.payments.fake`.

Migration `V9__payments.sql` (exact):
```sql
alter table bookings
    add column payment_reference varchar(100),
    add column checkout_url      varchar(500),
    add column refund_required   boolean not null default false;
create index bookings_refund_required on bookings (created_at) where refund_required;
create table payment_events (
    id                uuid primary key,
    provider          varchar(30)  not null,
    provider_event_id varchar(100) not null,
    type              varchar(100) not null,
    paid              boolean      not null,
    amount_minor      bigint,
    currency          char(3),
    received_at       timestamptz  not null,
    payload           text         not null,
    booking_id        uuid references bookings (id),
    refund_handled    boolean      not null default false,
    unique (provider, provider_event_id)
);
create index payment_events_unmatched on payment_events (received_at)
    where paid and booking_id is null and not refund_handled;
-- A seat can never carry two live tickets (M3 carry-over; partial so a VOID ticket's seat can be sold again in M5/M6).
create unique index tickets_one_live_per_seat on tickets (event_seat_id)
    where event_seat_id is not null and status <> 'VOID';
```

**Config:**
- `application.yml`:
  - `ticketing.payments.fake.enabled: false`
  - `ticketing.payments.paymongo`:
    - `secret-key: ${PAYMONGO_SECRET_KEY:}`
    - `webhook-secret: ${PAYMONGO_WEBHOOK_SECRET:}`
    - `base-url: https://api.paymongo.com`
    - `success-url: http://localhost:8080/payment/success`
    - `cancel-url: http://localhost:8080/payment/cancel`
    - `payment-method-types: [gcash, paymaya, card]`
    - `live: false`
  - The paymongo keys are read in Task 6. Add them now so all config lives in one place.
- `application-dev.yml`:
  - `ticketing.payments.fake: {enabled: true, webhook-secret: dev-fake-secret}`
  - `ticketing.countries.PH.payment-provider: ${PH_PAYMENT_PROVIDER:fake}`. Dev works without a PayMongo account; set
    `PH_PAYMENT_PROVIDER=paymongo` to use test mode.
  - Remove `fake-payments.enabled`.
- `application-test.yml`:
  - `ticketing.payments.fake: {enabled: true, webhook-secret: test-fake-secret}`
  - `ticketing.payments.paymongo.webhook-secret: whsk_test_secret`
  - `ticketing.countries.PH.payment-provider: fake`
  - Remove `fake-payments.enabled`.

- [x] **Step 1: Write the failing tests** (`PaymentGatewaysTest`, plain unit tests, no Spring)

```java
@Test void routesByProvider() { /* gateways [fake("a"), fake("b")], country PH→"a" → get("a") is the first; find("zz") empty */ }
@Test void duplicateProviderFailsStartup() { /* two gateways "a" → IllegalStateException */ }
@Test void countryWithoutGatewayFailsStartup() { /* country PH→"paymongo", gateways [fake("a")] → IllegalStateException naming "paymongo" */ }
@Test void disabledCountryStillNeedsGateway() { /* US enabled=false → provider "x" missing → IllegalStateException (spec: adapter stays deployed) */ }
```
Use a tiny inline `PaymentGateway` stub in the test (`provider()` only; other methods throw).

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=PaymentGatewaysTest`.
- [x] **Step 3: Implement.**
  - Write the migration, the records, the entity fields, the registry, the fake gateway and the config above.
  - The existing suite must still boot:
    - the test profile now resolves PH, JP and US to `fake`;
    - `PaymongoGateway` doesn't exist yet, so nothing references `paymongo` in the test profile.
  - Dev profile: PH → `fake` by default.
  - `FakePaymentController` is still present until Task 3; leave it.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: payment gateway contract, provider registry, fake gateway, payments schema"`

**Done notes (Task 1):** commit a1630fe; suite 138 tests green (4 new `PaymentGatewaysTest`; context boot validates the V9 schema against the entities).
- Deviation: `fake-payments.enabled: true` KEPT in application-dev.yml and application-test.yml (not removed) because `FakePaymentController` and the M2/M3 confirm tests still need it. Task 3 removes the controller and these two keys. The other old keys are gone.
- Test yml sets `PH: {payment-provider: fake}` only; Spring merges it with the main PH entry (currency, min charge stay).
- Ruling R1: without dev/test profile the app does not start (PH to paymongo has no gateway until Task 6). Intended.
- `Booking.markRefundHandled()` and the new getters are package-private like the rest of `Booking`. `PaymentEvent`, `FakePaymentGateway` accessors are public.
- `FakePaymentGateway` JSON via a static Jackson 3 `JsonMapper`; webhook secret compared in constant time, blank secret rejects.
- RED step was not run separately: the test file and implementation were written together.

---

### Task 2: Checkout after commit, checkout expiry after commit

**Files:**
- Create: `booking/BookingCheckout.java`, `payment/{CheckoutExpiryRequested,CheckoutExpiryListener}.java`
- Modify: `booking/{BookingService,BookingRepository,BookingResponse,BookingController}.java`
- Test: `booking/CheckoutTest.java`

**Interfaces:**
- Consumes: `PaymentGateways`, `FakePaymentGateway` accessors, the `Booking` payment fields (Task 1).
- Produces:
  - `BookingCheckout` (package-private `@Service`, **not** `@Transactional`): `BookingResponse holdAndCheckout(UUID userId, HoldRequest req)`.
    `BookingController.hold` calls it instead of `BookingService.hold`. It runs these steps in order:
    1. `bookingService.hold(...)` commits the hold in its own transaction.
    2. Build `CheckoutRequest(bookingId, totalMinor, currency, "Tickets for " + eventTitle)` from the booking's committed
       state. Read it via `BookingService.checkoutRequest(UUID id) -> CheckoutRequest`, a read-only transaction.
    3. `gateways.get(booking provider).createCheckout(req)`, outside any transaction.
    4. On any `RuntimeException` from step 3:
       - call `bookingService.cancelAfterCheckoutFailure(id)`;
       - log at warn;
       - throw `ApiException(PAYMENT_PROVIDER_UNAVAILABLE, "Payment provider unavailable, please try again")`.
    5. `bookingService.attachCheckout(id, sessionId, checkoutUrl)`. If that throws: log at error and still return the URL.
       The webhook finds the booking via metadata (spec flow 2).
    6. Return the booking response with `checkoutUrl` set.
  - `BookingService`:
    - `@Transactional(readOnly = true) CheckoutRequest checkoutRequest(UUID bookingId)`.
    - `@Transactional void attachCheckout(UUID id, String sessionId, String checkoutUrl)` through
      `BookingRepository.setCheckout(id, ref, url)`, a bulk update with `version + 1`. The row count isn't required: a
      booking cancelled in between is fine.
    - `@Transactional void cancelAfterCheckoutFailure(UUID id)`: `PENDING_PAYMENT → CANCELLED` (rule 1), release,
      `cancelledBy` null. There's no session, so no expiry event.
    - `expire` and owner `cancel`: when the booking has a `paymentReference`, publish
      `CheckoutExpiryRequested(provider, reference)` through `ApplicationEventPublisher` inside the transaction.
  - `BookingResponse.from` fills `checkoutUrl` from `Booking.getCheckoutUrl()`.
  - `payment.CheckoutExpiryRequested(String provider, String sessionId)` (public record).
  - `payment.CheckoutExpiryListener`: `@TransactionalEventListener(phase = AFTER_COMMIT)` → `gateways.get(provider).expireCheckout(sessionId)`.
    It catches `RuntimeException` and logs at warn: never rethrow.

- [x] **Step 1: Write the failing tests** (`CheckoutTest`, `@IntegrationTest`, PH venue → provider `fake`)

```java
@Test void holdReturnsCheckoutUrlAndSavesReference() { /* hold → 201, $.checkoutUrl "https://checkout.fake.test/fake_cs_<id>";
   fake.checkouts() has {bookingId, amountMinor == total, "PHP"}; GET /api/bookings/{id} → same checkoutUrl;
   DB payment_reference == "fake_cs_<id>", payment_provider == "fake" */ }
@Test void providerFailureCancelsAndReleases() { /* fake.failNextCheckout(); hold 2 GA → 502 PAYMENT_PROVIDER_UNAVAILABLE;
   booking CANCELLED (find it by user via GET /api/bookings), availability remaining back to capacity;
   immediate rebook succeeds (no PENDING_HOLD_EXISTS) */ }
@Test void expireAndCancelExpireTheCheckoutAfterCommit() { /* hold A, advance 10m+1s, expiryJob.run() → fake.expiredSessions()
   contains A's reference; hold B, DELETE → contains B's reference */ }
@Test void rolledBackExpireCallsNoProvider() { /* hold, advance past expiry; TransactionTemplate: service.expire(id) then
   status.setRollbackOnly() → booking still PENDING_PAYMENT and fake.expiredSessions() lacks its reference */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=CheckoutTest`.
- [x] **Step 3: Implement.** Existing `HoldTest` / `ReservedHoldTest` must still pass. Their responses now carry a `checkoutUrl`.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: create checkout after the hold commits, expire it after expiry or cancel"`

**Done notes (Task 2):** commit 4da62cf; suite 142 tests green (4 new `CheckoutTest`).
- Interface: `BookingService.checkoutData(id)` returns `CheckoutData(provider, CheckoutRequest)` instead of a bare `CheckoutRequest`, so `BookingCheckout` also gets the provider without a second read. `BookingService` now takes `ApplicationEventPublisher`.
- `HoldTest.holdsGaTickets` asserted `checkoutUrl` absent; it now expects the fake URL prefix.
- Known gap: `attachCheckout` after an expiry/cancel that already committed leaves a live session at the provider (no event fired, reference was still null). A later payment on it reaches the webhook for a non-pending booking (Task 3 refund path).
- `CheckoutExpiryListener` is package-private; `cancelAfterCheckoutFailure` locks user then booking like `cancel`.
- RED step not run separately: tests and implementation written together.

---

### Task 3: Webhook endpoint, idempotency, confirm step (remove fake pay)

**Files:**
- Create:
  - `payment/{PaymentNotificationHandler,PaymentWebhookController}.java`
  - `booking/{BookingInventory,PaymentConfirmationService}.java`
- Modify:
  - `booking/{BookingService,BookingRepository}.java`
  - `auth/SecurityConfig.java`
  - `TestFixtures.java`
  - `booking/ConfirmAndExpiryTest.java`, `booking/ReservedConfirmAndExpiryTest.java`
- Delete: `booking/FakePaymentController.java`, and `BookingService.confirmFake`
- Test: `booking/WebhookTest.java`

**Interfaces:**
- Produces:
  - `payment.PaymentNotificationHandler` (public interface): `void handle(PaymentNotification n)`.
  - **`PaymentWebhookController`:** `POST /api/payments/webhook/{provider}`, `@RequestBody byte[] body`, `@RequestHeader HttpHeaders headers`.
    1. `gateways.find(provider)` is empty → `404 NOT_FOUND "Unknown payment provider"`.
    2. `parseWebhook`. `InvalidSignatureException` → `401 INVALID_SIGNATURE`, with nothing written.
    3. `handler.handle(n)`.
       - On `DataIntegrityViolationException`: if `paymentEvents.existsByProviderAndProviderEventId(n.provider(),
         n.providerEventId())`, log at info "duplicate delivery" and return `200`. Otherwise rethrow (→ `500`, the provider retries).
       - This check runs **outside** the handler's transaction: that one is already rollback-only.
    4. `200` with an empty body.
  - `SecurityConfig`: `POST /api/payments/webhook/*` → `permitAll()`.
  - `BookingInventory` (package-private `@Component`, every method `@Transactional(propagation = MANDATORY)`, the caller holds
    the booking lock):
    - `void sell(Booking b, Instant now)`: HELD → SOLD and issue tickets.
      - Reserved: `seatInventory.confirm`, one ticket per seat.
      - GA: `ticketTypes.confirm` per item in lock order, `quantity` tickets each.
      - Moved verbatim from `confirmFake`.
    - `void release(Booking b)`: moved from `BookingService.release`.
    - `boolean isReserved(Booking b)`.
    - All reads of `b` happen before the first bulk update, as today.
  - `PaymentConfirmationService` (`booking`, public class, implements `PaymentNotificationHandler`, `@Transactional handle`).
    In this task:
    1. `UUID matched = n.bookingId() != null && bookings.existsById(n.bookingId()) ? n.bookingId() : null`.
       Then `paymentEvents.saveAndFlush(new PaymentEvent(n, matched, now))`, **first**.
    2. `!n.paid()` → log at info, return.
    3. `matched == null` → log at **error** "paid event matches no booking", return. It shows in the admin list (Task 5).
    4. Lock in rule-7 order:
       - read `userId` and `eventId` with a projection `BookingRepository.findOwnerAndEvent(id)` (not the entity: see the
         M2 Done note on stale managed instances);
       - `users.findForUpdateById`;
       - `events.findForShareById`;
       - `bookings.findForUpdateById`.
    5. Mismatch: `amountMinor` is null or ≠ `totalMinor`, `currency` ≠ booking currency, or `provider` ≠ `paymentProvider`
       → `bookings.flagRefund(id)`, return.
    6. `PENDING_PAYMENT` → `updateStatus(PENDING_PAYMENT → CONFIRMED)` (expect 1 row), then `inventory.sell(booking, now)`.
    7. Every other state → Task 4. For now: `CONFIRMED` → `flagRefund`; `EXPIRED` / `CANCELLED` → `flagRefund`.
  - `BookingRepository.flagRefund(UUID id)`: `@Modifying(flushAutomatically, clearAutomatically)`,
    `set refundRequired = true, version = version + 1 where id = :id`. Expect 1 row.
  - **`TestFixtures`:**
    - `MockHttpServletResponse webhook(String eventId, String type, UUID bookingId, Long amountMinor, String currency)`: posts the fake JSON with header `Fake-Signature: test-fake-secret` to `/api/payments/webhook/fake`.
    - `MockHttpServletResponse pay(UUID bookingId)`: a fresh event id, `"payment.paid"`, and the booking's total and currency read via JDBC.
    - `User admin()`.

- [x] **Step 1: Write the failing tests** (`WebhookTest`)

```java
@Test void paidWebhookConfirms() { /* hold 2 GA → fixtures.pay(id) → 200; booking CONFIRMED, 2 tickets, held 0 sold 2;
   one payment_events row with booking_id == id, paid true */ }
@Test void reservedPaidWebhookConfirms() { /* hold 2 seats → pay → seats SOLD, 2 tickets with seat labels */ }
@Test void duplicateDeliveryIgnored() { /* same eventId posted twice → both 200; tickets still 2; one payment_events row */ }
@Test void badSignature401() { /* Fake-Signature wrong or missing → 401 INVALID_SIGNATURE; no payment_events row; booking pending */ }
@Test void unknownProvider404() { /* POST /api/payments/webhook/stripe → 404 */ }
@Test void otherEventTypeStoredAnd200() { /* type "payment.failed" → 200; row stored paid=false; booking still pending */ }
@Test void unmatchedBooking200() { /* random bookingId → 200; row with booking_id null, paid true */ }
@Test void mismatchFlagsRefund() { /* amount total-1 → 200, booking PENDING_PAYMENT, refund_required true, no tickets;
   same for currency "JPY"; provider mismatch: jdbc set payment_provider='paymongo' then fake webhook → refund flagged */ }
@Test void failureMidConfirmLeavesNoPaymentEventSoRetryWorks() { /* reserved hold; jdbc set one seat AVAILABLE/booking_id null
   (confirm's row count then fails) → webhook 500, no payment_events row; restore the seat via jdbc; same eventId again → 200 CONFIRMED */ }
@Test void otherConstraintFailureIsNotSwallowed() { /* reserved hold [s1]; jdbc insert a VALID ticket for s1 on this booking
   (code 'preexisting') so sell's ticket insert hits tickets_one_live_per_seat → webhook 500 (not a 200 "duplicate"),
   no payment_events row, booking still PENDING_PAYMENT */ }
@Test void crashBeforeReferenceSavedStillConfirms() { /* hold; jdbc payment_reference = null; pay → CONFIRMED */ }
@Test void noFakePayEndpoint() { /* POST /api/bookings/{id}/fake-pay → 404 */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=WebhookTest`.
- [x] **Step 3: Implement.**
  - Delete `FakePaymentController` and `confirmFake`.
  - Rewrite the 6 `fake-pay` / `confirmFake` call sites in `ConfirmAndExpiryTest` and `ReservedConfirmAndExpiryTest`:
    - MockMvc tests use `fixtures.pay(id)`;
    - service-level race tests call `paymentConfirmationService.handle(notification)` with a `PaymentNotification` built in the test.
  - Their assertions stay the same.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: verified idempotent payment webhook confirms bookings; remove fake pay"`

**Done notes (Task 3):** commit 2b1408c; suite 154 tests green (12 new `WebhookTest`); the three confirm/webhook test classes ran green 2 extra times. RED: all 12 `WebhookTest` failed (401 from security, no endpoint, fake-pay still 200).
- Lock SQL checked: `existsById` emits `select count(*)` (booking not loaded); then payment_events insert, `findOwnerAndEvent` projection, user `for no key update`, event `for share`, booking `for no key update`.
- `findOwnerAndEvent` returns a nested interface projection `BookingRepository.OwnerAndEvent` (`getUserId`/`getEventId`), not a record: reliable across Spring Data versions.
- `PaymentConfirmationService` re-reads the booking after `updateStatus` (which clears the context) and passes that to `inventory.sell`, like `cancel` does for `release`; `sell` reads items/seats before its own bulk updates. `expectOneRow` moved to `BookingInventory` as package-private static (used by the confirmation service too); `lockOrder` moved there as private.
- All non-pending states flag refund (marked `shortcut:` in code for Task 4). Mismatch and non-pending both log at error.
- Test migration deviations (the old assertions could not survive the endpoint change): `payOrCancelAfterExpiry` now expects the late webhook to return 200 and set `refund_required` (was 409); `othersCannotPayOrCancel` → `othersCannotCancelOrSeeTickets` (a webhook has no caller identity); both `payRaceWithExpiry` tests decide the pay side's win from the booking status after the race (handle never throws for a lost race) and additionally assert the refund flag when expiry wins. `fakePay*` tests renamed `pay*`; the GA one reads status/items via `GET /api/bookings/{id}` since the webhook body is empty.
- `TestFixtures.webhook/pay` declare `throws Exception` (MockMvc). Constructor now also takes `MockMvc` and `JdbcTemplate`.
- `ticketing.fake-payments.enabled` removed from dev/test yml (ruling R2). README still shows the fake-pay curl; Task 7 replaces it.
- Gotcha for later tasks: `PaymentEvent` has an assigned id and no `@Version`, so `saveAndFlush` goes through `merge` and emits an extra `select` by id before the insert. Harmless (random UUID), but `Persistable`/`persist` would save a round trip.
- Gotcha (by reading, untested): a signed but malformed fake payload (no `id`) fails the NOT NULL insert → `existsBy...(provider, null)` is false → 500, so the provider would retry it forever. Fine for the fake gateway; Task 6 may want to 400/200 unparseable events.
- Open questions: none.

---

### Task 4: Late payments, double payments, cancelled events

**Files:**
- Modify: `booking/{PaymentConfirmationService,BookingInventory,BookingRepository}.java`, `event/TicketTypeRepository.java`
- Test: `booking/LatePaymentTest.java`

**Interfaces:**
- Produces:
  - `BookingInventory.boolean reclaim(Booking b)`. It re-takes the inventory a no-longer-pending booking recorded, as HELD
    for this booking. It changes **nothing** and returns `false` if any part is unavailable:
    - **Reserved:**
      - `eventSeats.lockByIds(eventId, b.getEventSeatIds())` (Postgres order);
      - all `AVAILABLE` → `markHeld(ids, bookingId)` must equal the count, else `IllegalStateException`;
      - any not available → `false`.
    - **GA:**
      - `ticketTypes.findForUpdateById(id)` for each item, in `lockOrder` (Java UUID order);
      - if every `capacity - held - sold >= quantity`, then `ticketTypes.hold(id, q)` for each, expecting 1 row each;
      - otherwise `false`.
  - `TicketTypeRepository.findForUpdateById(UUID)`: `@Lock(PESSIMISTIC_WRITE)`.
  - `PaymentConfirmationService` branches on the **locked** state, after the mismatch check:
    1. **Event `CANCELLED`** (checked first, any booking status) → `flagRefund`. If the booking is `PENDING_PAYMENT`, also
       `updateStatus(→ CANCELLED)` and `inventory.release`.
       Events can't be cancelled until M5, so the test sets the status with JDBC.
    2. **`PENDING_PAYMENT`** → confirm (Task 3).
    3. **`EXPIRED` / `CANCELLED`** (late payment):
       - checks:
         - the event is `ON_SALE` and `now < endsAt`;
         - `countActiveTickets(user, event) + this booking's quantity <= maxTicketsPerCustomer`;
         - `inventory.reclaim(booking)`.
       - all pass → `updateStatus(<current> → CONFIRMED)` (expect 1 row), then `inventory.sell(booking, now)`;
       - any fails → `flagRefund`, booking unchanged.
       - `cancelledAt` / `cancelledBy` stay as history.
    4. **`CONFIRMED`** → `flagRefund` (double payment).

- [x] **Step 1: Write the failing tests** (`LatePaymentTest`)

```java
@Test void latePaymentReclaimsGa() { /* hold 2, advance past expiry, expiryJob.run() → EXPIRED; pay → CONFIRMED, 2 tickets,
   held 0 sold 2, refund_required false */ }
@Test void latePaymentReclaimsSeats() { /* reserved: expire, pay → CONFIRMED, both seats SOLD with booking id */ }
@Test void latePaymentAfterCancelReclaims() { /* hold, DELETE → CANCELLED; pay → CONFIRMED */ }
@Test void latePaymentSeatTakenFlagsRefund() { /* expire X [s1,s2]; customer B holds s2; pay X → X stays EXPIRED,
   refund_required true, s1 AVAILABLE (nothing half-taken), s2 HELD by B */ }
@Test void latePaymentSoldOutGaFlagsRefund() { /* capacity 2: X holds 2, expires; B holds 2; pay X → refund, held 2 sold 0 */ }
@Test void latePaymentOverLimitFlagsRefund() { /* limit 2: X holds 2, expires; same customer holds 2 more (B);
   pay X → refund flagged, X EXPIRED, customer's active tickets == 2 */ }
@Test void doublePaymentFlagsRefund() { /* pay, then a second paid event (new eventId) → still CONFIRMED, 2 tickets, refund true */ }
@Test void cancelledEventFlagsRefundAndReleasesPending() { /* hold; jdbc events.status='CANCELLED'; pay → booking CANCELLED,
   refund true, inventory released, no tickets, response 200 */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=LatePaymentTest`.
- [x] **Step 3: Implement.**
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: late payments re-claim or flag refund; double payments and cancelled events flag refund"`

**Done notes (Task 4):** commit db5e5f3; suite 163 tests green (9 new `LatePaymentTest`); the three confirm/late test classes ran green 1 extra time. RED: the 3 reclaim cases and the cancelled-event case failed as expected. The 4 refund-only cases already passed on the Task 3 shortcut. The extra event-ended test errored on a test bug (the `starts_at < ends_at` check), fixed before GREEN.
- Lock SQL checked: `TicketTypeRepository.findForUpdateById` emits `... from ticket_types tt1_0 where tt1_0.id=? for no key update of tt1_0`. All ticket types are locked and checked before the first `hold` (which clears the context).
- The event check uses the `Event` returned by `findForShareById`. Order after the mismatch check: event CANCELLED, then a switch on the locked booking status.
- Deviations: (1) the event-cancelled branch also calls `markCancelled(now, null)` on a pending booking, like `cancelAfterCheckoutFailure`, so every CANCELLED booking has `cancelledAt`. (2) Extra test `latePaymentAfterEventEndedFlagsRefund` covers the `now < endsAt` check (JDBC moves the schedule into the past). (3) `BookingRepository` needed no change: `countActiveTickets` already excludes the EXPIRED/CANCELLED booking itself. (4) `BookingInventory` now also takes `EventSeatRepository`. Reclaim reuses `SeatInventory.hold` for the markHeld count check; it does not use `lockForHold`, whose 404 `ApiException` must not reach a webhook.
- Test migration: both `payRaceWithExpiry` tests can no longer say "exactly one side wins". A payment after the expiry now re-claims the free inventory, so either order ends CONFIRMED with refund false, held 0 and one sale. They now assert just that. `payOrCancelAfterExpiry` expects the late payment to confirm (sold 2, 2 tickets, refund false), then the cancel to get 409.
- Gotcha for Task 7: `webhookVsExpiryNeverLosesPayment` matches what the updated race tests already check. `latePaymentVsRebookNeverExceedsLimit` relies on the user lock, which `handle` takes first.
- Open questions: none.

---

### Task 5: Admin refund endpoints

**Files:**
- Create:
  - `booking/{AdminBookingController,AdminRefundResponse,RefundHandledRequest}.java`
  - `payment/{AdminPaymentController,UnmatchedPaymentResponse}.java`
- Modify: `booking/{BookingService,BookingRepository}.java`
- Test: `booking/AdminRefundTest.java`

**Interfaces:**
- Produces (all under `/api/admin/**`, which already requires `ADMIN`):
  - **Refund list:** `GET /api/admin/bookings?refundRequired=true&page&size`.
    - Returns `PagedModel<AdminRefundResponse>`, newest first; the query param is required and must be `true`.
    - `record AdminRefundResponse(UUID id, UUID userId, UUID eventId, BookingStatus status, Money total,
      String paymentProvider, String paymentReference, boolean refundRequired, long version)`.
  - **Clear a booking's refund flag:** `POST /api/admin/bookings/{id}/refund-handled`, body
    `record RefundHandledRequest(@NotNull Long version)`. In a transaction:
    1. missing → `404`;
    2. `version` ≠ current → `409 STALE_UPDATE`;
    3. `!refundRequired` → `409 REFUND_ALREADY_HANDLED`;
    4. otherwise `markRefundHandled()` and save. A concurrent change makes the `@Version` check throw → the existing handler
       returns `409 STALE_UPDATE`.

    Returns the updated `AdminRefundResponse`.
  - **Unmatched payments:** `GET /api/admin/payments/unmatched?page&size` → `PagedModel<UnmatchedPaymentResponse>`.
    - `record UnmatchedPaymentResponse(UUID id, String provider, String providerEventId, Long amountMinor, String currency,
      Instant receivedAt)`.
  - **Clear an unmatched payment:** `POST /api/admin/payments/{id}/refund-handled` → `markRefundHandled`. 1 row → `204`;
    0 rows → `409 REFUND_ALREADY_HANDLED` if the row exists, else `404`.

- [x] **Step 1: Write the failing tests** (`AdminRefundTest`)

```java
@Test void listsAndClearsRefundFlag() { /* double payment → GET as admin lists it with version v;
   POST refund-handled {version: v} → 200 refundRequired false; list no longer has it */ }
@Test void staleRefundHandled409() { /* flag via double payment, admin reads version v; a third paid webhook flags again
   (version bumps); POST {version: v} → 409 STALE_UPDATE; refund_required still true */ }
@Test void alreadyHandled409() { /* handled once, POST with the new version → 409 REFUND_ALREADY_HANDLED */ }
@Test void unmatchedPaymentsListedAndCleared() { /* unmatched paid webhook → listed; POST → 204; again → 409; list no longer has it */ }
@Test void nonAdmin403() { /* customer and organizer tokens → 403 on all four */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=AdminRefundTest`.
- [x] **Step 3: Implement.**
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: admin refund-required and unmatched-payment endpoints"`

**Done notes (Task 5):** commit 3685e69; suite 169 tests green (6 new `AdminRefundTest`: the 5 planned plus an unknown-booking / bad-filter case). Step 2 RED not run: the tests were written together with the implementation, so no failing-run evidence.
- `refundRequired=false` or a missing param both give `400` (`VALIDATION_FAILED` / Spring's missing-param error).
- Stale check is the explicit version compare plus `saveAndFlush`, so a concurrent `@Version` clash surfaces as `409 STALE_UPDATE` from the service call. The concurrent-clash path itself is not tested (only the compare path).
- Unmatched-payment clear runs in a controller-level `@Transactional` (no service layer, YAGNI); the guarded update decides, `existsById` only separates `404` from `409`.
- Tests assert on their own ids in `size=100` pages; if the shared DB ever holds more than 100 refund-flagged bookings the list assertions could miss.

---

### Task 6: PayMongo adapter (test mode)

**Files:**
- Create:
  - `payment/paymongo/{PaymongoGateway,PaymongoProperties}.java`
  - `src/test/resources/paymongo/checkout_session_payment_paid.json`
- Modify: `.env.example`
- Test: `payment/paymongo/PaymongoGatewayTest.java` (unit), `payment/PaymongoWebhookIT.java` (`@IntegrationTest`; name it `…Test` if Surefire's includes don't pick up `IT`)

**Interfaces:**
- `@ConfigurationProperties("ticketing.payments.paymongo") record PaymongoProperties(String secretKey, String webhookSecret,
  URI baseUrl, URI successUrl, URI cancelUrl, List<String> paymentMethodTypes, boolean live)`.
- `PaymongoGateway` (`@Component`, provider `"paymongo"`), constructor `(PaymongoProperties, RestClient.Builder, JsonMapper, Clock)`:
  - **RestClient:**
    - `baseUrl` from the properties; Basic auth with `secretKey` as the username and an empty password.
    - Connect timeout 5 s, read timeout 10 s.
    - Blank `secretKey` → `createCheckout` throws `IllegalStateException("PayMongo secret key not configured")`. Checkout
      creation then fails cleanly (→ 502) instead of calling out.
  - **`createCheckout`:** `POST /v1/checkout_sessions` with the body below. Returns `Checkout(data.id, data.attributes.checkout_url)`.
    ```json
    {"data":{"attributes":{
      "line_items":[{"name":"<description>","amount":<amountMinor>,"currency":"<currency>","quantity":1}],
      "payment_method_types":<paymentMethodTypes>, "success_url":"…", "cancel_url":"…",
      "description":"<description>", "reference_number":"<bookingId>", "show_line_items":true,
      "metadata":{"booking_id":"<bookingId>"}}}}
    ```
  - **`expireCheckout`:** `POST /v1/checkout_sessions/{id}/expire`.
  - **`parseWebhook`:**
    1. A blank `webhookSecret` → `InvalidSignatureException`.
    2. Header `Paymongo-Signature` has the form `t=<ts>,te=<hex>,li=<hex>`.
       - Expected signature = lower-case hex of HMAC-SHA256(`webhookSecret`, `t + "." + new String(body, UTF_8)`).
       - Compare with `MessageDigest.isEqual` against `te` (or `li` when `live`).
       - A missing header, missing part or no match → `InvalidSignatureException`.
    3. Only after that, parse with `JsonMapper`:
       - `providerEventId` = `data.id`;
       - `type` = `data.attributes.type`, and `paid` = `type == "checkout_session.payment.paid"`;
       - from the session `s` = `data.attributes.data`:
         - `bookingId` = `s.attributes.metadata.booking_id`, parsed as a UUID (invalid → null);
         - `amountMinor` = `s.attributes.payments[0].attributes.amount` (missing → null);
         - `currency` = `s.attributes.payments[0].attributes.currency` (missing → null).
    4. `payload` = the raw body string.
  - No timestamp tolerance check. Idempotency covers replays, and a tolerance could reject legitimate retries.
- `.env.example`: add `PAYMONGO_SECRET_KEY=`, `PAYMONGO_WEBHOOK_SECRET=` and `# PH_PAYMENT_PROVIDER=paymongo` (dev uses `fake` unless set).

**Field names come from the PayMongo API as known at planning time:**
- Automated fetches of docs.paymongo.com were refused (403), so the names could not be re-checked.
- Before the Task 7 manual test with real keys, the human checks them against the docs or the dashboard's webhook logs:
  - the checkout body;
  - `checkout_url`;
  - the signature format;
  - `payments[0].attributes.amount`.
- If they differ, only `PaymongoGateway` and the sample JSON change.

- [x] **Step 1: Write the failing tests**

`PaymongoGatewayTest` (no Spring; `MockRestServiceServer.bindTo(builder)`; a fixed `Clock`):
```java
@Test void createCheckoutSendsBodyAndParsesResponse() { /* expect POST {base}/v1/checkout_sessions, Authorization "Basic " +
   base64("sk_test_x:"), JSON body has line_items[0].amount 250000, currency "PHP", metadata.booking_id == id;
   respond {"data":{"id":"cs_123","attributes":{"checkout_url":"https://checkout.paymongo.com/cs_123"}}} → Checkout("cs_123", url) */ }
@Test void blankSecretKeyFailsWithoutCallingOut() { /* secretKey "" → IllegalStateException; server.verify() has no requests */ }
@Test void expireCheckoutPostsExpire() { /* expect POST {base}/v1/checkout_sessions/cs_123/expire */ }
@Test void validSignatureParsesPaidEvent() { /* sample JSON (test resource) signed with whsk_test_secret, header
   "t=1700000000,te=<hex>,li=" → provider "paymongo", paid true, bookingId from metadata, amountMinor 250000, currency "PHP" */ }
@Test void reserializedBodyRejected() { /* sign the original bytes, then send the same JSON re-serialized (spaces/key order
   changed) → InvalidSignatureException */ }
@Test void wrongSecretMissingHeaderOrLiveMismatchRejected() { /* each → InvalidSignatureException */ }
@Test void blankWebhookSecretRejectsEverything() { /* webhookSecret "" with a header signed by "" → InvalidSignatureException */ }
@Test void nonPaidTypeAndBadMetadataParse() { /* type "payment.failed" → paid false; metadata.booking_id "x" → bookingId null */ }
```

`PaymongoWebhookIT` (`@IntegrationTest`):
```java
@Test void signedPaymongoWebhookProviderMismatchFlagsRefund() { /* hold (provider "fake" in tests) → sign sample JSON with this
   booking id, amount = total → POST /api/payments/webhook/paymongo → 200; booking still pending, refund_required true
   (provider mismatch, spec M4 test) */ }
@Test void unsignedPaymongoWebhook401() { /* no header → 401 INVALID_SIGNATURE */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='PaymongoGatewayTest,PaymongoWebhookIT'`.
- [x] **Step 3: Implement.** `PaymentGateways` now also has `"paymongo"`. Main config keeps PH → `paymongo`; the test and dev profiles override it.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: PayMongo test-mode adapter (checkout sessions, signed webhooks)"`

**Done notes (Task 6):**
- Commit: `7922167`. Full suite 181 tests green. RED step not run separately (tests and impl written together).
- Ruling R4 done: `payment.MalformedWebhookException`, thrown by both gateways after signature check (bad JSON, missing id/type); controller maps it to 400 MALFORMED_REQUEST and logs at error, no DB write. Tests: PaymongoGatewayTest + WebhookTest.
- Ruling R1 ends: main config PH -> paymongo now has a gateway (not run without a profile, only by the test context).
- Deviations: (1) Added `spring-boot-starter-restclient` (Boot 4 has no RestClient.Builder bean without it). (2) No `Clock` constructor arg (no timestamp tolerance, so unused). (3) Timeouts are set globally via `spring.http.clients.connect-timeout/read-timeout` (5s/10s) instead of in code, because setting a request factory on the builder would override MockRestServiceServer.bindTo. (4) `PaymongoWebhookIT` is named `PaymongoWebhookTest` so Surefire runs it.
- Gotcha: a blank webhook secret is rejected before any HMAC (an empty HMAC key throws IllegalArgumentException).
- Open question for the human: PayMongo field names (checkout body, `checkout_url`, `Paymongo-Signature` format, `payments[0].attributes.amount`) were NOT verified against docs.paymongo.com; verify before the Task 7 manual test. Only PaymongoGateway and the sample JSON would change.

---

### Task 7: Payment races, expiry job hardening, dev setup, README

**Files:**
- Modify: `booking/{ExpiryJob,BookingRepository,BookingProperties}.java`, `application.yml`, `README.md`
- Test: `booking/PaymentConcurrencyTest.java`, plus `ExpiryJob` tests added to `booking/ConfirmAndExpiryTest.java`

**Interfaces:**
- **ExpiryJob fix (M3 carry-over):** one set of bookings that keep failing must not block the rest.
  - `BookingRepository.findExpiredPendingIds(Instant now, int offset, int limit)`, HQL
    `… order by b.expiresAt, b.id limit :limit offset :offset`.
  - `ExpiryJob`:
    - It keeps `failed` = the number of exceptions in this run and fetches with `offset = failed`.
    - A booking that expires, or that a webhook already took (`false`), leaves the pending set. A failing one stays in front.
    - It loops while a batch is full.
  - Batch size comes from the new `BookingProperties.expiryBatchSize` (default `100`, `ticketing.booking.expiry-batch-size`).
    Package-private constructor `ExpiryJob(BookingRepository, BookingService, Clock, int batchSize)` for tests.

- [x] **Step 1: Write the failing tests**

`PaymentConcurrencyTest` (service level: `PaymentConfirmationService.handle`, `BookingService.expire/hold/cancel`; start
latch; `get(30, SECONDS)`):
```java
@Test void webhookVsExpiryNeverLosesPayment() { /* 20 GA bookings past expiresAt; for each, handle(paid) and expire in parallel
   → every booking CONFIRMED with its tickets, refund_required false, held 0 */ }
@Test void payWinsDeterministically() { /* test tx locks the booking (findForUpdateById); start handle(paid) and wait until
   it blocks on a lock (pg_stat_activity wait_event_type = 'Lock', poll ≤ 10 s); start expire, wait until it blocks too;
   commit the test tx → booking CONFIRMED, and fake.expiredSessions() lacks its reference (expire found nothing to do) */ }
@Test void duplicateDeliveriesAtOnceProcessedOnce() { /* 10 threads deliver the same paid event through the controller path
   (MockMvc or the controller bean) → all 200, 1 payment_events row, 2 tickets */ }
@Test void latePaymentVsRebookNeverExceedsLimit() { /* limit 2: X (2 tickets) expired; in parallel handle(paid X) and the same
   customer holds 2 → active tickets ≤ 2; exactly one of: X CONFIRMED and hold 409 TICKET_LIMIT_EXCEEDED, or hold 201 and
   X refund flagged */ }
@Test void cancelWhileOthersGrabSameSeats() { /* reserved X [s1,s2]; in parallel cancel(X) and 10 customers holding [s2,s1] →
   only TICKETS_UNAVAILABLE / PENDING_HOLD_EXISTS as failures; every seat AVAILABLE or HELD by exactly one pending booking */ }
```
In `ConfirmAndExpiryTest`:
```java
@Test void expiryJobSkipsFailingBookings() { /* job with batchSize 1; 2 reserved bookings past expiry, the earlier one poisoned
   (jdbc: one of its seats AVAILABLE, booking_id null → release fails); run() → the later booking EXPIRED, the poisoned one
   still PENDING_PAYMENT; run() returns (no infinite loop: assertTimeoutPreemptively 10 s) */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='PaymentConcurrencyTest,ConfirmAndExpiryTest'`.
- [x] **Step 3: Implement** the ExpiryJob change. The race tests should pass on Task 3–4 code; if one fails, fix the code, not the test.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`; run `PaymentConcurrencyTest` 3 times.
- [x] **Step 5: README:**
  - Milestone 4 ✅, status line "Milestones 1–4 done; milestone 5 next."
  - "Try it": replace the fake-pay step with the dev fake-provider webhook `curl`:
    - header `Fake-Signature: dev-fake-secret`;
    - body `{"id":"evt_1","type":"payment.paid","bookingId":"$BOOKING","amountMinor":<total>,"currency":"PHP"}`.
  - A short "PayMongo test mode" section:
    - set `PAYMONGO_SECRET_KEY`, `PAYMONGO_WEBHOOK_SECRET` and `PH_PAYMENT_PROVIDER=paymongo` in `.env`;
    - expose the app with a tunnel (e.g. `ngrok http 8080`) and register `https://<tunnel>/api/payments/webhook/paymongo`
      for `checkout_session.payment.paid` in the PayMongo dashboard;
    - pay on the returned `checkoutUrl` with a test method.
  - Admin refund endpoints in one line.
- [x] **Step 6: Manual check** (`docker compose up -d`, app with the `dev` profile, stop it afterwards):
  1. A customer holds 2 Sample Concert tickets: the response has `checkoutUrl` `https://checkout.fake.test/…`.
  2. The fake webhook `curl` → `200`. The booking is `CONFIRMED` with 2 tickets.
  3. The same `curl` again → `200`, still 2 tickets.
  4. A bad `Fake-Signature` → `401`.
  5. As admin, a second paid event id for the same booking → `GET /api/admin/bookings?refundRequired=true` lists it.
  - *Optional, only if the human has put PayMongo test keys in `.env`:* `PH_PAYMENT_PROVIDER=paymongo`, hold, open
    `checkoutUrl`, pay in test mode through a tunnel, and confirm. Otherwise report it as skipped.
- [x] **Step 7: Commit** — `git commit -m "feat: payment race tests, expiry job skips failing bookings, README for milestone 4"`

**Done notes (Task 7):** commit e7c1cc2; suite 187 tests green (5 new `PaymentConcurrencyTest`, 1 new `ConfirmAndExpiryTest.expiryJobSkipsFailingBookings`); `PaymentConcurrencyTest` ran green 3 more times. RED: the expiry test failed on the old loop (with the 4-arg constructor added first): the later booking stayed `PENDING_PAYMENT`. The 5 race tests passed on Task 3–4 code with no fixes, as the brief expected.
- The job fixes `now` once per run, so the set of candidates only shrinks and the loop always ends. A failing booking that a payment resolves during the run shifts the offset by one, so one booking may wait for the next run. Harmless.
- `expiryBatchSize` is a primitive `int` set in `application.yml`; a config that leaves it out would get 0 and loop forever on empty batches. Every profile inherits the main yml, so this is only a risk for a hand-written config.
- `payWinsDeterministically` waits on `pg_stat_activity` (`wait_event_type = 'Lock'`, this database) for 1 then 2 waiters; no helper existed in M2/M3, so it is a private method in the test. The booking is held through `BookingCheckout`, so it has a payment reference.
- `expiryJobSkipsFailingBookings` restores the poisoned seat and expires the booking in `finally`, so later expiry runs on the shared DB stay quiet.
- Manual check (dev, fake provider) all passed: hold → `checkout.fake.test` URL; webhook 200, CONFIRMED, 2 tickets; repeat 200, still 2; bad signature 401; second event id → listed under `refundRequired=true`. Real PayMongo step skipped: no `PAYMONGO_SECRET_KEY` in `.env`. The dev DB now holds that test booking.
- Open question carried from Task 6: PayMongo field names are still unverified against the live API.

**Done notes (final review fixes):**
- Fixed: (1) `PaymongoGateway.createCheckout` rejects a response with a blank/missing id or checkout URL (booking is cancelled, 502); (2) `ExpiryJob` rejects a non-positive batch size at startup; (3) both gateways reject an over-long event id/type or a currency that is not 3 chars as 400 (`MalformedWebhookException`), not a 500 on every redelivery; (4) a paid webhook without an amount is now covered by a test (200, stays `PENDING_PAYMENT`, refund required, no tickets); (5) the unmatched-payments page has an `id` tie-break sort; (6) README warns not to open a tunnel with the fake provider and notes the missing redirect pages.
- Tests: suite 187 -> 192 green (new: checkout response, long type, bad currency, no amount, expiry batch size).
- Deferred to M7: make `CheckoutExpiryListener` async on a bounded executor (a slow provider currently delays the expiry job and holds a DB connection during the call); with the PayMongo field-name verification, compute the HMAC over the raw bytes instead of the decoded string.
- Deferred to M5: refund detail (paid amount, payment events) in the admin refund response; a concurrent `@Version` clash test.
- Deferred to M8: `cancelAfterCheckoutFailure` throwing gives a 500; untested listener-swallow and attach-failure branches.
- Still open for the human: verify PayMongo field names against docs.paymongo.com.

---

## Decisions made in this plan (spec left them open or M4 narrows them)

- **Checkout creation lives in `BookingCheckout`, outside any transaction:** the hold response needs the URL synchronously.
  That's cleaner than returning it from an after-commit listener. Expiring a checkout has no caller waiting on it, so it
  uses `@TransactionalEventListener(AFTER_COMMIT)`. Both satisfy rule 8.
- **`payment` never imports `booking`:** the webhook calls `PaymentNotificationHandler`, which `booking` implements. That
  matches the spec layout (`payment` = gateway + webhook controller) with no package cycle.
- **The fake gateway is real code, enabled only in dev and test:**
  - The spec says to fake `PaymentGateway` in tests and to simulate webhooks in dev.
  - Dev defaults PH to `fake`, so the app works with no PayMongo account.
  - `PaymentGateways` fails startup if any configured country names a provider with no gateway. So `fake` can't leak into
    a production config where the fake is disabled.
- **The duplicate-delivery check runs outside the failed transaction:** only a confirmed existing `(provider, event id)`
  turns a `DataIntegrityViolationException` into `200`. Any other constraint failure stays a `500`, so a real bug never
  silently drops a payment.
- **`checkout_url` is stored**, beyond the spec's `payment_reference`, so `GET /api/bookings/{id}` can show the payment link
  again after a page reload.
- **Late GA re-claim locks the ticket-type rows** (`FOR NO KEY UPDATE`, Java order) and checks all of them before changing
  any. That way a partial re-take can't leave counters moved while the refund flag commits.
- **The event-cancelled branch is built now** (the M2 carry-over: re-check event status under the share lock). Events can't
  be cancelled until M5, so it's tested by setting the status with JDBC.
- **Checkout-creation failure → booking `CANCELLED` + `502`:** returning the admission pass is M7.
- **No webhook timestamp tolerance:** replays are made harmless by idempotency, and a tolerance window could reject the
  provider's own retries.
- **Deferred to later milestones:**
  - Free bookings with no checkout (M6).
  - Event cancel expiring pending checkouts (M5).
  - Rate-limiting the public webhook endpoint (not in spec).
