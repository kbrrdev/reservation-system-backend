# Milestone 2 — Countries, Venues, Events, GA Holds Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Organizers create venues (country + time zone) and general-admission events that go straight on sale;
customers hold GA tickets for 10 minutes, "pay" through a fake confirm endpoint and get ticket codes; an expiry job
releases unpaid holds, and concurrency tests prove GA never oversells.

**Architecture:** New feature packages `country`, `venue`, `event`, `booking` next to M1's `auth`, `user` and `common`.
Inventory lives only in Postgres. Hold, confirm, cancel and expire are `@Transactional` service methods. They lock in
the spec's order (user → event → booking → ticket types) and move counters and statuses only through conditional
`UPDATE` queries that check the affected row count.
Services return DTO records (mapped inside the transaction, because `open-in-view` is off).

**Tech Stack:** as M1 (Java 25, Spring Boot 4.1.2, Hibernate 7, Jackson 3, Postgres 16, Flyway, Testcontainers),
plus Spring scheduling (`@Scheduled`) and Spring Data `Specification` / `PagedModel`.

**Spec:** [docs/superpowers/specs/2026-11-19-event-ticketing-design.md](../specs/2026-11-19-event-ticketing-design.md)
(milestone 2 row; "Domain model", "Concurrency rules" 1, 3, 4, 5, 6, 7, 9; flow 2 GA path; flows 4 and 5 (pending
booking only); "Testing → M2").

## Global Constraints

- Everything in M1's Global Constraints still holds (UUID ids, DTOs only, `ProblemDetail` + `code`, Flyway-only schema,
  injected `Clock`, `postgres:16`). Match M1 code style (tabs, package-private where possible).
- **Money:** `long` amount in the currency's minor units + ISO 4217 code. Minor-unit digits only from
  `Currency.getDefaultFractionDigits()`. Totals use `Math.multiplyExact` / `Math.addExact`. API shape `{ "amountMinor": 150000, "currency": "PHP" }`.
- **Countries** come from config `ticketing.countries`, not a table. PH: `enabled: true, currency: PHP, payment-provider: paymongo, min-charge-minor: 2000`
  (PayMongo's minimum is PHP 20.00). `enabled` only gates **new** venues and events.
- **Schedule input** (`startsAt`, `endsAt`, `saleStartsAt`) is venue-local wall time with **no offset**, pattern
  `uuuu-MM-dd'T'HH:mm[:ss]`. A DST-gap time → `400`; an overlap → the earlier offset. **All other date-times** (filters,
  responses) are ISO-8601 **with an offset**; event schedule responses are rendered in the venue zone plus a `timeZone` field.
- **Limits (config `ticketing.booking`):** hold duration `PT10M`; default max tickets per customer `8`; platform cap `50`;
  expiry job interval `PT30S`.
- **Concurrency rules** (spec rules 1, 3–7, 9) are binding:
  - Status and counter changes are conditional `UPDATE`s that bump `version` and check the row count.
  - GA counters change only through those queries, never by load-modify-save of a `TicketType`.
  - Ticket types are touched in id order.
  - Lock order is user → event (shared) → booking → ticket types.
  - No external calls inside a transaction.
- Events are created directly as `ON_SALE` with `seating_mode = 'GA'` (lifecycle in M5, reserved seating in M3).
- Commands use Git Bash (`./mvnw`); integration tests need Docker running.

## Review Focus

1. **Quantities that overflow:** an item with `quantity: 2147483647`, or two items whose sum overflows `int`
   → `409 TICKET_LIMIT_EXCEEDED`, never a 500 or a negative total. Test in Task 5.
2. **Time zone given as an offset or alias** (`"+08:00"`, `"Z"`, `"GMT+8"`, `"Mars/Base"`) → `400 VALIDATION_FAILED`
   on `timeZone`. Only region ids from `ZoneId.getAvailableZoneIds()` are accepted. Test in Task 2.
3. **Someone else's ids:** creating an event on another organizer's venue, or reading, cancelling or paying another
   customer's booking → `404 NOT_FOUND` (never 403, so ids can't be probed). Tests in Tasks 3, 5 and 6.
4. **Sale-window boundaries:** a hold one minute before `saleStartsAt`, or at/after `endsAt`
   → `409 EVENT_NOT_ON_SALE`; a hold exactly at `saleStartsAt` succeeds. Test in Task 5.
5. **Acting on a booking that already moved:**
   - Cancel or pay a booking the expiry job already expired → `409 BOOKING_NOT_PENDING`.
   - Cancel the same booking twice → `409 BOOKING_NOT_PENDING`.
   - The `held` counter is released exactly once.

   Tests in Task 6.

---

## File Structure

```
src/main/resources/db/migration/
  V2__create_venues.sql, V3__create_events.sql, V4__create_bookings.sql, V5__create_tickets.sql
src/main/java/dev/kbrr/ticketing/
  common/   ApiException (modify: properties), ErrorCode (modify), GlobalExceptionHandler (modify),
            Money.java, SchedulingConfig.java
  country/  CountryProperties.java, CountryController.java, CountryResponse.java
  venue/    Venue.java, VenueRepository.java, VenueService.java, VenueController.java,
            VenueRequest.java, VenueResponse.java
  event/    Event.java, EventStatus.java, EventCategory.java, TicketType.java, EventRepository.java,
            TicketTypeRepository.java, EventSpecs.java, VenueTime.java, EventService.java,
            OrganizerEventController.java, PublicEventController.java,
            CreateEventRequest.java, UpdateEventRequest.java, TicketTypeRequest.java,
            EventResponse.java, TicketTypeResponse.java, AvailabilityResponse.java, DevEventSeeder.java
  booking/  Booking.java, BookingStatus.java, BookingType.java, BookingItem.java, Ticket.java, TicketStatus.java,
            BookingRepository.java, TicketRepository.java, BookingProperties.java, TicketCodes.java,
            BookingService.java, ExpiryJob.java, BookingController.java, FakePaymentController.java,
            HoldRequest.java, BookingResponse.java, TicketResponse.java
  auth/SecurityConfig.java (modify), user/UserRepository.java (modify: findForUpdateById)
src/test/java/dev/kbrr/ticketing/
  IntegrationTest.java (modify: import TestFixtures, TestClockConfiguration), TestFixtures.java,
  MutableClock.java, TestClockConfiguration.java
  common/GlobalExceptionHandlerTest.java (modify), country/CountryTest.java, venue/VenueTest.java,
  event/VenueTimeTest.java, event/OrganizerEventTest.java, event/PublicEventTest.java,
  booking/HoldTest.java, booking/HoldConcurrencyTest.java, booking/ConfirmAndExpiryTest.java
```

