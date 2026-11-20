# Milestone 3 — Reserved Seating Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Organizers give venues a seat layout (sections × rows × seats) and create **reserved** events whose sections are priced
through ticket types; customers see a seat map, hold specific seats (never the same seat twice), pay via the fake endpoint and get one
ticket per seat; cancel and expiry return seats to sale.

**Architecture:**
- **Venue layout:** `Section` and `Seat` rows belong to a venue (the `venue` package).
- **Event inventory:** a reserved event gets one `EventSeat` row per venue seat, generated with a single `INSERT … SELECT` when the
  event is created. Each `EventSeat` row is the inventory unit, with status `AVAILABLE` | `HELD` | `SOLD` and the current `booking_id`.
- **Seat locking (spec rule 2):** seats are locked with `SELECT … ORDER BY id … FOR UPDATE`, and every path that touches them does
  this first. Then a guarded bulk `UPDATE` changes them, and its row count is checked.
- **Bookings:** `BookingService` picks the GA path (M2's ticket-type counters) or the reserved path (a new `SeatInventory`) based on
  the event's seating mode. A booking's seats are kept in `booking_seats` and never deleted.

**Tech Stack:** as M2 (Java 25, Spring Boot 4.1.2, Hibernate 7, Postgres 16, Flyway, Testcontainers).

**Spec:** [docs/superpowers/specs/2026-11-19-event-ticketing-design.md](../specs/2026-11-19-event-ticketing-design.md)
(milestone 3 row; "Domain model" → Venue / Section / Seat / TicketType (TicketTypeSection) / EventSeat / BookingSeat / Ticket;
"Concurrency rules" 2, 4, 7; flow 2 reserved path; flows 3–5 for seats; API outline `…/sections`, `…/availability`; "Testing → M3").
Builds on M2: read the **Done notes** in `docs/superpowers/plans/2026-11-20-milestone-2-events-ga-holds.md` first.

## Global Constraints

- Everything in M1/M2 Global Constraints still holds: UUID ids, DTOs only, `ProblemDetail` + `code`, Flyway-only schema, injected
  `Clock`, conditional updates with checked row counts, no external calls in a transaction, and the M2 code style.
- **Seat lock order:** every path that changes `event_seats` first locks the affected rows with a JPQL
  `… order by es.id` query under `@Lock(PESSIMISTIC_WRITE)`, so Postgres takes the locks in its own uuid order. Rules:
  - **Never** sort seat ids in Java for locking. (Ticket types keep M2's Java `UUID.compareTo` order; each resource has exactly one order.)
  - Global order: user → event (shared) → booking → seats / ticket types.
- **Seat state changes** are guarded bulk updates (spec rule 2):
  - hold `AVAILABLE → HELD` (sets `booking_id`);
  - confirm `HELD → SOLD` where `booking_id = :b`;
  - release `HELD → AVAILABLE` (clears `booking_id`) where `booking_id = :b`.

  A row count different from the expected seat count → `IllegalStateException` (rollback), except for the hold's
  "already taken" case, which is detected before updating (see Task 4).
- In reserved mode the ticket-type `held` / `sold` counters are **not used**; `event_seats` is the only inventory. A reserved ticket
  type's `capacity` = number of seats in its sections (informational).
- **Layout limits:**
  - a section has 1–100 rows and 1–200 seats per row, at most 5,000 seats;
  - a venue has at most 20,000 seats in total;
  - row labels are 1–10 chars `[A-Za-z0-9]`, upper-cased, unique within the section.
- **Sections are locked** once any event uses the venue: adding a section → `409 VENUE_IN_USE` (spec: layout can't change once a
  non-draft event uses it; M2/M3 events are all `ON_SALE`).
- A hold or comp request sends **either** `items` (GA) **or** `eventSeatIds` (reserved), never both, never neither → `400`.
  Seats / ticket types that don't belong to the event, or don't match its seating mode → `404`.
- Integration tests share one DB and one `MutableClock`: use fresh users / venues / events per test, and timeouts on every
  concurrent wait (`await(10, SECONDS)` asserted true, `get(30, SECONDS)`).
- Commands use Git Bash (`./mvnw`); integration tests need Docker running.

## Review Focus

1. **The same seat twice, or seats in any order:**
   - `eventSeatIds: [s1, s1]` → `400` (duplicate).
   - Two customers holding `[s1, s2]` and `[s2, s1]` at once never deadlock: one gets `201`, the other `409 TICKETS_UNAVAILABLE`.
   - Tests in Task 4.
2. **Wrong request shape:**
   - Both `items` and `eventSeatIds`, or neither → `400`.
   - `eventSeatIds` on a GA event, or `items` on a reserved event → `404`.
   - A seat id from another event → `404`.
   - Tests in Task 4.
3. **Layout changing under an event:** adding a section to a venue an event already uses → `409 VENUE_IN_USE`. A section added
   while an event is being created must not leave that event with an unmapped section; both operations lock the venue row.
   Tests in Tasks 1 and 2.
4. **Bad section mapping:** a reserved event with a section left unmapped, a section mapped twice, or a section of another venue
   → `400` with a field error. A GA ticket type without capacity, or a reserved one with capacity → `400`. Test in Task 2.
5. **An expired / cancelled / paid hold racing a new hold on the same seats:** no deadlock, no seat ends up `HELD` without a
   pending booking, and each seat is sold at most once. Tests in Task 5.

---

## File Structure

```
src/main/resources/db/migration/
  V6__create_sections_and_seats.sql, V7__create_event_seats.sql, V8__create_booking_seats.sql
src/main/resources/application.yml (modify: hibernate jdbc batching)
src/main/java/dev/kbrr/ticketing/
  common/ErrorCode.java (modify: VENUE_IN_USE)
  venue/    Section.java, Seat.java, SectionRepository.java, SeatRepository.java, SectionService.java,
            SectionController.java, SectionRequest.java, SectionResponse.java,
            VenueRepository.java + VenueService.java (modify: requireOwnedForUpdate)
  event/    SeatingMode.java, TicketTypeSection.java, TicketTypeSectionRepository.java, EventSeat.java, EventSeatStatus.java,
            EventSeatRepository.java, SeatMapRow.java,
            Event.java, TicketTypeRequest.java, CreateEventRequest.java, EventService.java, EventResponse.java,
            TicketTypeResponse.java, AvailabilityResponse.java, EventRepository.java, DevEventSeeder.java (modify)
  booking/  SeatInventory.java, HoldRequest.java, Booking.java, BookingService.java, BookingResponse.java, Ticket.java,
            TicketResponse.java (modify)
src/test/java/dev/kbrr/ticketing/
  TestFixtures.java (modify: section, reservedEvent; update existing request constructors)
  venue/SectionTest.java, event/ReservedEventTest.java, event/SeatMapTest.java,
  booking/ReservedHoldTest.java, booking/SeatConcurrencyTest.java, booking/ReservedConfirmAndExpiryTest.java
```

---

### Task 1: Sections and seats

**Files:**
- Create: `db/migration/V6__create_sections_and_seats.sql`, `venue/{Section,Seat,SectionRepository,SeatRepository,SectionService,
  SectionController,SectionRequest,SectionResponse}.java`
- Modify: `common/ErrorCode.java`, `venue/VenueRepository.java`, `venue/VenueService.java`, `application.yml`, `TestFixtures.java`
- Test: `venue/SectionTest.java`

**Interfaces:**
- Consumes: `VenueService.requireOwned`, `ApiException.fieldError`, `TestFixtures.organizer/venue/bearer`.
- Produces:
  - `ErrorCode.VENUE_IN_USE` (409).
  - Venue lock:
    - `VenueRepository.findForUpdateByIdAndOwnerId(UUID id, UUID ownerId)` with `@Lock(PESSIMISTIC_WRITE)`.
    - `public Venue VenueService.requireOwnedForUpdate(UUID ownerId, UUID id)`: missing or not owned → `404 "Venue not found"`.
  - `@Entity Section` (`sections`): `id`, `UUID venueId`, `name`.
  - `@Entity Seat` (`seats`): `id`, `UUID sectionId`, `rowLabel`, `int rowIndex` (0-based position in the request), `int seatNumber` (1-based).
  - `public interface SeatRepository`: `long countBySectionIdIn(Collection<UUID> sectionIds)`, `long countByVenueId(UUID venueId)`.
    The latter is a JPQL entity join over `Section` on `sectionId`.
  - `public interface SectionRepository`: `List<Section> findByVenueIdOrderByName(UUID venueId)`;
    `@Query("select count(e) > 0 from Event e where e.venue.id = :venueId") boolean venueHasEvents(UUID venueId)`.
    The query is a JPQL string, so `venue` code doesn't import the `event` package (no package cycle).
  - `record SectionRequest(@NotBlank @Size(max=100) String name, @NotEmpty @Size(max=100) List<@NotNull @Pattern(regexp="[A-Za-z0-9]{1,10}") String> rowLabels,
    @NotNull @Min(1) @Max(200) Integer seatsPerRow)`.
  - `record SectionResponse(UUID id, String name, List<String> rowLabels, int seatsPerRow, int seatCount)`.
  - Service: `SectionService.create(UUID ownerId, UUID venueId, SectionRequest) -> SectionResponse` and `list(UUID ownerId, UUID venueId) -> List<SectionResponse>`.
  - Endpoints: `POST /api/organizer/venues/{venueId}/sections` → `201`, `GET /api/organizer/venues/{venueId}/sections`.
  - `TestFixtures.section(User organizer, VenueResponse venue, String name, List<String> rows, int seatsPerRow) -> SectionResponse`.

Migration `V6__create_sections_and_seats.sql` (exact):
```sql
create table sections (
    id       uuid primary key,
    venue_id uuid         not null references venues (id),
    name     varchar(100) not null,
    unique (venue_id, name)
);
create table seats (
    id          uuid primary key,
    section_id  uuid        not null references sections (id),
    row_label   varchar(10) not null,
    row_index   int         not null check (row_index >= 0),
    seat_number int         not null check (seat_number >= 1),
    unique (section_id, row_label, seat_number)
);
```
`application.yml`: `spring.jpa.properties.hibernate.jdbc.batch_size: 100` and `hibernate.order_inserts: true`, so a
5,000-seat section inserts in batches.

**`SectionService.create`**, in this order:
1. `requireOwnedForUpdate`. The venue row lock serializes this with event creation (Task 2 takes the same lock).
2. `sections.venueHasEvents` → `VENUE_IN_USE` "Venue layout can't change once an event uses it".
3. Upper-case the row labels with `Locale.ROOT`. Duplicates → `fieldError("rowLabels", "row labels must be unique")`.
4. Name already used in this venue → `fieldError("name", "section name already exists")`.
5. `rows × seatsPerRow > 5000` → `fieldError("seatsPerRow", "a section has at most 5000 seats")`.
6. `countByVenueId + new > 20000` → `fieldError("seatsPerRow", "a venue has at most 20000 seats")`.
7. Save the section, then `saveAll` its seats.

- [x] **Step 1: Write the failing tests** (`SectionTest`, `@IntegrationTest`)

```java
@Test void createsSectionWithSeats() { /* POST {name:"Orchestra", rowLabels:["a","B"], seatsPerRow:10} → 201,
   $.rowLabels == ["A","B"], $.seatCount == 20; GET lists it; seatRepository.countBySectionIdIn([id]) == 20 */ }
@Test void duplicateRowLabelsAfterUpperCasing() { /* ["A","a"] → 400 field rowLabels */ }
@Test void duplicateSectionName() { /* same name twice in one venue → 400 field name */ }
@Test void sectionTooLarge() { /* 100 rows × 51 seats → 400 field seatsPerRow */ }
@Test void venueTooLarge() { /* 4 sections of 5000 then one more of 1 seat → 400 field seatsPerRow (20000 cap) */ }
@Test void invalidRowLabel() { /* "A-1" → 400 VALIDATION_FAILED */ }
@Test void venueInUse() { /* fixtures.gaEvent on the venue, then POST a section → 409 VENUE_IN_USE */ }
@Test void otherOrganizersVenue() { /* → 404 NOT_FOUND */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=SectionTest`.
- [x] **Step 3: Implement** the migration, entities, repositories, service, controller and fixture.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: venue sections and seats"`

**Done notes (Task 1):**
- Commit: see `git log` (feat: venue sections and seats). Tests: 109 pass (101 + 8 SectionTest). RED run was a compile failure (section code missing).
- Deviations: none in behavior. Sections store no row/seat-per-row columns, so `list` rebuilds `rowLabels`/`seatsPerRow` from seats via `SeatRepository.rowsOf` (one grouped JPQL query, ordered by `rowIndex`); it returns `List<Object[]>`.
- Gotchas: `requireOwnedForUpdate` must run inside a transaction (`SectionService.create` is `@Transactional`; Task 2 must do the same). `SectionRepository`/`SeatRepository`/entities are public. Batch settings live under `spring.jpa.properties.hibernate`.
- Interface: `TestFixtures.section(organizer, venue, name, rows, seatsPerRow)` calls the service directly; creating a section after `gaEvent` on that venue gives `VENUE_IN_USE`.
- Open questions: none.

---

### Task 2: Reserved events (section pricing, event seat generation)

**Files:**
- Create: `db/migration/V7__create_event_seats.sql`, `event/{SeatingMode,TicketTypeSection,TicketTypeSectionRepository,EventSeat,
  EventSeatStatus,EventSeatRepository}.java`
- Modify: `event/{Event,CreateEventRequest,TicketTypeRequest,EventService,EventResponse,TicketTypeResponse}.java`,
  `DevEventSeeder.java` and `TestFixtures.java` (constructor arity only), every test that builds these records
- Test: `event/ReservedEventTest.java`

**Interfaces:**
- Consumes: `VenueService.requireOwnedForUpdate`, `SectionRepository`, `SeatRepository.countBySectionIdIn`.
- Produces:
  - `enum SeatingMode { GA, RESERVED }`. `Event.seatingMode` becomes `@Enumerated(STRING) SeatingMode`; the column is unchanged.
  - Requests (new fields go **last**; existing callers pass `null`):
    - `CreateEventRequest`: new field `SeatingMode seatingMode`, where `null` means `GA`.
    - `TicketTypeRequest`: `capacity` loses `@NotNull` (keeps `@Min(1) @Max(1_000_000)`); new field
      `@Size(max=50) List<@NotNull UUID> sectionIds`.
  - Responses:
    - `EventResponse`: new field `SeatingMode seatingMode`.
    - `TicketTypeResponse`: new field `List<UUID> sectionIds`, empty for GA.
  - `@Entity TicketTypeSection` (`ticket_type_sections`): `id`, `UUID eventId`, `UUID ticketTypeId`, `UUID sectionId`.
    `TicketTypeSectionRepository.findByEventId(UUID)`.
  - `enum EventSeatStatus { AVAILABLE, HELD, SOLD }`.
  - `@Entity EventSeat` (`event_seats`): `id`, `UUID eventId`, `UUID seatId`, `UUID ticketTypeId`, `EventSeatStatus status`,
    `UUID bookingId`. There is no `@Version`; changes go only through Task 4/5 bulk queries.
  - `public interface EventSeatRepository`:
    - `@Modifying @Query(nativeQuery = true) int generate(UUID eventId)`. It inserts one `AVAILABLE` row per seat of every
      section mapped in `ticket_type_sections` for the event, with id `gen_random_uuid()`.
    - `long countByEventIdAndStatus(UUID eventId, EventSeatStatus status)`.

Migration `V7__create_event_seats.sql` (exact):
```sql
create table ticket_type_sections (
    id             uuid primary key,
    event_id       uuid not null references events (id) on delete cascade,
    ticket_type_id uuid not null references ticket_types (id) on delete cascade,
    section_id     uuid not null references sections (id),
    unique (event_id, section_id)
);
create table event_seats (
    id             uuid primary key,
    event_id       uuid        not null references events (id) on delete cascade,
    seat_id        uuid        not null references seats (id),
    ticket_type_id uuid        not null references ticket_types (id),
    status         varchar(10) not null check (status in ('AVAILABLE', 'HELD', 'SOLD')),
    booking_id     uuid        references bookings (id),
    unique (event_id, seat_id),
    check ((status = 'AVAILABLE') = (booking_id is null))
);
create index event_seats_booking on event_seats (booking_id) where booking_id is not null;
```

**`EventService.create` changes:**
- Take the venue with `requireOwnedForUpdate` instead of `requireOwned` (Review Focus 3).
- Mode `GA` (or null): every ticket type needs `capacity` → else `fieldError("ticketTypes[i].capacity", "required for general admission")`.
  `sectionIds` must be null or empty → else `fieldError("ticketTypes[i].sectionIds", "only for reserved seating")`.
- Mode `RESERVED`:
  - The venue has ≥ 1 section → else `fieldError("venueId", "venue has no sections")`.
  - Every ticket type has `capacity == null` → else `fieldError("ticketTypes[i].capacity", "must be omitted for reserved seating")`.
  - Every ticket type has a non-empty `sectionIds` → else `fieldError("ticketTypes[i].sectionIds", "required for reserved seating")`.
  - Each section id belongs to the venue → else `fieldError("ticketTypes[i].sectionIds", "unknown section")`.
  - No section appears twice across all ticket types → else `fieldError("ticketTypes", "each section belongs to exactly one ticket type")`.
  - Every venue section is mapped → else `fieldError("ticketTypes", "every section must be mapped to a ticket type")`.
  - The ticket type's `capacity` = `countBySectionIdIn(its sections)`.
- All other M2 validation is unchanged and runs in the same order; the reserved checks run after the price check.
- After `saveAndFlush` of the event (so ticket type ids exist): save the `TicketTypeSection` rows, flush, then `generate(eventId)`.
  The returned count must equal `countByVenueId` → else `IllegalStateException`.

- [x] **Step 1: Write the failing tests** (`ReservedEventTest`)

```java
@Test void createsReservedEventWithEventSeats() { /* venue with sections Orchestra (A,B × 10) and Balcony (C × 5);
   RESERVED, ticket types Premium→[Orchestra] 250000, Standard→[Balcony] 120000 → 201, $.seatingMode "RESERVED",
   Premium capacity 20, sectionIds [orchestraId]; eventSeatRepository.countByEventIdAndStatus(id, AVAILABLE) == 25 */ }
@Test void unmappedSection() { /* only Orchestra mapped → 400 field ticketTypes */ }
@Test void sectionMappedTwice() { /* Orchestra in both types → 400 field ticketTypes */ }
@Test void sectionOfAnotherVenue() { /* → 400 field "ticketTypes[0].sectionIds" */ }
@Test void venueWithoutSections() { /* RESERVED on a venue with no sections → 400 field venueId */ }
@Test void capacityRulesPerMode() { /* RESERVED with capacity → 400 "ticketTypes[0].capacity";
   GA without capacity → 400 "ticketTypes[0].capacity"; GA with sectionIds → 400 "ticketTypes[0].sectionIds" */ }
@Test void gaStillDefault() { /* request without seatingMode → $.seatingMode "GA" (existing fixtures keep working) */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=ReservedEventTest`.
- [x] **Step 3: Implement.** Add `null` for the new trailing fields at every existing `new CreateEventRequest(...)` /
  `new TicketTypeRequest(...)` call (8 call sites across main and test).
  Add `TestFixtures.reservedEvent(User organizer, VenueResponse venue, Map<String, List<UUID>> sectionsByTypeName, long priceMinor) -> EventResponse`:
  every type gets that price, with the same schedule as `gaEvent`.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 5: Commit** — `git commit -m "feat: reserved events with section pricing and generated event seats"`

**Done notes (Task 2):**
- Tests: 116 total (7 new in `ReservedEventTest`), all green. Commit: see `git log` (feat: reserved events with section pricing and generated event seats).
- Deviations: `EventResponse.from(Event, Map<ticketTypeId, sectionIds>)` now takes the section mapping; `EventService.respond(Event)` loads it (one extra query, reserved events only) and every service read path uses it. Lists of reserved events therefore do one extra query per event (fine for now).
- Reserved validation lives in private `validateGa` / `validateReserved`, which also return capacity by ticket type name. `Event` constructor gained a `SeatingMode` arg. `TestFixtures.reservedEvent` added.
- Gotchas: `EventSeat` is read-only from JPA (no setters, no `@Version`); `generate` is a native insert and does not flush or clear, so tests/services should not rely on a stale persistence context. Section ids are not checked for existence beyond venue membership; a venue with no sections is rejected before any per-type check.
- Open questions: none.

---

### Task 3: Seat-map availability

**Files:**
- Create: `event/SeatMapRow.java`
- Modify: `event/AvailabilityResponse.java`, `event/EventService.java`, `event/EventSeatRepository.java`
- Test: `event/SeatMapTest.java`

**Interfaces:**
- Produces:
  - `AvailabilityResponse(UUID eventId, SeatingMode seatingMode, List<Item> ticketTypes, List<Section> sections)`.
    - GA:
      - `ticketTypes` as in M2.
      - `sections` is an empty list.
    - Reserved:
      - Each `Item.remaining` = `AVAILABLE` seats of that type.
      - `sections` = `record Section(UUID sectionId, String name, UUID ticketTypeId, Money price, List<SeatView> seats)`,
        `record SeatView(UUID eventSeatId, String row, int number, EventSeatStatus status)`.
      - Sections are ordered by name, seats by `rowIndex` then `number`.
  - `EventSeatRepository.findSeatMap(UUID eventId) -> List<SeatMapRow>`: a JPQL constructor projection joining `EventSeat`,
    `Seat` (on `seatId`) and `Section` (on `sectionId`), ordered by section name, row index, seat number.
    `record SeatMapRow(UUID eventSeatId, UUID sectionId, String sectionName, String rowLabel, int rowIndex, int seatNumber,
    EventSeatStatus status, UUID ticketTypeId)`.
  - Implementation note: put `// shortcut: returns every seat at once; split per section before using 10k+ seat layouts.` on `findSeatMap`.

- [x] **Step 1: Write the failing tests** (`SeatMapTest`, no token)

```java
@Test void reservedSeatMap() { /* reserved event (Orchestra A,B × 3 → Premium 250000; Balcony C × 2 → Standard 120000) →
   GET availability: $.seatingMode "RESERVED"; sections[0].name "Balcony" (name order), seats size 2, seats[0] {row "C", number 1, status "AVAILABLE"};
   sections[1].price {250000,"PHP"}; ticketTypes Premium remaining 6 */ }
@Test void seatOrderFollowsRowIndexNotLabel() { /* rows ["B","A"] → first seat row "B" */ }
@Test void gaAvailabilityUnchanged() { /* GA event → $.seatingMode "GA", sections [], remaining as before */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=SeatMapTest`.
- [x] **Step 3: Implement.**
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS` (check M2's `PublicEventTest` availability assertions).
- [x] **Step 5: Commit** — `git commit -m "feat: reserved seat-map availability"`

**Done notes (Task 3):**
- Commit: see `git log` (feat: reserved seat-map availability). Tests: 119 total (3 new in `SeatMapTest`), all green; M2 availability assertions untouched and passing.
- Deviations: none. Added a `TestFixtures.reservedEvent` overload taking a `Map<String, Long>` price per type name; the original delegates to it.
- Gotchas: `availability` groups rows by section in one pass (rows already ordered by section name, row index, number); GA path unchanged apart from the two new fields. `SeatMapRow` is a public record for Task 5.
- Open questions: none.


---

### Task 4: Reserved holds

**Files:**
- Create: `db/migration/V8__create_booking_seats.sql`, `booking/SeatInventory.java`
- Modify: `booking/{HoldRequest,Booking,BookingService,BookingResponse}.java`, `event/EventSeatRepository.java`
- Test: `booking/ReservedHoldTest.java`, `booking/SeatConcurrencyTest.java`

**Interfaces:**
- Consumes: M2 `BookingService.hold` structure (user lock → event share lock → pending check → limit check), `EventSeatRepository`.
- Produces:
  - `HoldRequest(@NotNull UUID eventId, @Size(max=20) List<@NotNull @Valid Item> items, @Size(max=50) List<@NotNull UUID> eventSeatIds)`.
    `items` loses `@NotEmpty`. Exactly one of the two lists must be non-empty → else
    `fieldError("items", "send either items (general admission) or eventSeatIds (reserved seating)")`.
    Duplicate seat ids → `fieldError("eventSeatIds", "duplicate seat")`.
  - `Booking`: `@ElementCollection @CollectionTable(name = "booking_seats", joinColumns = @JoinColumn(name = "booking_id"))
    @Column(name = "event_seat_id") Set<UUID> eventSeatIds`; `void addSeat(UUID eventSeatId)`. It is never cleared (spec: a permanent record).
  - `BookingResponse`: new trailing field `List<UUID> eventSeatIds`, sorted, empty for GA.
  - `EventSeatRepository`:
    - Locking, both `@Lock(PESSIMISTIC_WRITE)` and `order by es.id`:
      - `List<EventSeat> lockByIds(Collection<UUID> ids)`: `where es.id in :ids`.
      - `List<EventSeat> lockByBookingId(UUID bookingId)`: `where es.bookingId = :bookingId` (used by Task 5).
    - Guarded bulk updates. Each is `@Modifying(flushAutomatically = true, clearAutomatically = true)` and returns `int`:
      - `markHeld(Collection<UUID> ids, UUID bookingId)`: `status = HELD, bookingId = :bookingId where id in :ids and status = AVAILABLE`.
      - `markSold(UUID bookingId)`: `status = SOLD where bookingId = :b and status = HELD`.
      - `markAvailable(UUID bookingId)`: `status = AVAILABLE, bookingId = null where bookingId = :b and status = HELD`.
    - `EventRepository.findSeatingModeById(UUID eventId) -> Optional<SeatingMode>` (projection).
  - `SeatInventory` (package-private `@Component`, `booking` package), every method `@Transactional(propagation = MANDATORY)`:
    - `List<EventSeat> lockForHold(UUID eventId, Set<UUID> seatIds)`: `lockByIds`. Size ≠ requested, or any seat with another
      `eventId` → `404 "Seat not found"`. Returns the locked seats.
    - `void hold(Collection<UUID> seatIds, UUID bookingId)`: `markHeld` must equal `seatIds.size()` → else `IllegalStateException`.
    - `confirm(UUID bookingId, int expected)` and `release(UUID bookingId, int expected)`: implemented in Task 5.

Migration `V8__create_booking_seats.sql` (exact):
```sql
create table booking_seats (
    booking_id    uuid not null references bookings (id) on delete cascade,
    event_seat_id uuid not null references event_seats (id),
    primary key (booking_id, event_seat_id)
);
alter table tickets add column event_seat_id uuid references event_seats (id);
```

**Reserved branch of `hold`.** Steps 1–3 are shared with GA. Validate the request shape before any lock.
1. Lock the user; shared-lock the event (`ON_SALE`, sale window). An event in the wrong mode for the request → `404`
   ("Seat not found" for seats on a GA event, "Ticket type not found" for items on a reserved event).
2. Pending check, then limit check with `requested = eventSeatIds.size()`. This is M2's order, unchanged.
3. `seatInventory.lockForHold`. Any locked seat not `AVAILABLE` → `ApiException(TICKETS_UNAVAILABLE, "Some seats are no longer available",
   Map.of("unavailable", <those seat ids>))`. Nothing has been changed yet, so the rollback is trivial.
4. Build the `Booking`: one `BookingItem` per ticket type with `quantity` = its seat count and that type's `priceMinor`
   (read the ticket types before any bulk update). Call `addSeat` for each seat, then `saveAndFlush`. The booking row must exist
   before `markHeld` because of the `booking_id` FK; a brand-new row can't be contended.
5. `seatInventory.hold(seatIds, booking.getId())`; re-read the booking for the response.

The GA branch is unchanged. The GA path's ticket-type counter code must not run for reserved events.

- [x] **Step 1: Write the failing tests**

`ReservedHoldTest` (`@IntegrationTest`, MockMvc):
```java
@Test void holdsSeats() { /* hold 2 Premium seats → 201, total {500000,"PHP"}, items [{Premium, 2}], eventSeatIds == the 2 ids;
   seat map shows them HELD; Premium remaining 4 */ }
@Test void mixedTypesTotal() { /* 1 Premium + 1 Standard → total 370000, 2 items */ }
@Test void requestShape() { /* items + eventSeatIds → 400; neither → 400; [s1, s1] → 400 field eventSeatIds */ }
@Test void modeMismatchAndForeignSeat() { /* eventSeatIds on a GA event → 404; items on a reserved event → 404;
   seat of another reserved event → 404 */ }
@Test void takenSeat() { /* A holds s1; B holds [s1, s2] → 409 TICKETS_UNAVAILABLE, $.unavailable == [s1]; s2 still AVAILABLE */ }
@Test void seatLimit() { /* limit 2, hold 3 seats → 409 TICKET_LIMIT_EXCEEDED */ }
@Test void pendingRuleAppliesToSeats() { /* hold s1, then hold s2 → 409 PENDING_HOLD_EXISTS */ }
```

`SeatConcurrencyTest` (service-level, like M2's `HoldConcurrencyTest`; timeouts everywhere):
```java
@Test void sameSeatExactlyOneWins() { /* 20 customers hold the same seat at once → exactly 1 success, 19 TICKETS_UNAVAILABLE;
   the seat is HELD with the winner's booking id */ }
@Test void overlappingSetsNeverDeadlock() { /* 4 seats; 20 customers each hold a random pair, in shuffled order, at once →
   every outcome is success or TICKETS_UNAVAILABLE (any other exception — e.g. a deadlock — fails the test);
   HELD seats == 2 × successes; no seat is in two bookings' booking_seats with status PENDING_PAYMENT */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest='ReservedHoldTest,SeatConcurrencyTest'`.
- [x] **Step 3: Implement** as above.
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`; run `SeatConcurrencyTest` 3 times.
- [x] **Step 5: Commit** — `git commit -m "feat: reserved seat holds with ordered row locks"`

**Done notes (Task 4):**
- Commit: `a274ead` (feat: reserved seat holds with ordered row locks). Tests: 128 pass (119 + 7 ReservedHoldTest + 2 SeatConcurrencyTest); SeatConcurrencyTest ran green 3 extra times. RED run was a compile failure (3-arg `HoldRequest`, `eventSeatIds` missing).
- Lock SQL checked with `-Dspring.jpa.show-sql=true`: `lockByIds` emits `... where es1_0.id in (...) order by es1_0.id for no key update of es1_0`.
- Deviations: rulings applied: `EventRepository.findSeatingModeById` added (JPQL projection, unused until Task 5); `seatLimit` lowers the limit through the organizer `PUT` endpoint, fixtures unchanged. Pending + limit checks moved into a private `checkPendingAndLimit` and the booking construction into `newPurchase`, shared by both branches; GA order (ticket-type 404 → pending → limit) unchanged. The mode check sits right after the sale-window check, so items on a reserved event are 404 before any ticket-type lookup or counter. GA callers of `HoldRequest` in tests now pass `null` as the third arg.
- Gotchas for Task 5: (1) `markSold`/`markAvailable`/`lockByBookingId` exist but are untested; their row counts must be checked in `SeatInventory.confirm/release`. (2) Every `EventSeatRepository` bulk update clears the persistence context: read `EventSeat`s (ticket type, seat id) and the booking's seat count before calling it, and re-read the booking afterwards. (3) `Booking.eventSeatIds` is a lazy `@ElementCollection`; its size is the `expected` count. (4) `lockForHold` locks foreign-event seats briefly before its 404 (rolled back right away).
- Interface: `BookingResponse.eventSeatIds` is sorted by Java `UUID` order for display only (not a lock order). `SeatInventory` is package-private with `MANDATORY` methods `lockForHold`, `hold`. `tickets.event_seat_id` column exists but `Ticket` doesn't map it yet (`ddl-auto: validate` allows extra columns).
- Open questions: none.

---

### Task 5: Confirm, cancel and expire for reserved bookings

**Files:**
- Modify: `booking/{SeatInventory,BookingService,Ticket,TicketResponse,TicketRepository}.java`, `event/EventSeatRepository.java`
- Test: `booking/ReservedConfirmAndExpiryTest.java`

**Interfaces:**
- Produces:
  - `SeatInventory`:
    - `confirm(UUID bookingId, int expected)`: `lockByBookingId`, then `markSold`.
    - `release(UUID bookingId, int expected)`: `lockByBookingId`, then `markAvailable`.
    - In both, a row count ≠ `expected` → `IllegalStateException`. `expected` = the booking's seat count.
  - `Ticket`: new field `UUID eventSeatId` (nullable) and constructor `Ticket(UUID bookingId, UUID ticketTypeId, UUID eventSeatId, String code, Instant issuedAt)`.
    The old 4-arg constructor passes `null`.
  - `TicketResponse`: new trailing fields `UUID eventSeatId, String section, String row, Integer number`, all null for GA.
    Filled from `EventSeatRepository.findSeatLabels(Collection<UUID> eventSeatIds) -> List<SeatMapRow>` (same projection as Task 3).
  - `BookingService.confirmFake / cancel / expire` choose GA or reserved through `events.findSeatingModeById(booking.eventId)`.
    - The lock order is unchanged: user → event (share) → booking → seats. Expire is booking → seats.
    - Reserved confirm issues one `Ticket` per seat with its `ticketTypeId` and `eventSeatId`. The `EventSeat` rows must be read
      (inside `confirm`, after locking) before `markSold` clears the persistence context.

- [x] **Step 1: Write the failing tests** (`ReservedConfirmAndExpiryTest`)

```java
@Test void fakePayConfirmsSeatsAndIssuesSeatTickets() { /* hold 2 seats → fake-pay → CONFIRMED; both seats SOLD with booking id;
   GET tickets → 2, each with eventSeatId, section "Orchestra", row "A", number 1/2, distinct codes */ }
@Test void expiryReturnsSeats() { /* hold 2, advance 10m+1s, expiryJob.run() → EXPIRED, both seats AVAILABLE, booking_id null;
   eventSeatIds still listed on the booking (history kept); another customer can now hold them → 201 */ }
@Test void cancelReturnsSeatsOnce() { /* hold, DELETE → CANCELLED, seats AVAILABLE; DELETE again → 409 BOOKING_NOT_PENDING */ }
@Test void soldSeatCannotBeHeld() { /* after fake-pay, another customer holding that seat → 409 TICKETS_UNAVAILABLE */ }
@Test void payRaceWithExpiry() { /* 20 fresh reserved bookings of 2 seats: fake-pay vs expire in parallel →
   each ends CONFIRMED (both seats SOLD, 2 tickets) or EXPIRED (both seats AVAILABLE, 0 tickets); never mixed */ }
@Test void expireWhileOthersGrabSameSeats() { /* booking X holds [s1,s2]; advance past expiry; in parallel run expire(X) and
   10 customers holding [s2,s1] → no exception besides TICKETS_UNAVAILABLE / PREVIOUS_HOLD_RELEASING / PENDING_HOLD_EXISTS;
   finally every seat is AVAILABLE or HELD by exactly one PENDING_PAYMENT booking; X is EXPIRED */ }
```

- [x] **Step 2: Run to verify they fail** — `./mvnw test -Dtest=ReservedConfirmAndExpiryTest`.
- [x] **Step 3: Implement.**
- [x] **Step 4: Run all tests** — `./mvnw test` → `BUILD SUCCESS`. Run `ReservedConfirmAndExpiryTest` and `SeatConcurrencyTest` 3 times each.
- [x] **Step 5: Commit** — `git commit -m "feat: confirm, cancel and expire reserved seat bookings"`

**Done notes (Task 5):**
- Commit: `ebadb97` (feat: confirm, cancel and expire reserved seat bookings). Tests: 134 pass (128 + 6 ReservedConfirmAndExpiryTest). ReservedConfirmAndExpiryTest and SeatConcurrencyTest each ran green 3 extra times. RED run: 5 failures + 1 error, all from the GA counter path throwing `IllegalStateException` on reserved bookings (the gap Task 4's review found).
- Deviations: `SeatInventory.confirm` returns the locked `List<EventSeat>` (the brief left the return type open) and also checks the locked row count equals `expected`, besides the `markSold`/`markAvailable` count. `cancel` and `expire` now share one `release(Booking)` that re-reads the booking after the status update and branches on `findSeatingModeById`. `BookingService` also injects `EventSeatRepository` for `findSeatLabels` (read-only, not through `SeatInventory`, which owns writes).
- ExpiryJob isolation checked by reading, not changed: the job is not transactional, each `service.expire(id)` goes through the proxy in its own transaction, and a `RuntimeException` is logged and the loop continues.
- Gotchas: `Booking.eventSeatIds` must be sized before `updateStatus` / any seat bulk update clears the context (lazy collection). `findSeatLabels` is skipped when there are no seat tickets (GA). `TicketResponse` seat fields are null for GA.
- Interface: `Ticket` 5-arg constructor + `getEventSeatId`; `TicketResponse` gains `eventSeatId, section, row, number`; `EventSeatRepository.findSeatLabels(Collection<UUID>)`.
- Open questions: none.

---

### Task 6: Dev sample reserved event, README, manual check

**Files:**
- Modify: `event/DevEventSeeder.java`, `README.md`

**Interfaces:**
- `DevEventSeeder` seeds each sample venue independently. A venue is skipped if the organizer already has a venue with that
  name; existing dev databases with only "Sample Arena" then gain the theater.
  - Venue "Sample Theater" (Makati, PH, `Asia/Manila`) with sections:
    - Orchestra: rows A–E × 10.
    - Balcony: rows F–H × 12.
  - Event "Sample Play": RESERVED. "Orchestra" ticket type → Orchestra section at `250000`; "Balcony" ticket type → Balcony section at `120000`.
    The sale opened a minute ago and the event starts in 30 days.

- [x] **Step 1: Implement the seeder change.**
- [x] **Step 2: Run all tests** — `./mvnw test` → `BUILD SUCCESS`.
- [x] **Step 3: Manual check** (`docker compose up -d`, then run the app with the `dev` profile, then stop it afterwards):
  1. Log in as customer.
  2. `GET /api/events?country=PH` lists "Sample Play".
  3. Its availability shows 2 sections and 86 seats, all `AVAILABLE`.
  4. Hold 2 Orchestra seats → `PENDING_PAYMENT`.
  5. Fake-pay → `CONFIRMED`.
  6. Tickets → 2, with row / number.
  7. Availability shows those seats `SOLD`.

  A restart creates nothing new.
- [x] **Step 4: Update the README.** Milestone 3 ✅, status line "milestone 4 next", and a "Reserved seating" line in "Try it".
- [x] **Step 5: Commit** — `git commit -m "feat: dev sample reserved event, README for milestone 3"`

**Done notes (Task 6):**
- Commit: see `git log` (feat: dev sample reserved event, README for milestone 3). Tests: not runnable at the time (local Docker Desktop stopped publishing ports); verified later, see final review fixes.
- Manual check passed against the Compose Postgres: login, `GET /api/events?country=PH` lists both events, availability 2 sections / 86 seats all AVAILABLE, hold A1+A2 `PENDING_PAYMENT`, fake-pay `CONFIRMED`, 2 tickets with Orchestra A/1, A/2, availability 84 AVAILABLE + 2 SOLD (Orchestra remaining 48). Restart created nothing new. App stopped.
- Deviations: Sample Play uses category THEATER; the seeder now skips each venue by name (Arena logic moved into an `if`). No seeder test (dev-only, verified by the manual check).
- Gotchas: the hold request body is `{"eventId", "eventSeatIds"}`.
- Open questions: none.

**Done notes (final review fixes):**
- Fixed: reserved hold/confirm/expire/cancel tests now assert the ticket-type held+sold counters stay 0; cancel test asserts seats' booking_id is cleared; `lockByIds` is scoped to the event (the foreign-event check in `SeatInventory` became redundant and was removed; size mismatch is still a 404); README "Try it" lists `$PLAY`, `$SEAT1`, `$SEAT2` and says seat ids come from the availability `eventSeatId`.
- Verified: after a Docker Desktop restart the full suite ran green at 50850e5: 134 tests, 0 failures.
- Deferred to M4: pay-wins side of payRaceWithExpiry and a cancel-vs-hold race test; partial unique index on tickets(event_seat_id) where status <> 'VOID'; ExpiryJob poison-batch starvation.
- Deferred to M5: rowsOf Object[] projection; multi-section list and sections GET 404 tests; per-event and per-booking extra queries on list endpoints; non-transactional dev seeder.

---

## Decisions made in this plan (spec left them open or M3 narrows them)

- **Event seats are generated when the event is created:** M2 events start `ON_SALE`; M5 moves this to publish.
  They're generated with one `INSERT … SELECT gen_random_uuid()` because 20,000 JPA inserts would be slow and pointless.
- **Seats get one lock order, taken by Postgres:** every seat path locks with `ORDER BY id … FOR UPDATE` before updating,
  which covers expire / cancel / confirm too. The M2 review warned that Java's signed `UUID.compareTo` differs from
  Postgres uuid order. Ticket types keep the Java order: two resources, one order each.
- **The venue row lock** is taken by section creation and event creation only. No booking flow locks venues, so it can't form a
  cycle with the booking lock order.
- **Sections are locked once any event uses the venue,** GA events included. That's simpler than tracking which events need
  the layout, and matches the spec's "non-draft event uses the venue".
- **Reserved ticket types keep a `capacity`** (seat count) but never use `held` / `sold`; `event_seats` is the only inventory.
- **`booking_seats` is an `@ElementCollection` of seat ids:** it has no fields of its own, so it needs no entity.
- **`EventSeat` has no `@Version`:** it's never saved as an entity, only changed by guarded bulk updates (spec rule 9 lists Event,
  Venue, TicketType and Booking).
- **The seat map shows raw status** (`AVAILABLE` / `HELD` / `SOLD`) as the spec says. A held seat may come back, so the UI can say so.
- **Row order** is the order the organizer listed the rows in (`row_index`), not alphabetical ("10" vs "9", "AA" vs "B").
- **Section update / delete is not built:** sections lock as soon as an event uses the venue, and before that the organizer
  can just make a new venue. Deferred to M5 with venue edit.