---

### Task 1: Field errors on `ApiException`, country config, `GET /api/countries`

**Files:**
- Modify: `common/ApiException.java`, `common/GlobalExceptionHandler.java`, `auth/AuthService.java` (72-byte check uses
  `fieldError`), `auth/SecurityConfig.java`, `src/main/resources/application.yml`, `src/test/resources/application-test.yml`
- Create: `common/Money.java`, `country/CountryProperties.java`, `country/CountryController.java`, `country/CountryResponse.java`
- Test: `common/GlobalExceptionHandlerTest.java` (add 1), `country/CountryTest.java`

**Interfaces:**
- Produces:
  - `ApiException(ErrorCode code, String detail, Map<String, Object> properties)`; the existing 2-arg constructor
    delegates with an empty map; `Map<String, Object> properties()`.
    `static ApiException fieldError(String field, String message)` → `VALIDATION_FAILED`, detail
    `"Request validation failed"`, property `errors = [{field, message}]` (same shape as bean validation).
  - `record Money(long amountMinor, String currency)`.
  - `@ConfigurationProperties("ticketing") record CountryProperties(Map<String, Country> countries)`;
    nested `record Country(boolean enabled, String currency, String paymentProvider, long minChargeMinor)`.
    Its compact constructor checks `Currency.getInstance(currency)` and `minChargeMinor ≥ 1`, so a bad config fails startup.
    Methods:
    - `Optional<Country> enabled(String code)`: present only if the country is configured and enabled.
    - `Country get(String code)`: any configured country, enabled or not; used for existing venues. Missing → `IllegalStateException`.
  - `GET /api/countries` (public) → `List<CountryResponse>`, enabled countries only, sorted by code.
    `record CountryResponse(String code, String currency, int currencyFractionDigits, long minChargeMinor)`.

- [x] **Step 1: Write the failing tests**

```java
// GlobalExceptionHandlerTest: add probe GET /probe/field-error → throws ApiException.fieldError("startsAt", "does not exist in zone")
@Test void apiExceptionCarriesFieldErrors() throws Exception {
    mvc.perform(get("/probe/field-error")).andExpect(status().isBadRequest())
       .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
       .andExpect(jsonPath("$.errors[0].field").value("startsAt"))
       .andExpect(jsonPath("$.errors[0].message").value("does not exist in zone"));
}
// CountryTest (@IntegrationTest) — test profile config: PH enabled, JP enabled (JPY), US disabled (USD)
@Test void listsEnabledCountriesWithoutToken() { /* GET /api/countries → 200; codes == ["JP","PH"];
   PH: currency "PHP", currencyFractionDigits 2, minChargeMinor 2000; JP: "JPY", 0 */ }
@Test void disabledCountryIsKnownButNotEnabled() { /* countryProperties.enabled("US") empty; get("US").currency() == "USD" */ }
@Test void invalidCurrencyFailsFast() { /* new CountryProperties.Country(true, "XXY", "x", 1) throws IllegalArgumentException */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='GlobalExceptionHandlerTest,CountryTest'` → compilation failure.

- [x] **Step 3: Implement**

- Handler: `handleApi` copies `e.properties()` onto the `ProblemDetail`.
- `application.yml`: add the PH entry from Global Constraints under `ticketing.countries`.
- `application-test.yml`: add
  `JP: {enabled: true, currency: JPY, payment-provider: fake, min-charge-minor: 50}` and
  `US: {enabled: false, currency: USD, payment-provider: fake, min-charge-minor: 50}`.
- `SecurityConfig`: `permitAll` for `GET /api/countries`.

- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS` (M1's 37 + 4 new).

- [x] **Step 5: Commit** — `git commit -m "feat: country config, countries endpoint, field errors on ApiException"`

**Done notes (Task 1):**
- Tests: 41 pass (37 + 4 new). Commit: see `git log` (feat: country config, countries endpoint, field errors on ApiException).
- No deviations. `CountryProperties` binds prefix `ticketing` with only `countries`; jwt/dev-seed keys are ignored. Null map is normalized to empty; map is copied immutably.
- Gotcha: `CountryController` and `CountryResponse` are package-private; `CountryProperties` and `Money` are public for use by later packages.
- `AuthService` 72-byte check now yields `errors[0].field == "password"`, detail "Request validation failed".


---

### Task 2: Venues

**Files:**
- Create: `db/migration/V2__create_venues.sql`, `venue/{Venue,VenueRepository,VenueService,VenueController,VenueRequest,VenueResponse}.java`,
  test `TestFixtures.java`
- Modify: `auth/SecurityConfig.java` (`/api/organizer/**` → `hasRole("ORGANIZER")`), `IntegrationTest.java` (`@Import` `TestFixtures`)
- Test: `venue/VenueTest.java`

**Interfaces:**
- Consumes: `CountryProperties.enabled`, `ApiException.fieldError`, `UserService`/`UserRepository`, `@IntegrationTest`.
- Produces:
  - `@Entity Venue`: `UUID id`, `UUID ownerId`, `name`, `address`, `city`, `countryCode`, `timeZone` (String), `@Version long version`.
  - `VenueRepository extends JpaRepository<Venue, UUID>`: `List<Venue> findByOwnerIdOrderByName(UUID ownerId)`,
    `Optional<Venue> findByIdAndOwnerId(UUID id, UUID ownerId)`.
  - `VenueService.create(UUID ownerId, VenueRequest req) -> VenueResponse`, `list(UUID ownerId) -> List<VenueResponse>`,
    `get(UUID ownerId, UUID id) -> VenueResponse` (not owned or missing → `NOT_FOUND "Venue not found"`),
    package-visible `Venue requireOwned(UUID ownerId, UUID id)` for Task 3.
  - `record VenueRequest(@NotBlank @Size(max=200) name, @NotBlank @Size(max=300) address, @NotBlank @Size(max=100) city,
    @NotBlank @Pattern(regexp="[A-Za-z]{2}") countryCode, @NotBlank timeZone)`. The compact constructor upper-cases
    `countryCode` with `Locale.ROOT`.
  - `record VenueResponse(UUID id, String name, String address, String city, String countryCode, String timeZone)`.
  - Endpoints (organizer): `POST /api/organizer/venues` → 201, `GET /api/organizer/venues`, `GET /api/organizer/venues/{id}`.
  - **Test fixture** `TestFixtures` (plain class, `@Import`ed by `@IntegrationTest`; constructor-injected repos, `JwtEncoder`, `Clock`):
    - `User customer()` and `User organizer()`: save a user with a unique `UUID@x.test` email.
    - `String bearer(User u)`: `"Bearer " + token` minted with `JwtEncoder`: `iss=ticketing`, `sub`, `roles`, `exp = now + 1h`.

Migration (exact):
```sql
create table venues (
    id           uuid primary key,
    owner_id     uuid         not null references users (id),
    name         varchar(200) not null,
    address      varchar(300) not null,
    city         varchar(100) not null,
    country_code char(2)      not null check (country_code = upper(country_code)),
    time_zone    varchar(64)  not null,
    version      bigint       not null default 0
);
create index venues_owner on venues (owner_id);
```

- [x] **Step 1: Write the failing tests** (`VenueTest`)

```java
@Test void organizerCreatesVenue() { /* POST {name,address,city,countryCode:"ph",timeZone:"Asia/Manila"} → 201,
   $.countryCode == "PH"; GET /api/organizer/venues lists it */ }
@Test void disabledCountry() { /* countryCode "US" → 400 VALIDATION_FAILED, errors[0].field == "countryCode" */ }
@Test void unknownCountry() { /* "ZZ" → 400 VALIDATION_FAILED, field countryCode */ }
@ParameterizedTest @ValueSource(strings = {"+08:00", "Z", "GMT+8", "Mars/Base", "asia/manila"})
void invalidTimeZone(String tz) { /* → 400 VALIDATION_FAILED, field timeZone */ }
@Test void customerCannotCreateVenue() { /* customer token → 403 FORBIDDEN */ }
@Test void otherOrganizersVenueIsNotFound() { /* organizer B GET A's venue id → 404 NOT_FOUND */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=VenueTest`.

- [x] **Step 3: Implement** the migration, entity, repository, service, controller and fixture.
  Validation in `VenueService.create`:
  - The country must be `enabled`, or `fieldError("countryCode", "country is not enabled")`.
  - `ZoneId.getAvailableZoneIds().contains(timeZone)`, or `fieldError("timeZone", "must be an IANA region id like Asia/Manila")`.
    This is case-sensitive: `asia/manila` is rejected.

- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.

- [x] **Step 5: Commit** — `git commit -m "feat: organizer venues with country and time zone"`

**Done notes (Task 2):**
- Commit: see `git log` (feat: organizer venues with country and time zone). Tests: 51 pass (41 + 10 in VenueTest, counting 5 parameterized time zones).
- Deviations: `VenueService.requireOwned` is public (Task 3 is in another package); `VenueService` is a public class, its other methods and `VenueRepository`/DTOs stay package-private. Entity needs `@Table(name = "venues")` (default name would be `venue`) and `@JdbcTypeCode(SqlTypes.CHAR)` on `countryCode` so `ddl-auto: validate` accepts the `char(2)` column. Tests were written together with the implementation, so no separate RED run.
- Gotchas: `TestFixtures` is public with a package-private constructor and is injectable in any test via `@IntegrationTest`; `customer()`/`organizer()` save users with a unique email and a `{noop}` password hash (not loginable, use `bearer(u)`). Disabled and unknown country codes both give VALIDATION_FAILED on `countryCode`.

---

### Task 3: Events with GA ticket types (organizer side)

**Files:**
- Create: `db/migration/V3__create_events.sql`, `event/{Event,EventStatus,EventCategory,TicketType,EventRepository,
  TicketTypeRepository,VenueTime,EventService,OrganizerEventController,CreateEventRequest,UpdateEventRequest,
  TicketTypeRequest,EventResponse,TicketTypeResponse}.java`, `booking/BookingProperties.java`
- Modify: `application.yml` (`ticketing.booking.*`), `TestFixtures.java` (add `venue`, `gaEvent`)
- Test: `event/VenueTimeTest.java` (plain unit test), `event/OrganizerEventTest.java`

**Interfaces:**
- Consumes: `VenueService.requireOwned`, `CountryProperties`, `Money`, `ApiException.fieldError`, `Clock`.
- Produces:
  - `@ConfigurationProperties("ticketing.booking") record BookingProperties(Duration holdDuration, int defaultMaxTicketsPerCustomer,
    int platformMaxTicketsPerCustomer, Duration expiryInterval)`; yml values `PT10M`, `8`, `50`, `PT30S`.
  - `enum EventStatus { DRAFT, PENDING_APPROVAL, REJECTED, APPROVED, ON_SALE, ENDED, CANCELLED }` (M2 only writes `ON_SALE`).
  - `enum EventCategory { CONCERT, CONFERENCE, WORKSHOP, FESTIVAL, SPORTS, THEATER, OTHER }`.
  - `@Entity Event`:
    - Fields: `id`, `organizerId`, `@ManyToOne(LAZY) Venue venue`, `currency`, `title`, `description`, `category`,
      `Instant startsAt`, `endsAt`, `saleStartsAt`, `seatingMode` (String `"GA"`), `maxTicketsPerCustomer`, `status`,
      `Instant createdAt`, `@Version version`.
    - Ticket types: `@OneToMany(mappedBy="event", cascade=ALL, orphanRemoval=true) @OrderBy("name") List<TicketType>`.
  - `@Entity TicketType`: `id`, `@ManyToOne(LAZY) Event event`, `name`, `long priceMinor`, `int capacity`, `int held`, `int sold`, `@Version version`.
  - `final class VenueTime` — `static Instant toInstant(LocalDateTime local, ZoneId zone, String field)`:
    - Empty `zone.getRules().getValidOffsets(local)` → `fieldError(field, "does not exist in " + zone + " (daylight-saving gap)")`.
    - Otherwise `local.atZone(zone).toInstant()` (the earlier offset on overlap).
    - `static OffsetDateTime toVenueTime(Instant t, ZoneId zone)`.
  - Requests:
    - `record TicketTypeRequest(@NotBlank @Size(max=100) String name, @NotNull @PositiveOrZero Long priceMinor, @NotNull @Min(1) @Max(1_000_000) Integer capacity)`.
    - `record CreateEventRequest(@NotNull UUID venueId, @NotBlank @Size(max=200) String title, @Size(max=5000) String description,
      @NotNull EventCategory category, @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt, @NotNull LocalDateTime saleStartsAt,
      Integer maxTicketsPerCustomer, @NotEmpty @Size(max=20) List<@Valid TicketTypeRequest> ticketTypes)`.
      The three schedule fields carry `@JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm[:ss]")`, so any offset or `Z` suffix is a parse error.
    - `record UpdateEventRequest(@NotBlank @Size(max=200) String title, @Size(max=5000) String description, @NotNull EventCategory category,
      @NotNull Integer maxTicketsPerCustomer)`.
  - Responses:
    - `record TicketTypeResponse(UUID id, String name, Money price, int capacity)`.
    - `record EventResponse(UUID id, String title, String description, EventCategory category, EventStatus status, String currency,
      OffsetDateTime startsAt, OffsetDateTime endsAt, OffsetDateTime saleStartsAt, String timeZone, UUID venueId, String venueName,
      String city, String countryCode, int maxTicketsPerCustomer, List<TicketTypeResponse> ticketTypes)`.
      Times are rendered in the venue zone.
  - `EventService.create(UUID organizerId, CreateEventRequest) -> EventResponse`, `update(UUID organizerId, UUID id, UpdateEventRequest) -> EventResponse`,
    `getOwned(UUID organizerId, UUID id) -> EventResponse`, `listOwned(UUID organizerId) -> List<EventResponse>`.
  - Endpoints: `POST /api/organizer/events` → 201; `PUT /api/organizer/events/{id}`; `GET /api/organizer/events`; `GET /api/organizer/events/{id}`.
  - `TestFixtures`:
    - `VenueResponse venue(User organizer, String countryCode, String timeZone)`.
    - `EventResponse gaEvent(User organizer, VenueResponse venue, int capacity, long priceMinor)`:
      one ticket type "Regular", sale started 1 hour ago, starts in 30 days, ends 30 days + 3 hours from now
      (venue-local, from the injected `Clock`), `maxTicketsPerCustomer = 8`.
    - Overload `gaEvent(User organizer, VenueResponse venue, int capacity, long priceMinor, int maxTickets,
      LocalDateTime saleStartsAt, LocalDateTime startsAt, LocalDateTime endsAt)` for tests that need another limit or sale window.

Migration (exact):
```sql
create table events (
    id                       uuid primary key,
    organizer_id             uuid         not null references users (id),
    venue_id                 uuid         not null references venues (id),
    currency                 char(3)      not null,
    title                    varchar(200) not null,
    description              varchar(5000),
    category                 varchar(20)  not null,
    starts_at                timestamptz  not null,
    ends_at                  timestamptz  not null,
    sale_starts_at           timestamptz  not null,
    seating_mode             varchar(10)  not null check (seating_mode in ('RESERVED', 'GA')),
    max_tickets_per_customer int          not null check (max_tickets_per_customer >= 1),
    status                   varchar(20)  not null check (status in ('DRAFT','PENDING_APPROVAL','REJECTED','APPROVED','ON_SALE','ENDED','CANCELLED')),
    created_at               timestamptz  not null,
    version                  bigint       not null default 0,
    check (sale_starts_at < starts_at and starts_at < ends_at)
);
create index events_on_sale_starts on events (starts_at) where status = 'ON_SALE';
create table ticket_types (
    id          uuid primary key,
    event_id    uuid         not null references events (id) on delete cascade,
    name        varchar(100) not null,
    price_minor bigint       not null check (price_minor >= 0),
    capacity    int          not null check (capacity >= 1),
    held        int          not null default 0 check (held >= 0),
    sold        int          not null default 0 check (sold >= 0),
    version     bigint       not null default 0,
    unique (event_id, name),
    check (held + sold <= capacity)   -- last line of defence against oversell
);
```

- [ ] **Step 1: Write the failing tests**

```java
// VenueTimeTest (unit)
@Test void convertsWithVenueZone() { /* 2026-12-31T20:00 in Asia/Manila → Instant 2026-12-31T12:00:00Z */ }
@Test void dstGapIsRejected() { /* 2027-03-14T02:30 in America/New_York → ApiException VALIDATION_FAILED, errors[0].field == given field */ }
@Test void dstOverlapTakesEarlierOffset() { /* 2026-11-01T01:30 America/New_York → 2026-11-01T05:30:00Z (EDT, -04:00) */ }

// OrganizerEventTest (@IntegrationTest)
@Test void createsOnSaleGaEventInVenueCurrencyAndZone() { /* PH venue Asia/Manila, startsAt "2026-12-31T20:00" → 201,
   $.status "ON_SALE", $.currency "PHP", $.startsAt "2026-12-31T20:00:00+08:00", $.timeZone "Asia/Manila",
   $.ticketTypes[0].price == {amountMinor: 150000, currency: "PHP"}, $.maxTicketsPerCustomer == 8 (default) */ }
@Test void jpyVenueGivesJpyEvent() { /* JP venue Asia/Tokyo, price 1500 → $.currency "JPY", price.amountMinor 1500 */ }
@Test void scheduleWithOffsetIsRejected() { /* startsAt "2026-12-31T20:00+08:00" → 400; and "2026-12-31T20:00Z" → 400 */ }
@Test void dstGapScheduleIsRejected() { /* venue America/New_York, startsAt "2027-03-14T02:30" → 400 VALIDATION_FAILED field startsAt */ }
@Test void scheduleOrderAndPast() { /* saleStartsAt >= startsAt → 400 field saleStartsAt; startsAt >= endsAt → 400 field endsAt;
   endsAt in the past → 400 field endsAt */ }
@ParameterizedTest @ValueSource(ints = {0, 51}) void ticketLimitOutOfRange(int v) { /* → 400 field maxTicketsPerCustomer */ }
@Test void priceBelowCountryMinimum() { /* PH price 1999 → 400 field "ticketTypes[0].priceMinor"; price 0 → 400 too (free tickets: M6) */ }
@Test void duplicateTicketTypeNames() { /* two "Regular" → 400 field "ticketTypes" */ }
@Test void otherOrganizersVenueIsNotFound() { /* → 404 NOT_FOUND */ }
@Test void updateChangesLimitAndText() { /* PUT {title:"New", category, description, maxTicketsPerCustomer: 2} → 200; GET shows them */ }
```

- [ ] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='VenueTimeTest,OrganizerEventTest'`.

- [ ] **Step 3: Implement** migration, entities, `VenueTime`, service and controller.
  `create` validates in this order and stops at the first failure, using `fieldError`:
  1. Venue owned (`404`).
  2. The venue's country is enabled (`venueId`, "country is not enabled").
  3. Limit null → default; outside `1..platformMax` → `400`.
  4. Unique ticket type names.
  5. Every price ≥ the country's `minChargeMinor`.
  6. Convert all three times with `VenueTime`.
  7. `saleStartsAt < startsAt < endsAt`, then `endsAt > now`.

  `currency` = the venue country's currency. Status `ON_SALE`. `update` applies the same limit validation.

- [ ] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.

- [ ] **Step 5: Commit** — `git commit -m "feat: organizer GA events with venue-local schedule and minor-unit prices"`

---

### Task 4: Public event browsing and availability

**Files:**
- Create: `event/{PublicEventController,EventSpecs,AvailabilityResponse}.java`
- Modify: `event/EventService.java`, `event/EventRepository.java` (`JpaSpecificationExecutor<Event>`), `auth/SecurityConfig.java`
- Test: `event/PublicEventTest.java`

**Interfaces:**
- Produces:
  - `GET /api/events?country=&city=&category=&from=&to=&page=0&size=20` (public) → `PagedModel<EventResponse>`.
    - Only `ON_SALE` events.
    - Sorted by `startsAt`, then `id`.
    - `page ≥ 0`; `1 ≤ size ≤ 100` (`@Min`/`@Max` on the params; the client can't choose a sort field).
    - `from`/`to` are `OffsetDateTime` with `@DateTimeFormat(iso = DATE_TIME)`; they filter `from ≤ startsAt < to`.
    - `country` is matched upper-cased, `city` case-insensitively.
  - `GET /api/events/{id}` (public) → `EventResponse`; anything not `ON_SALE` → `404` (full visibility rules in M5).
  - `GET /api/events/{id}/availability` (public) → `record AvailabilityResponse(UUID eventId, List<Item> ticketTypes)` with
    `record Item(UUID ticketTypeId, String name, Money price, int remaining)`, `remaining = capacity - held - sold`.
  - `EventSpecs`: one static `Specification<Event>` per filter, combined with `Specification.allOf`. Each one is a no-op when its param is null.
  - `EventService.search(filters, PageRequest) -> Page<EventResponse>`, `getPublic(UUID) -> EventResponse`, `availability(UUID) -> AvailabilityResponse`.
- `SecurityConfig`: `permitAll` for `GET /api/events`, `GET /api/events/*`, `GET /api/events/*/availability`.

- [ ] **Step 1: Write the failing tests** (`PublicEventTest`, no token)

```java
@Test void listFiltersByCountryCityCategoryAndTime() { /* create PH/Manila CONCERT and JP/Tokyo CONFERENCE events;
   ?country=ph → only PH; ?city=manila → only Manila; ?category=CONFERENCE → only JP;
   ?from=<PH startsAt with offset> → includes PH (inclusive); $.page.totalElements matches */ }
@Test void filterTimeWithoutOffsetIsRejected() { /* ?from=2026-12-01T00:00 → 400 */ }
@Test void pageSizeOverLimitIsRejected() { /* ?size=101 → 400 VALIDATION_FAILED */ }
@Test void getEventIsPublicAndCarriesZone() { /* GET /api/events/{id} → 200, $.timeZone, $.startsAt ends with "+08:00" */ }
@Test void unknownEventIs404() { /* random UUID → 404 NOT_FOUND; "not-a-uuid" → 400 */ }
@Test void availabilityShowsRemaining() { /* capacity 5 → remaining 5, price {amountMinor, currency} */ }
```

Note: the integration tests share one database, so list assertions must filter by something unique to the test
(e.g. a city name containing a UUID), not count all events.

- [ ] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=PublicEventTest`.

- [ ] **Step 3: Implement.** Spring MVC's built-in method validation handles the `@Min`/`@Max` params (M1 already maps
  `HandlerMethodValidationException` → `VALIDATION_FAILED`). A filter without an offset fails type conversion → `400`
  through the base handler.

- [ ] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.

- [ ] **Step 5: Commit** — `git commit -m "feat: public event search, details and GA availability"`

---

### Task 5: GA holds (`POST /api/bookings`), booking reads, concurrency tests

**Files:**
- Create: `db/migration/V4__create_bookings.sql`, `booking/{Booking,BookingStatus,BookingType,BookingItem,BookingRepository,
  BookingService,BookingController,HoldRequest,BookingResponse}.java`, test `MutableClock.java`, `TestClockConfiguration.java`
- Modify: `common/ErrorCode.java`, `user/UserRepository.java`, `event/EventRepository.java`, `event/TicketTypeRepository.java`,
  `IntegrationTest.java` (import `TestClockConfiguration`)
- Test: `booking/HoldTest.java`, `booking/HoldConcurrencyTest.java`

**Interfaces:**
- Consumes: `Event`, `TicketType`, `BookingProperties`, `CountryProperties.get`, `Money`, `TestFixtures`.
- Produces:
  - `ErrorCode` additions, all `409`: `EVENT_NOT_ON_SALE`, `TICKET_LIMIT_EXCEEDED`, `PENDING_HOLD_EXISTS`, `PREVIOUS_HOLD_RELEASING`,
    `TICKETS_UNAVAILABLE`, `BOOKING_NOT_PENDING`.
  - `enum BookingStatus { PENDING_PAYMENT, CONFIRMED, EXPIRED, CANCELLED }`, `enum BookingType { PURCHASE, COMP }`.
  - `@Entity Booking`:
    - Fields: `id`, `UUID userId`, `UUID eventId`, `type`, `status`, `long totalMinor`, `currency`, `Instant expiresAt`,
      `String paymentProvider`, `Instant createdAt`, `Instant cancelledAt`, `UUID cancelledBy`, `@Version version`.
    - Items: `@OneToMany(mappedBy="booking", cascade=ALL) List<BookingItem> items`.
  - `@Entity BookingItem`: `id`, `@ManyToOne(LAZY) Booking booking`, `UUID ticketTypeId`, `int quantity`, `long unitPriceMinor`.
  - Locks:
    - `UserRepository.findForUpdateById(UUID)`: `@Lock(PESSIMISTIC_WRITE)`.
    - `EventRepository.findForShareById(UUID)`: `@Lock(PESSIMISTIC_READ)`, which Postgres runs as `FOR SHARE`.
  - `TicketTypeRepository`. Each `@Modifying(flushAutomatically = true, clearAutomatically = true)` query sets `version = version + 1` and returns `int`:
    - `hold(UUID id, int q)` → `held = held + q where capacity - held - sold >= q`.
    - `confirm(UUID id, int q)` → `held = held - q, sold = sold + q where held >= q`.
    - `release(UUID id, int q)` → `held = held - q where held >= q`.
  - `BookingRepository`:
    - `int updateStatus(UUID id, BookingStatus expected, BookingStatus next)`: conditional, bumps `version`.
    - `long countActiveTickets(UUID userId, UUID eventId)`: sum of item quantities over `PURCHASE` bookings that are
      `PENDING_PAYMENT` or `CONFIRMED`; `coalesce` to 0.
    - `Optional<Booking> findPendingPurchase(UUID userId, UUID eventId)`.
    - `Page<Booking> findByUserIdAndTypeOrderByCreatedAtDesc(UUID, BookingType, Pageable)`.
  - `record HoldRequest(@NotNull UUID eventId, @NotEmpty @Size(max=20) List<@Valid Item> items)`.
    Nested `record Item(@NotNull UUID ticketTypeId, @NotNull @Min(1) Integer quantity)`.
    Duplicate `ticketTypeId`s → `fieldError("items", "duplicate ticket type")`.
  - `record BookingResponse(UUID id, UUID eventId, BookingType type, BookingStatus status, List<Item> items, Money total,
    Instant expiresAt, Instant createdAt, String checkoutUrl)`, nested `record Item(UUID ticketTypeId, int quantity, Money unitPrice)`.
    `checkoutUrl` is always null until M4.
  - `BookingService.hold(UUID userId, HoldRequest req) -> BookingResponse` (`@Transactional`), `get(UUID userId, UUID id)`,
    `list(UUID userId, int page, int size) -> Page<BookingResponse>`.
    Someone else's booking → `404`.
  - Endpoints:
    - `POST /api/bookings` → `201`.
    - `GET /api/bookings` → `PagedModel<BookingResponse>`; own `PURCHASE` bookings, newest first; same page/size rules as Task 4.
    - `GET /api/bookings/{id}`.
  - Test clock:
    - `MutableClock extends Clock`, starting at `Instant.now()`, UTC; `void advance(Duration d)`.
    - `TestClockConfiguration` declares it as a `@Primary` `Clock` bean.
    - Tests only advance it forward, and by less than 1 hour, so tokens stay valid.

Migration (exact):
```sql
create table bookings (
    id               uuid primary key,
    user_id          uuid        not null references users (id),
    event_id         uuid        not null references events (id),
    type             varchar(10) not null check (type in ('PURCHASE', 'COMP')),
    status           varchar(20) not null check (status in ('PENDING_PAYMENT','CONFIRMED','EXPIRED','CANCELLED')),
    total_minor      bigint      not null check (total_minor >= 0),
    currency         char(3)     not null,
    expires_at       timestamptz not null,
    payment_provider varchar(30),
    created_at       timestamptz not null,
    cancelled_at     timestamptz,
    cancelled_by     uuid references users (id),
    version          bigint      not null default 0
);
create unique index bookings_one_pending_purchase on bookings (user_id, event_id)
    where status = 'PENDING_PAYMENT' and type = 'PURCHASE';
create index bookings_pending_expiry on bookings (expires_at) where status = 'PENDING_PAYMENT';
create index bookings_user_created on bookings (user_id, created_at desc);
create table booking_items (
    id               uuid primary key,
    booking_id       uuid   not null references bookings (id) on delete cascade,
    ticket_type_id   uuid   not null references ticket_types (id),
    quantity         int    not null check (quantity >= 1),
    unit_price_minor bigint not null check (unit_price_minor >= 0),
    unique (booking_id, ticket_type_id)
);
```

**`hold` algorithm** (spec flow 2, GA path; the order is the point):
1. `users.findForUpdateById(userId)`.
2. `events.findForShareById(eventId)`. Missing or not `ON_SALE` → `404`. `now < saleStartsAt` or `now ≥ endsAt` → `EVENT_NOT_ON_SALE`.
3. Load the requested ticket types. Any missing, or belonging to another event → `404 "Ticket type not found"`.
4. Requested total: sum the quantities as `long` (`Math.addExact`). `countActiveTickets + requested > event.maxTicketsPerCustomer`
   → `TICKET_LIMIT_EXCEEDED`. This check runs before any arithmetic on prices, which removes the overflow risk.
5. `findPendingPurchase`:
   - `expiresAt ≤ now` → `PREVIOUS_HOLD_RELEASING`, detail `"Your previous hold is being released, retry in a few seconds"`.
   - Otherwise → `PENDING_HOLD_EXISTS`.
6. For each item, sorted by `ticketTypeId`: `ticketTypes.hold(id, q)`. Collect the ids that return 0.
   If any → throw `ApiException(TICKETS_UNAVAILABLE, "Not enough tickets left", Map.of("unavailable", ids))`,
   which rolls back the holds already taken.
7. Save the `Booking`:
   - `PURCHASE`, `PENDING_PAYMENT`, `expiresAt = now + holdDuration`.
   - `currency` = the event's currency.
   - `totalMinor` = Σ `multiplyExact(price, q)`.
   - `paymentProvider` = `countries.get(venue.countryCode).paymentProvider()`.
   - Items with unit-price snapshots.

- [ ] **Step 1: Write the failing tests**

`HoldTest` (`@IntegrationTest`, MockMvc):
```java
@Test void holdsGaTickets() { /* capacity 10, price 150000, hold 2 → 201, status PENDING_PAYMENT,
   total {300000,"PHP"}, expiresAt == clock.now + 10m; availability remaining == 8 */ }
@Test void jpyTotalInMinorUnits() { /* JP event price 1500, hold 3 → total {4500, "JPY"} */ }
@Test void ticketTypeOfAnotherEventIs404() { /* item ticketTypeId from event B, eventId A → 404 NOT_FOUND */ }
@Test void invalidQuantitiesAndDuplicates() { /* quantity 0 → 400; quantity -1 → 400; same ticketTypeId twice → 400; items [] → 400 */ }
@Test void overflowingQuantity() { /* quantity Integer.MAX_VALUE → 409 TICKET_LIMIT_EXCEEDED; two types each MAX_VALUE → 409, not 500 */ }
@Test void notEnoughTickets() { /* capacity 2, hold 3 (limit 8) → 409 TICKETS_UNAVAILABLE, $.unavailable[0] == ticketTypeId; remaining still 2 */ }
@Test void saleWindowBoundaries() { /* event with saleStartsAt = now + 1 min → 409 EVENT_NOT_ON_SALE; advance clock exactly 1 min → 201;
   event with startsAt = now + 1 min, endsAt = now + 2 min: advance 2 min (now == endsAt) → 409 EVENT_NOT_ON_SALE */ }
@Test void perCustomerLimit() { /* limit 3: hold 4 → 409 TICKET_LIMIT_EXCEEDED */ }
@Test void secondPendingHold() { /* hold 1 then hold 1 again → 409 PENDING_HOLD_EXISTS */ }
@Test void rebookRightAfterExpiry() { /* hold, advance clock 10m + 1s (job not run) → hold again → 409 PREVIOUS_HOLD_RELEASING */ }
@Test void loweringLimitBlocksFurtherBuysOnly() { /* limit 8, hold 4 (pending), organizer PUT limit 2 → booking still PENDING_PAYMENT
   (GET → 200); a different customer holding 3 → 409 TICKET_LIMIT_EXCEEDED, holding 2 → 201 */ }
@Test void othersBookingIs404() { /* customer B GET A's booking → 404 */ }
@Test void listShowsOwnBookingsOnly() { /* A has 1, B has 1 → A's list has exactly A's */ }
```

`HoldConcurrencyTest` (`@IntegrationTest`; calls `BookingService` **directly** with an `ExecutorService` and a
`CountDownLatch` start gate, not MockMvc, so it stresses the database locks; the test class is NOT `@Transactional`):
```java
@Test void gaNeverOversells() { /* capacity 10, 30 different customers each hold 1 at once →
   exactly 10 succeed, 20 get ApiException TICKETS_UNAVAILABLE; ticket type held == 10, sold == 0 */ }
@Test void oneCustomerParallelHoldsRespectLimit() { /* limit 3, one customer, 8 threads each hold 2 →
   exactly 1 succeeds (others PENDING_HOLD_EXISTS or TICKET_LIMIT_EXCEEDED); countActiveTickets == 2 */ }
```

- [ ] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='HoldTest,HoldConcurrencyTest'`.

- [ ] **Step 3: Implement** the migration, entities, queries, service and controller following the algorithm above.

- [ ] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`. Run the concurrency test 3 times to check it isn't flaky.

- [ ] **Step 5: Commit** — `git commit -m "feat: GA holds with per-customer limit and oversell-proof counters"`

---

### Task 6: Fake payment confirm, tickets, cancel, expiry job

**Files:**
- Create: `db/migration/V5__create_tickets.sql`, `booking/{Ticket,TicketStatus,TicketRepository,TicketCodes,TicketResponse,
  ExpiryJob,FakePaymentController}.java`, `common/SchedulingConfig.java`
- Modify: `booking/BookingService.java`, `booking/BookingRepository.java`, `booking/BookingController.java`, `application.yml`,
  `application-dev.yml`, `application-test.yml`
- Test: `booking/ConfirmAndExpiryTest.java`

**Interfaces:**
- Produces:
  - `@Entity Ticket`: `id`, `UUID bookingId`, `UUID ticketTypeId`, `code`, `TicketStatus status` (`VALID | VOID | USED`), `Instant issuedAt`.
  - `final class TicketCodes`: `static String newCode()`. 16 bytes from a static `SecureRandom`,
    `Base64.getUrlEncoder().withoutPadding()`, giving 22 chars.
  - `BookingRepository.findForUpdateById(UUID)` (`@Lock(PESSIMISTIC_WRITE)`);
    `List<UUID> findExpiredPendingIds(Instant now, Pageable limit)`, which returns `PENDING_PAYMENT` bookings with `expiresAt < now`, oldest first.
  - `BookingService`:
    - `confirmFake(UUID userId, UUID bookingId) -> BookingResponse`.
    - `cancel(UUID userId, UUID bookingId) -> BookingResponse`.
    - `expire(UUID bookingId) -> boolean`, where true means this call expired it.
    - `tickets(UUID userId, UUID bookingId) -> List<TicketResponse>`.
    - All `@Transactional`.
  - `record TicketResponse(UUID id, String code, UUID ticketTypeId, String ticketTypeName, TicketStatus status, Instant issuedAt)`.
  - Endpoints:
    - `DELETE /api/bookings/{id}` → `200 BookingResponse` (`CANCELLED`).
    - `GET /api/bookings/{id}/tickets`.
    - `POST /api/bookings/{id}/fake-pay` → `200 BookingResponse` (`CONFIRMED`). It exists only when
      `ticketing.fake-payments.enabled=true` (`@ConditionalOnBooleanProperty` on the controller): `true` in the dev and
      test yml, absent elsewhere. Remove it in M4.
  - `ExpiryJob`: `@Scheduled(fixedDelayString = "${ticketing.booking.expiry-interval}")`. It calls
    `findExpiredPendingIds(now, 100)` and then `bookingService.expire(id)` for each id, each in its own transaction.
    It logs each failure and continues.
  - `SchedulingConfig`: `@EnableScheduling`, active unless `ticketing.scheduling.enabled=false`
    (`@ConditionalOnBooleanProperty(name = "ticketing.scheduling.enabled", matchIfMissing = true)`). The test yml sets it
    to `false`, so tests call `expire` / `ExpiryJob.run()` themselves with the `MutableClock`.

Migration `V5__create_tickets.sql` (exact):
```sql
create table tickets (
    id             uuid primary key,
    booking_id     uuid        not null references bookings (id),
    ticket_type_id uuid        not null references ticket_types (id),
    code           varchar(32) not null unique,
    status         varchar(10) not null check (status in ('VALID', 'VOID', 'USED')),
    issued_at      timestamptz not null
);
create index tickets_booking on tickets (booking_id);
```

**Algorithms** (lock order user → event → booking → ticket types; counters in ticket-type id order):
- `confirmFake`:
  1. Read the booking. Not the caller's → `404`.
  2. Lock the user, shared-lock the event, then `findForUpdateById`.
  3. Status ≠ `PENDING_PAYMENT` → `BOOKING_NOT_PENDING`. Late-payment rules arrive in M4. Past `expiresAt` but not yet
     expired still confirms (spec rule 4).
  4. `updateStatus(PENDING_PAYMENT → CONFIRMED)` must return 1.
  5. `ticketTypes.confirm` for each item must return 1, else `IllegalStateException` (rolls back).
  6. Insert one `VALID` ticket per unit.
- `cancel`: owner check (`404`) → lock user → `findForUpdateById` → `updateStatus(PENDING_PAYMENT → CANCELLED)`.
  0 rows → `BOOKING_NOT_PENDING`. Then set `cancelledAt` / `cancelledBy` and `release` each item.
- `expire`: `updateStatus(PENDING_PAYMENT → EXPIRED)`. 0 rows → return false (it was confirmed or cancelled meanwhile).
  Otherwise `release` each item (must return 1) and return true.

- [ ] **Step 1: Write the failing tests** (`ConfirmAndExpiryTest`)

```java
@Test void fakePayConfirmsAndIssuesTickets() { /* hold 3 → fake-pay → 200 CONFIRMED; ticket type held 0, sold 3;
   GET tickets → 3, all VALID, codes distinct, each 22 chars and Base64.getUrlDecoder().decode(code).length == 16 */ }
@Test void ticketCodesAreUnique() { /* 1000 × TicketCodes.newCode() → set size 1000 */ }
@Test void expiryJobReleasesHold() { /* hold 2, advance 10m+1s, expiryJob.run() → booking EXPIRED, held 0, remaining back to capacity;
   rebook now → 201 */ }
@Test void payOrCancelAfterExpiry() { /* expired booking: fake-pay → 409 BOOKING_NOT_PENDING; DELETE → 409; held stays 0 */ }
@Test void cancelReleasesOnce() { /* hold 2, DELETE → 200 CANCELLED, held 0; DELETE again → 409 BOOKING_NOT_PENDING; held 0 */ }
@Test void expireSkipsConfirmed() { /* confirmed booking: expire(id) → false, status stays CONFIRMED, sold unchanged */ }
@Test void othersCannotPayOrCancel() { /* customer B fake-pay / DELETE A's booking → 404 */ }
@Test void limitCountsConfirmedTickets() { /* limit 3, hold 2 + fake-pay, then hold 2 → 409 TICKET_LIMIT_EXCEEDED, hold 1 → 201 */ }
@Test void payRaceWithExpiry() { /* hold, advance past expiry; run fake-pay and expire in parallel (latch) 20 times on fresh bookings →
   each booking ends CONFIRMED (sold = q, held 0) or EXPIRED (held 0, sold 0); never both, never negative */ }
```

- [ ] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=ConfirmAndExpiryTest`.

- [ ] **Step 3: Implement** following the algorithms above.

- [ ] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.

- [ ] **Step 5: Commit** — `git commit -m "feat: fake payment confirm, ticket codes, cancel and hold expiry job"`

---

### Task 7: Dev sample data, README, manual check

**Files:**
- Create: `event/DevEventSeeder.java`
- Modify: `README.md` (status: milestone 2 ✅; "Try it" curl walk-through)
- Test: none new. The seeder is dev-only; it's verified by the manual check.

**Interfaces:**
- `DevEventSeeder implements ApplicationRunner`, `@Profile("dev")`, `@Order` after `DevUserSeeder` (give both explicit
  `@Order` values). If `organizer@example.test` owns no venue, it creates:
  - Venue "Sample Arena", Manila, PH, `Asia/Manila`.
  - An `ON_SALE` GA event "Sample Concert" (sale started now, starts in 30 days), built through `EventService.create`
    so it is validated like any other event.
  - Ticket types "VIP" (`500000`, capacity 50) and "Regular" (`150000`, capacity 500).

- [ ] **Step 1: Implement `DevEventSeeder`.**

- [ ] **Step 2: Run all tests** — `./mvnw test` → `BUILD SUCCESS` (the seeder isn't active in `test`).

- [ ] **Step 3: Manual end-to-end check** (`docker compose up -d`, `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`):
  1. Log in as `customer@example.test`.
  2. `GET /api/events?country=PH` lists "Sample Concert".
  3. `GET …/availability` shows Regular = 500.
  4. `POST /api/bookings` 2 × Regular → `PENDING_PAYMENT`.
  5. `POST /api/bookings/{id}/fake-pay` → `CONFIRMED`.
  6. `GET …/tickets` → 2 codes.
  7. Availability shows Regular = 498.

  Restarting the app creates no second sample event.

- [ ] **Step 4: Update README.** Mark milestone 2 ✅, change the status line to "milestone 3 next", and add the
  walk-through above as a short "Try it" list.

- [ ] **Step 5: Commit** — `git commit -m "feat: dev sample event, README walk-through for milestone 2"`

---

## Decisions made in this plan (spec left them open or M2 narrows them)

- **Events are created `ON_SALE` with their ticket types in one request.** The spec's separate
  `PUT …/ticket-types` belongs to the draft lifecycle (M5). Before then, ticket types would be editable while on sale,
  which the spec forbids. *Trade-off:* M5 reshapes the create endpoint.
- **`PUT /api/organizer/events/{id}` edits only title, description, category and limit:** the fields the spec keeps
  editable after approval. `needs_review` and the client-sent version check (stale-edit 409) come with M5.
- **Venues:** create, list and get only. Update and delete depend on "used by a non-draft event" rules (M5).
- **Free tickets (price 0) are rejected until M6:** a free booking needs the same-transaction confirm path.
- **Booking cancel (`DELETE`) is included:** it is the expiry release with a different target status, so it costs little
  and covers Review Focus 5.
- **Fake payment** is a property-gated endpoint (`ticketing.fake-payments.enabled`), not a profile, so dev and test both
  get it and nothing else does. It's removed in M4.
- **Booking ↔ user/event are plain UUID columns, not `@ManyToOne`.** That avoids lazy loading across aggregates;
  `Event → Venue` and `Booking → items` stay as JPA relations because they're read together.
- **Paging params are `page`/`size` only:** a client-chosen sort could name an unknown property and turn into a 500.
- **`ticket_types` has a `held + sold <= capacity` check constraint:** the conditional `UPDATE` is the real guard, and the
  constraint turns any bug in it into a failed transaction instead of an oversell.
- **Scheduling is off in tests:** tests drive the expiry job with `MutableClock`, so no background thread races them.
- **Event categories:** `CONCERT, CONFERENCE, WORKSHOP, FESTIVAL, SPORTS, THEATER, OTHER`.
