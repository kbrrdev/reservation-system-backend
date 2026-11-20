# Event Ticketing Backend — Design

Date: 2026-11-19 · Status: **Approved** (2026-11-20)

## Intent

A personal project to learn Java Spring Boot by building a realistic backend:
a ticketing system for **any kind of event, worldwide** — concerts,
conferences, workshops, festivals, sports. Organizers create events (an admin
approves them), customers buy tickets and pay, and high-demand sales go through
a fair waiting room.

**The Philippines is the first launch country.** The model is country-aware from day one (currency, time zone,
payment provider per country), but only PH is enabled and only its payment provider (PayMongo) is implemented.
Adding a country later means a config entry plus, if needed, one new payment-provider adapter — no schema changes.

Because the goal is learning, the system follows common Spring Boot practices
rather than clever shortcuts, and puts the effort into the hard parts of
ticketing: never overselling, verified payment, and fairness under load.
It is built in **milestones** (see the end), each a working slice that teaches one area of Spring.

## Goals

- Roles: every user is a `CUSTOMER`; `ORGANIZER` and `ADMIN` are extra roles on top (an organizer can buy tickets with the same account).
- Organizers create venues and events; an admin approves or rejects each event before it can go on sale.
- Two seating modes per event:
  - **Reserved** — customer picks specific seats from the venue's seat layout.
  - **General admission (GA)** — customer picks a ticket type (e.g. "VIP", "Regular") and a quantity.
- Customers hold tickets for 10 minutes while they pay; at most the organizer-set `max_tickets_per_customer` per event.
- Each event sells in its venue country's currency through that country's payment provider.
  First provider: PayMongo **test mode** for the Philippines (GCash, Maya, cards — no real money).
- A paid booking is confirmed only by a verified provider webhook, and gets one ticket (unique code) per seat/unit.
- Unpaid holds expire automatically and the inventory goes back on sale.
- **Free ticket types** (price 0) for free events — confirmed immediately, no payment step.
- **Complimentary tickets (comps)**: organizers issue free tickets for their own events to guests, sponsors, press.
- Optional per-event **waiting room**: buyers join a line, see their position, and are admitted in batches.
- Organizers see a simple sales summary and an attendee list for their own events, and can check in a ticket by its code.
- Admins see bookings that need a manual refund.
- Whole stack runs with `docker compose up`.
- Concurrency tests prove no seat and no GA unit is ever sold twice / oversold.

## Non-goals

- A frontend. Backend API only; exercised via Swagger UI / HTTP client.
- Real money movement: automated refunds, chargebacks, organizer payouts or fees. (Refunds are only *flagged*.)
- Mixed seating in one event (an event is either reserved or GA).
- Microservices, Kafka, WebSockets. (A live seat map over WebSockets is a possible later upgrade.)
- Email/SMS (including delivering comp tickets — the organizer gets the ticket codes and shares them),
  QR images, a scanner app, resale, promo codes, dynamic pricing. (Check-in is one API call with the code.)
- Taxes and fees: ticket prices are final, inclusive of local taxes (e.g. PH VAT / amusement tax); nothing is
  added at checkout. Per-country tax rules are a later concern.
- Currency conversion, or one event selling in several currencies. An event has exactly one currency.
- Translated content. Event text is stored as the organizer typed it; API error messages are English, but every
  error carries a stable machine-readable `code` so a frontend can translate it.
- Country-specific data-privacy workflows (GDPR, PH Data Privacy Act data export / deletion).
- Countries other than PH, and payment providers other than PayMongo — designed for, not built.
- Login rate limiting / account lockout. Passwords: minimum 8 characters, BCrypt; nothing more.
- Bot protection / CAPTCHA / per-IP rate limiting on the queue.
- Refresh tokens / logout. Access-token-only JWT is enough here.
- Cloud deployment. Local Docker only.
- Organizer verification (KYC). Any user can register as an organizer; event approval is the gate.

## Anti-goals

- **Overselling** — a seat sold twice, or more GA units sold than capacity. Failure even if all else works.
- Confirming a booking with a non-zero total without a verified payment webhook.
- A waiting room that can be skipped by calling the booking API directly.
- Money owed to a customer that only exists in a log line.
- Over-engineered layering that hides the Spring concepts being learned.

## Approaches considered

### Overall structure

| Approach | Trade-off | Verdict |
|---|---|---|
| **A. Single Spring Boot app; inventory (holds) in Postgres** with row locks / conditional updates and a scheduled expiry job | One app; teaches JPA, transactions, locking, security, testing | **Chosen** |
| B. Holds in Redis (TTL keys) | Closer to big ticketing sites, but two sources of truth for inventory | Rejected |
| C. Microservices + Kafka | Distributed plumbing swamps the booking logic | Rejected |

### Waiting room

| Approach | Trade-off | Verdict |
|---|---|---|
| **A. Redis sorted-set queue + client polling** | Standard tool for this; high-frequency position checks stay off Postgres | **Chosen** |
| B. Queue table in Postgres | One less service, but thousands of pollers hit the booking database | Rejected |
| Position updates via WebSockets instead of polling | Instant, but holds a connection per waiting user; harder to scale and to throttle | Rejected — polling is what Queue-it / Cloudflare Waiting Room do |

Postgres stays the single source of truth for inventory. Redis only holds queue
and admission state, which can be lost without causing an oversell.

Payment: a `PaymentGateway` interface with **one implementation per provider**; PayMongo (test mode) is the first.
Spring injects all implementations as a `Map<String, PaymentGateway>` keyed by bean name (`"paymongo"`), and the
event's country config names which one to use — no hand-written registry. Tests use a fake.

### Going worldwide

| Approach | Trade-off | Verdict |
|---|---|---|
| **A. Countries in config** (`ticketing.countries.PH: {currency, provider, min-charge}`), only PH enabled | Simple; adding a country needs a deploy anyway (new provider code / credentials) | **Chosen** |
| B. Countries table editable by admins | Runtime-editable, but a country is useless without provider code, so the flexibility is fake | Rejected |
| C. Stay PH-only, generalize later | Least work now, but money-as-centavos and `Asia/Manila` would be baked into every table | Rejected |

## Tech stack

- Java 25, Spring Boot 4.x (Spring Framework 7, Hibernate 7, Jackson 3), **Maven** (via the Maven wrapper)
- Spring Web, Spring Data JPA (Hibernate), Bean Validation
- PostgreSQL 16, Flyway migrations
- Redis 7, Spring Data Redis (`StringRedisTemplate`)
- Spring Security with stateless JWT auth (access token only)
- springdoc-openapi for Swagger UI
- JUnit 5, Testcontainers (Postgres + Redis), MockMvc
- Docker: multi-stage `Dockerfile` + `docker-compose.yml`

## Architecture

One Spring Boot application, organized **by feature**:

```
com.example.ticketing
├── auth        (register, login, JWT filter, SecurityConfig)
├── user
├── venue       (Venue, Section, Seat)
├── event       (Event, TicketType, EventSeat, approval workflow)
├── queue       (waiting room: join, position, admission job)
├── booking     (Booking, BookingItem, BookingSeat, Ticket; hold / confirm / expire / cancel)
├── country     (CountryProperties: enabled countries, currency, provider, min charge)
├── payment     (PaymentGateway interface, webhook controller; payment/paymongo = first adapter)
├── organizer   (organizer-facing endpoints, comps, sales summary)
├── admin       (approval, refund-required list)
└── common      (ProblemDetail error handling, Clock bean, config properties)
```

Controllers never return entities — they map to DTO records.
Errors are RFC 7807 `ProblemDetail` via `@RestControllerAdvice`.
Time comes from an injected `Clock` so expiry and queue logic are testable.

## Domain model

**Money** is a `long` amount in the currency's **minor units** plus an ISO 4217 currency code
(PHP 1,500.00 → `150000`; JPY 1,500 → `1500`, since yen has no minor unit). Minor-unit digits come from
`java.util.Currency.getDefaultFractionDigits()`, never hard-coded. Amounts in different currencies are never
added together; every total and report is per event, so per currency.

**Time:** timestamps are `timestamptz` in UTC. Each venue has an IANA time zone (`Asia/Manila`); event times are
shown in the venue's zone, and API responses include it.
- **Event schedule input** (`starts_at`, `ends_at`, `sale_starts_at`) is the **venue's local wall time without an
  offset** (`2026-12-31T20:00`). The server converts it with the venue's zone (`LocalDateTime.atZone(zone)`), so
  organizers never work out offsets or daylight saving time. A local time that doesn't exist (DST gap, e.g. 02:30
  on spring-forward night) → `400`; an ambiguous one (DST overlap) → the earlier offset. Changing a draft's venue
  re-interprets its local times in the new venue's zone.
- **All other date-times** (query filters, responses) are ISO-8601 **with an offset**
  (`2026-12-31T20:00:00+08:00`); input without one → `400`.

*shortcut: schedules are stored as UTC instants; if a government changes its time-zone rules after an event is
published, the local time shown shifts. Store local time + zone instead if that ever matters.*

**Countries** (config, not a table):
```yaml
ticketing.countries:
  PH: { enabled: true, currency: PHP, payment-provider: paymongo, min-charge-minor: <verify in PayMongo docs> }
```
`enabled` only controls **new** work in that country: creating venues and submitting events. Events already
approved or on sale keep selling, and their webhooks keep working, if a country is later disabled. A provider's
adapter must stay deployed while any booking still references it.

- **User** — id, email (unique, stored lower-cased), password hash (BCrypt), name, roles (set: `CUSTOMER` always,
  plus `ORGANIZER` and/or `ADMIN`). Registration can opt into `ORGANIZER`; `ADMIN` only via seed data.
- **Venue** — id, owner (organizer), name, address, city, `country_code` (ISO 3166-1 alpha-2), `time_zone` (IANA).
  Only its owner can use or edit it. Country must be enabled in config → else `400`; time zone must be a valid
  `ZoneId`. Country and time zone can't change once a non-draft event uses the venue.
- **Section** — id, venue, name. **Seat** — id, section, row label, seat number (unique per section/row/number).
  Only needed for reserved events. Sections and seats can't be edited once any non-draft event uses the venue.
- **Event** — id, organizer, venue, `currency` (follows the venue's country while `DRAFT` / `REJECTED`; locked from submit on),
  title, description, category, starts_at, ends_at,
  `seating_mode` (`RESERVED` | `GA`), `sale_starts_at`, `waiting_room_enabled`,
  `max_tickets_per_customer`, status, rejection_reason,
  `reviewed_by` / `reviewed_at` (admin approve/reject), `cancelled_by` / `cancelled_at`,
  `needs_review` (boolean; set when text is edited after approval, cleared by an admin).
  **Set by the organizer** when creating the event (optional; defaults to 8 from config), validated
  `1 ≤ value ≤ platform cap` (config, 50). This is the **only** ticket limit: it caps a single booking
  and a customer's total for the event.
- **TicketType** — id, event, name, `price_minor` (in the event's currency). Price is either `0` (free) or at least
  the country's `min-charge-minor`.
  - GA: `capacity`, `held`, `sold` counters (`sold` includes comps).
  - Reserved: priced per section through **TicketTypeSection** (event_id, ticket_type_id, section_id),
    unique on (event_id, section_id) — every section belongs to exactly one ticket type per event.
- **EventSeat** (reserved only) — id, event, seat, ticket_type, status (`AVAILABLE` | `HELD` | `SOLD`),
  booking_id (current holder/owner, nullable). Created for every seat when the event is published.
- **Booking** — id, user, event, `type` (`PURCHASE` | `COMP`),
  status (`PENDING_PAYMENT` | `CONFIRMED` | `EXPIRED` | `CANCELLED`), `total_minor`, `currency`, expires_at,
  `payment_provider` (e.g. `paymongo`; set **in the hold transaction** from the country config) and
  `payment_reference` (the provider's checkout id; saved after commit). Both null for free and comp bookings.
  `refund_required` (boolean), created_at, `cancelled_by` / `cancelled_at` (null when expired by the job).
  For `COMP`, `user` is the issuing organizer and `recipient_name` / `recipient_email` are stored.
- **BookingItem** — booking, ticket_type, quantity, `unit_price_minor` (price snapshot; both modes).
- **BookingSeat** — booking, event_seat. Permanent record of which seats a booking asked for;
  never cleared, so late payments can re-claim and history survives expiry.
- **Ticket** — id, booking, ticket_type, event_seat (nullable for GA), code (unique; 128 bits from `SecureRandom`,
  URL-safe Base64 — it works like a key at the door, so it must be unguessable), issued_at,
  status (`VALID` | `VOID` | `USED`), checked_in_at, checked_in_by.
  `VOID` when its comp is revoked or its event is cancelled; `USED` after check-in.
- **PaymentEvent** — provider, provider event id (unique together), type, amount_minor, currency, received_at, raw payload, booking_id (nullable — null
  means unmatched), `refund_handled` (for unmatched payments). Webhook idempotency + debugging.
  Inserted **in the same transaction** as the work it triggers (see flow 3).

Seed data (Flyway): one admin, one organizer, one customer, two sample venues
(one with a small seat layout), and one on-sale sample event of each seating mode.

## Concurrency rules

These apply to every flow below. They are the reason overselling can't happen.

1. **Every booking status change is a conditional update from the expected state**:
   `UPDATE booking SET status = :new … WHERE id = :id AND status = :expected`.
   Only the caller that changes exactly 1 row continues; the other (expiry job vs webhook vs cancel) backs off.
   Allowed moves: `PENDING_PAYMENT → CONFIRMED | EXPIRED | CANCELLED`; `EXPIRED | CANCELLED → CONFIRMED`
   (late payment only); `CONFIRMED → CANCELLED` (comp revoke only).
2. **Every seat change is guarded by owner and expected status**:
   hold `… WHERE id = :seat AND status = 'AVAILABLE'`;
   confirm / release `… WHERE id = :seat AND booking_id = :booking AND status = 'HELD'`;
   un-sell (comp revoke) `… WHERE id = :seat AND booking_id = :booking AND status = 'SOLD'`.
   Row counts are checked; a mismatch rolls back.
   Seats to hold are locked with `@Lock(PESSIMISTIC_WRITE)` **ordered by id** to avoid deadlocks.
3. **GA counters use conditional updates**:
   hold `SET held = held + :q WHERE id = :id AND capacity - held - sold >= :q`;
   confirm `SET held = held - :q, sold = sold + :q`; release `SET held = held - :q`;
   un-sell (comp revoke) `SET sold = sold - :q`.
   Applied in ticket-type id order. Because of rule 1, each booking's counters move exactly once per transition.
4. **A hold is released only by the expiry job (or explicit cancel)** — never by "noticing" a past
   `expires_at`. Same semantics for reserved and GA; a hold may live up to one job interval past its expiry.
5. **Per-customer serialization**: a hold or free booking first locks the customer's `User` row
   (`PESSIMISTIC_WRITE`), so one customer's bookings for any event run one at a time.
6. **Event status vs bookings**: holds, comps and the confirm step take a **shared** lock on the event row
   (`PESSIMISTIC_READ` → `FOR SHARE`) and re-check its status; event cancel takes an **exclusive** lock
   (`FOR UPDATE`). So a cancel waits for in-flight holds to commit (and then cancels them), and no new hold
   can start on a cancelled event. Event status transitions are conditional updates like rule 1
   (`WHERE id = :id AND status = :expected`).
7. **Lock order** is always: user → event → existing booking → seats / ticket types (by id). Same order
   everywhere, so no deadlocks. (A hold inserts its booking last, but a brand-new row can't be contended.)
8. **No external calls inside a transaction.** Payment-provider calls (create / expire checkout) and Redis writes
   (deleting passes, clearing a queue) run **after commit**, via `@TransactionalEventListener(phase = AFTER_COMMIT)`.
   A slow provider then never holds a database lock, and a rolled-back transaction never leaves a Redis side effect.
   Failures after commit are logged; nothing they do is needed for correctness.
   (Reading Redis inside a transaction — checking an admission pass — is fine.)
9. **Optimistic locking on edited entities.** `Event`, `Venue`, `TicketType` and `Booking` have a JPA `@Version`
   column. Hibernate writes every column on save, so without it a stale edit silently overwrites newer changes
   (e.g. an organizer saving a typo fix on an event an admin just cancelled would set it back to `APPROVED`).
   A stale save → `OptimisticLockException` → `409` ("changed by someone else, reload").
   The hand-written conditional `UPDATE` queries in rules 1–3 and 6 must also `SET version = version + 1` —
   JPQL bulk updates don't bump it automatically.

## Event lifecycle (organizer + admin approval)

```
DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ APPROVED ──publish──▶ ON_SALE ──(ends_at passes)──▶ ENDED
  ▲                    │
  └──edit── REJECTED ◀─┘ reject (with reason)

DRAFT / PENDING_APPROVAL / APPROVED / ON_SALE ──cancel──▶ CANCELLED
```

- In `DRAFT` / `REJECTED` everything is editable; editing a rejected event moves it back to `DRAFT`.
  Switching to a venue whose country uses a **different currency** → `409` unless the event has no ticket types
  yet (prices entered in the old currency would be meaningless); the organizer deletes them first.
- After approval only **cosmetic fields** (title, description, category), `max_tickets_per_customer`
  and `waiting_room_enabled` stay editable, without re-approval (organizers often learn about demand late).
  Text edits (title, description, category) go live immediately but set `needs_review = true`; admins see these
  in a re-review list and either clear the flag or cancel the event. This keeps typo fixes cheap while edits
  can't silently bypass the approval check.
  Turning the waiting room off mid-sale deletes the queue and passes and sets queue status `DISABLED` (pollers
  are told to just book); turning it on starts a queue at once (late joiners, FIFO). Changing the limit mid-sale applies to new bookings only:
  customers already above a lowered limit keep their tickets but can't buy more.
  Venue, dates, ticket types, prices and capacities are locked; changing them means cancel and recreate.
- Submit validates: venue owned by organizer, at least one ticket type, every section mapped (reserved),
  capacities set (GA), `sale_starts_at < starts_at < ends_at`. Submit, approve and publish each also require
  `ends_at > now` (no approving a stale submission or publishing an event that's already over).
- **An admin can't review their own event**: approve / reject / review-ok by the event's organizer → `403`,
  even if they hold the `ADMIN` role.
- Organizer publishes an approved event → `ON_SALE`: publicly visible; EventSeats generated (reserved).
  Buying is allowed from `sale_starts_at` until `ends_at` (door sales during the event are allowed).
- A scheduled job moves events past `ends_at` to `ENDED`.
- Organizer or admin can cancel (flow 5).
- Organizers can delete their own `DRAFT` / `REJECTED` events, and venues no event references.

**Visibility of `GET /api/events/{id}`:** `ON_SALE`, `ENDED` and `CANCELLED` events are public (past bookings
link to them). `DRAFT`, `PENDING_APPROVAL`, `REJECTED` and `APPROVED` are visible only to their organizer and
admins; anyone else gets `404` (not `403`, so unpublished events can't be discovered).
The public list (`GET /api/events`) shows `ON_SALE` only.

## Core flows

### 1. Waiting room (only when `waiting_room_enabled`)

Redis keys per event:
- `queue:{eventId}` — sorted set of user ids.
- `admitted:{eventId}` — sorted set of admission passes: member = user id, score = pass expiry (epoch millis);
  pass lifetime 15 min (config). A pass is valid if `ZSCORE > now`. Expired members are removed with
  `ZREMRANGEBYSCORE` on every admit tick. (A sorted set rather than one key per user, so active passes can be counted.)
- `queue-status:{eventId}` — `OPEN` | `PAUSED` | `SOLD_OUT` | `DISABLED`, written by the admit job and the toggle.

**Join** — `POST /api/events/{id}/queue` (customer JWT). Allowed from 30 min before `sale_starts_at` (config) until `ends_at`.
- Joined **before** `sale_starts_at` → score = random number in `[0, 1)`. Early arrivals are shuffled fairly; being first on the page gives no advantage.
- Joined **after** → score = `1 + millis since sale_starts_at` (first come, first served, always behind the early group).
- `ZADD NX`, so re-joining never improves or loses your place. One entry per user.

**Poll** — `GET /api/events/{id}/queue/me` reads **only Redis** (no database query per poll — that's the
point of using Redis) and returns one of:
- `WAITING` with `position` (`ZRANK + 1`) and `pollAfterSeconds` (server-controlled, e.g. 5–15s; raised when the line is long).
- `ADMITTED` with `admittedUntil`.
- `SOLD_OUT` — only when final: every seat / GA unit is `SOLD`. The queue is then cleared.
  If what's left is only *held*, users stay `WAITING` (admission paused), because expiring holds free tickets.
  If a revoked comp later frees inventory, it's visible on the public availability endpoint; the cleared queue isn't rebuilt.
- `NO_QUEUE` — status is `DISABLED` (waiting room turned off): go ahead and book.
- `NOT_IN_QUEUE`.

Before the first admit tick (sale not started) there is no status key yet; queued users get `WAITING`.

**Admit** — `@Scheduled` job every 5 seconds, for each on-sale event with a waiting room whose sale has started:
1. Read available inventory from Postgres once (`A` = available seats or GA units).
2. Write `queue-status:{eventId}` to Redis: `SOLD_OUT` if everything is sold, `PAUSED` if `A = 0` but some is held,
   else `OPEN`. Polls read this key.
3. If `OPEN`: remove expired passes, count active ones (`P = ZCARD admitted:{eventId}`), then `ZPOPMIN`
   the next `min(N, A − P)` users (N = config, e.g. 50; nothing if ≤ 0) and add their passes.
   Subtracting active passes stops the job from re-admitting for the same tickets every tick while
   earlier admits haven't booked yet.

**Gate** — when the waiting room is enabled, `POST /api/bookings` requires a valid admission pass, otherwise `403`.
A **successful hold uses up the pass** (removed after commit; a failed hold keeps it). So `P` in the admit step
counts only people admitted but not yet holding — someone with a hold is already subtracted from `A` and must not
be subtracted again, or admission stalls mid-sale. To change seats or try again after a hold expires or is
cancelled, the customer rejoins the line. Unused passes lapse.

If Redis is lost, the queue and passes reset; inventory in Postgres is unaffected, so no oversell is possible.
If Redis is **unreachable**, events with a waiting room fail closed: queue endpoints and bookings return `503`
(no booking without a verifiable pass). Events without a waiting room keep selling.

*shortcut: scheduled jobs assume a single app instance; add ShedLock (or a Redis lock) before running more than one.*

### 2. Hold tickets (`POST /api/bookings`)

Input: `eventId` plus either `eventSeatIds` (reserved) or `items: [{ticketTypeId, quantity}]` (GA).

**Request validation** (Bean Validation, before any locking): `quantity ≥ 1`; at least one seat or item;
no duplicate seat or ticket-type ids → `400`. Every seat / ticket type must **belong to `eventId`** (and match
its seating mode) → else `404`. Without this check a hold on event A could take event B's seats, skipping B's
waiting room; and a negative quantity would lower `held`.

In one `@Transactional` service method:
1. Lock the customer's `User` row (rule 5).
2. Shared-lock the event (rule 6) and check: `ON_SALE`, now between `sale_starts_at` and `ends_at`;
   admission pass valid if the waiting room is enabled.
3. **Per-customer limit:** tickets in this customer's `PURCHASE` bookings for this event that are
   `PENDING_PAYMENT` or `CONFIRMED`, plus this request, must not exceed `max_tickets_per_customer` → else `409`.
   Also at most one `PENDING_PAYMENT` booking per customer per event → else `409`.
   (A partial unique index `UNIQUE (user_id, event_id) WHERE status = 'PENDING_PAYMENT' AND type = 'PURCHASE'`
   backs this up at the database level.)
   Counting pending *and* confirmed means a webhook confirming another booking concurrently can't change the count.
4. Take inventory (rules 2 and 3). Any seat not `AVAILABLE` or any GA type short → `409` listing what's unavailable; the transaction rolls back.
5. Create `Booking` (`PENDING_PAYMENT`, `expires_at = now + 10 min`), `BookingItem`s, `BookingSeat`s.
   For a **paid** booking, `payment_provider` is set here from the event country's config (null for free bookings).
   It is saved here, not after commit, so the webhook's provider check holds even if the app dies before the
   checkout id is saved.

After commit: the admission pass is removed (if the waiting room is on).

The one-pending-booking rule can reject a rebook for up to one expiry-job interval after a hold's `expires_at`
(the old booking is still `PENDING_PAYMENT` until the job runs). That `409` carries a specific error code
(`PREVIOUS_HOLD_RELEASING`) and a "retry in a few seconds" message, not a generic conflict.

After commit (rule 8), use the booking's `payment_provider` gateway to create a checkout session (amount in
minor units + currency) with the **booking id in its `metadata`**, save the session id as `payment_reference`, and
return `{ bookingId, status, checkoutUrl, expiresAt }`.
If checkout creation fails, cancel the booking (flow 5) and **give the admission pass back** (fresh 15 min) —
the failure was ours, so the customer keeps their place. If the app dies between commit and checkout creation,
the booking simply expires. If it dies after creating the session but before saving its id, the webhook still
finds the booking through the metadata, so a payment can never arrive for a booking we can't find.

**Free bookings:** if the total is `0` (only free ticket types), the same transaction runs the confirm step
(flow 3) directly — no checkout. Response has `status: CONFIRMED` and no `checkoutUrl`. Steps 1–4 still apply,
so the user-row lock covers free bookings too. A booking mixing free and paid types is a paid booking.

### 3. Payment confirmation (`POST /api/payments/webhook/{provider}`)

Each provider has its own webhook path, signature scheme and payload. The provider's adapter does only two things:
**verify the signature** (PayMongo: the `Paymongo-Signature` header with the webhook secret; reject otherwise → `401`)
and **translate the payload** into a common `PaymentNotification(provider, providerEventId, type, bookingId,
amountMinor, currency)`. Everything below is provider-agnostic and works on that record. PayMongo's names are used as
examples (its paid event is `checkout_session.payment.paid`).

- **Raw body first:** the controller takes the body as `byte[]`, the adapter verifies the signature over those exact
  bytes, and only then parses JSON. (Letting Spring parse first changes the bytes and breaks — or tempts skipping —
  the signature check.)
- **Idempotency:** one transaction inserts the `PaymentEvent` row *and* does the work below. The insert comes
  **first, with `saveAndFlush`**, so a duplicate delivery fails on the unique (provider, provider-event-id) constraint
  before any work runs; the handler turns that `DataIntegrityViolationException` into `200`, no-op. If the work
  fails, the `PaymentEvent` insert rolls back too, so the provider's retry is processed again instead of being skipped.
- The booking is found by the **booking id in the checkout session's `metadata`** (not by session id).
- Event types other than `checkout_session.payment.paid` → store the `PaymentEvent`, log, return `200`
  (otherwise the provider retries forever). A paid event whose metadata names no existing booking (should never
  happen) → store it, log at error level, return `200`; the stored `PaymentEvent` is the trail for a manual refund.
- Paid amount **and currency** must equal the booking's `total_minor` and `currency`, and the provider must equal the
  booking's `payment_provider`; any mismatch → not confirmed, `refund_required = true`.
- On `checkout_session.payment.paid`, in one transaction, **lock first, then decide**:
  1. Lock the booking's owner (`User` row), shared-lock the event, then `SELECT … FOR UPDATE` the booking
     (rule 7 order). The status is now stable — the expiry job or a cancel can't change it until we commit.
     (Deciding on an unlocked read would lose the payment if the expiry job flipped the booking in between.)
  2. Branch on the **locked** state:
     - **Event `CANCELLED`** → `refund_required = true` on the booking whatever its status. If it is still
       `PENDING_PAYMENT`, also `→ CANCELLED` and release its inventory. If the event cancel already did that,
       nothing else to do (no double release, no rollback, no endless PayMongo retries).
     - **Booking `PENDING_PAYMENT`** → the **confirm step**: `→ CONFIRMED` (rule 1); seats `HELD → SOLD`
       guarded by booking id (rule 2); GA `held → sold` (rule 3); one `Ticket` per seat/unit.
     - **Booking `EXPIRED` or `CANCELLED`** (late payment) → event must be `ON_SALE`; **per-customer limit**
       (this booking's tickets plus the customer's other pending/confirmed ones); re-take the inventory recorded
       in `BookingSeat` / `BookingItem` with the hold rules. All pass → `CONFIRMED` + tickets. Any fails →
       `refund_required = true`, booking stays as it is. (Without the limit check, a customer whose hold
       expired could rebook and then get the late tickets too.)
     - **Booking `CONFIRMED`** (already paid via another event id) → `refund_required = true` (double payment).
- Whenever a booking expires or is cancelled, `PaymentGateway.expireCheckout(sessionId)` is called **after commit**
  so late payments are rare; failure is logged, not fatal.

The client never confirms a booking. For local dev the webhook is reached via a tunnel (e.g. ngrok), or a dev-profile-only endpoint simulates it.

### 4. Expire holds

`@Scheduled` every 30 seconds: for each `PENDING_PAYMENT` booking past `expires_at`, in its own transaction:
`PENDING_PAYMENT → EXPIRED` (rule 1; if 0 rows, a webhook won — skip); release its seats guarded by booking id
and its GA `held` counters. After commit: expire the checkout.

### 5. Cancel

- **Pending booking** — `DELETE /api/bookings/{id}` by its owner: `PENDING_PAYMENT → CANCELLED` (rule 1), inventory released; after commit, checkout expired. Confirmed bookings can't be cancelled.
- **Event** — organizer (own events) or admin, in one transaction holding the event's exclusive lock (rule 6):
  event `CANCELLED` (with `cancelled_by/at`); pending bookings cancelled as above; confirmed `PURCHASE`
  bookings with a non-zero total get `refund_required = true`; all `VALID` tickets → `VOID`.
  After commit: the pending bookings' checkouts are expired, and the queue and passes are deleted from Redis.
  (No provider calls while holding the exclusive event lock — rule 8.)

### 6. Complimentary tickets (organizer)

`POST /api/organizer/events/{id}/comps` with `recipientName`, `recipientEmail`, and either
`eventSeatIds` (reserved) or `items` (GA). Allowed for the organizer's own `ON_SALE` events,
including before `sale_starts_at` (sponsors often get seats before the public).
- Same request validation as flow 2 (seats / ticket types must belong to this event — so an organizer can
  never comp another organizer's event), and takes inventory with the same rules, so comps can never oversell.
- Skips the waiting room and per-customer limits; capped per request (config, e.g. 50).
- Creates a `COMP` booking, confirmed immediately (confirm step), total `0`. The response and
  `GET …/comps` return the ticket codes; the organizer shares them (no email).
- Revoke: `DELETE /api/organizer/events/{id}/comps/{bookingId}` → `CONFIRMED → CANCELLED` (with `cancelled_by/at`),
  tickets `VOID`, inventory released. A comp with a `USED` ticket can't be revoked (`409`).

### 7. Attendees and check-in (organizer)

- **Attendee list** — `GET /api/organizer/events/{id}/attendees` (paged): one row per ticket — code, status,
  ticket type, seat (reserved), holder name and email (customer, or comp recipient), booking type.
- **Check-in** — `POST /api/organizer/events/{id}/check-in` with `{ code }`, for the organizer's own event:
  - `VALID` ticket of this event → conditional update `VALID → USED` with `checked_in_at/by`; returns the attendee row.
  - Already `USED` → `409` with when it was checked in (catches copied tickets).
  - `VOID`, unknown code, or a ticket of another event → `404`/`409` with the reason.
  Only allowed while the event is `ON_SALE` (doors open during the sale window).

## API outline

Public:
- `POST /api/auth/register` (optionally `organizer: true`), `POST /api/auth/login` → JWT
- `POST /api/me/organizer` (logged-in customer adds the `ORGANIZER` role; re-login to get a token with it)
- `GET /api/events?country=&city=&category=&from=&to=` (paged, `ON_SALE` only), `GET /api/events/{id}`
  (event responses include `currency`, venue `countryCode` and `timeZone`; prices as `{ amountMinor, currency }`)
- `GET /api/countries` → enabled countries with currency (for organizer forms and filters)
- `GET /api/events/{id}/availability` → reserved: sections/seats with status and price; GA: ticket types with price and remaining count
  *(shortcut: returns every seat at once; fine for seeded venues — split per section before using a 10k+ seat layout.)*

Customer:
- `POST /api/events/{id}/queue`, `GET /api/events/{id}/queue/me`
- `POST /api/bookings`, `GET /api/bookings` (own `PURCHASE` bookings only — comps an organizer issued are under
  their organizer endpoints), `GET /api/bookings/{id}`, `DELETE /api/bookings/{id}`
- `GET /api/bookings/{id}/tickets`

Organizer (own resources only):
- `POST/PUT/GET/DELETE /api/organizer/venues`, `POST /api/organizer/venues/{id}/sections` (rows × seats per row)
- `POST/PUT/GET/DELETE /api/organizer/events`, `PUT /api/organizer/events/{id}/ticket-types` (incl. section mapping for reserved)
- `GET /api/organizer/events/{id}/attendees`, `POST /api/organizer/events/{id}/check-in`
- `POST /api/organizer/events/{id}/submit | publish | cancel`
- `POST/GET /api/organizer/events/{id}/comps`, `DELETE /api/organizer/events/{id}/comps/{bookingId}`
- `GET /api/organizer/events/{id}/sales` → per ticket type: sold, comp, held, remaining, revenue

Admin:
- `GET /api/admin/events?status=PENDING_APPROVAL`, `GET /api/admin/events?needsReview=true`,
  `POST /api/admin/events/{id}/review-ok` (clears `needs_review`)
- `POST /api/admin/events/{id}/approve`, `POST /api/admin/events/{id}/reject` (reason), `POST /api/admin/events/{id}/cancel`
- `GET /api/admin/bookings?refundRequired=true`, `POST /api/admin/bookings/{id}/refund-handled` (clears the flag after a manual refund)
- `GET /api/admin/payments/unmatched` (paid `PaymentEvent`s whose metadata matched no booking),
  `POST /api/admin/payments/{id}/refund-handled`

Provider:
- `POST /api/payments/webhook/{provider}` (no JWT; signature-verified by that provider's adapter; unknown provider → `404`)

State-change endpoints use action verbs (`submit`, `publish`, `approve`) rather than PATCHing a status field —
it keeps each transition's rules in one place.

## Docker & configuration

- `Dockerfile`: multi-stage — Maven build on `eclipse-temurin:25-jdk`, run on `eclipse-temurin:25-jre`, non-root user.
- `docker-compose.yml`: `app`, `postgres:16`, `redis:7` with healthchecks; app waits for healthy DB/Redis; Postgres data in a named volume.
- Developers can also run only `postgres` + `redis` in Compose and the app from the IDE.
- Profiles `dev` and `test`. Secrets via env vars, never committed: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`,
  `REDIS_HOST`, `JWT_SECRET`, `PAYMONGO_SECRET_KEY`, `PAYMONGO_WEBHOOK_SECRET`. A `.env.example` lists them.
- Tunables as `@ConfigurationProperties`: hold duration (10 min), default max tickets per customer (8)
  and the platform cap organizers can set it to (50),
  comp cap per request (50), queue open lead time (30 min), admit batch size and interval, admission pass TTL (15 min),
  and `ticketing.countries` (per country: enabled, currency, payment provider, minimum charge).
  Provider secrets are per provider (`PAYMONGO_*` now; e.g. `STRIPE_*` when a provider is added).

## Testing

**How:** unit tests for pure logic; integration tests with Testcontainers (Postgres + Redis) and MockMvc;
**concurrency tests** call the service layer directly with an `ExecutorService` + `CountDownLatch` (not MockMvc —
the point is to stress the database locks). `PaymentGateway` is faked; no network calls to PayMongo.
Tests are grouped by the milestone that introduces them.

**M1 — auth & users**
- Register / login; roles in the token; `POST /api/me/organizer`; password rule; email case-insensitive.

**M2 — countries, venues, events, GA holds**
- Venue in a disabled country → 400; invalid time zone → 400; event currency follows the venue's country.
- Minor units for a zero-decimal currency (a JPY test country enabled only in the `test` profile).
- Schedule input: local time converted with the venue zone; DST-gap time → 400; schedule with an offset → 400;
  other date-times (filters) without an offset → 400; responses carry offset + zone.
- Request validation: ticket types of another event → 404; quantity ≤ 0 or duplicate ids → 400.
- Ticket limit: organizer value outside `1..platform cap` → 400; lowering it mid-sale blocks further buys only;
  rebook right after expiry → `409 PREVIOUS_HOLD_RELEASING`.
- *Concurrency:* N threads buy GA beyond capacity → `held + sold` never exceeds capacity;
  a customer fires parallel holds → per-customer cap never exceeded.
- Expiry job releases holds; ticket codes are unique and 128-bit.

**M3 — reserved seating**
- Seats of another event → 404. *Concurrency:* N threads hold the same seat → exactly one succeeds.

**M4 — payments**
- Valid signature confirms; bad signature → 401; duplicate delivery ignored; unknown provider path → 404;
  other event types / unmatched booking → 200 (unmatched appears in the admin list).
- Amount, currency or provider mismatch → refund flagged.
- Failure mid-confirm leaves no `PaymentEvent`, so the retry succeeds; signature checked on the raw body
  (re-serialized JSON with the same content → 401).
- Stale admin `refund-handled` save after a concurrent webhook change → 409, nothing reverted.
- Crash after checkout creation but before saving its id → webhook still confirms (found via metadata,
  provider saved at hold time).
- Late payment re-claims, or flags refund; double payment for a confirmed booking → refund flagged.
- *Concurrency:* webhook vs expiry job at the boundary → payment never lost; late payment after the customer
  rebooked up to the limit → refund flagged, limit not exceeded.
- After-commit side effects: rolled-back confirm leaves no Redis / provider side effect.

**M5 — approval, cancel, organizer tools**
- Lifecycle transitions; submit / approve / publish with `ends_at` in the past → 409; admin reviewing own event → 403.
- Stale edit: organizer saves an event an admin cancelled meanwhile → 409, event stays `CANCELLED`.
- Post-approval text edit sets `needs_review`; `review-ok` clears it.
- Visibility: unpublished events → 404 to the public and other organizers; ownership checks across organizers.
- Draft venue switch to a different-currency country with ticket types → 409; same currency → ok.
- Disabling a country blocks new venues / submits; an on-sale event there keeps selling and confirming.
- Event cancel: pending cancelled, confirmed flagged, tickets `VOID`, no provider calls while holding the lock.
- Attendee list; check-in `VALID → USED`; used / void / other event's code rejected.
- *Concurrency:* event cancel vs holds and a webhook → no confirmed booking or `VALID` ticket on a cancelled event;
  event cancel then webhook → refund flagged, 200, no retry loop; two check-ins of one code → exactly one succeeds.

**M6 — free tickets & comps**
- Free booking confirms with no checkout; `GET /api/bookings` shows purchases only.
- Comps take real inventory, can't oversell, can't target another organizer's event, are excluded from revenue;
  revoke releases inventory; revoking a comp with a `USED` ticket → 409.
- *Concurrency:* parallel free bookings by one customer → cap never exceeded.

**M7 — waiting room**
- Early joiners shuffled and all ahead of late joiners; late joiners FIFO; booking without a pass → 403.
- A successful hold uses up the pass; a failed hold keeps it; checkout-creation failure gives the pass back.
- Admits at most `available − active passes`; no stall: with holds in progress, admission continues for the rest.
- Polls make no database queries; Redis down → 503; waiting room turned off → `NO_QUEUE`.

## Milestones

Each milestone ends with a working, tested slice. Later milestones build on earlier ones; none is started
before the previous one's tests pass.

| # | Slice | Spring topics learned |
|---|---|---|
| 1 | Project skeleton, Docker Compose for Postgres, Flyway, users + JWT auth, roles | Boot setup, Security, JPA basics, Testcontainers |
| 2 | Country config, venues (country + time zone), events (created directly as `ON_SALE` for now; currency from venue; venue-local schedule input), GA ticket types with minor-unit prices, GA holds with a **fake** payment confirm endpoint, expiry job, GA concurrency tests | `@ConfigurationProperties`, `java.time`, transactions, conditional updates, `@Scheduled`, `Clock` |
| 3 | Reserved seating: sections, seats, EventSeat, BookingSeat, seat-map availability, seat concurrency tests | Pessimistic locking, deadlock avoidance |
| 4 | `PaymentGateway` + PayMongo adapter, provider routing (country → provider, `/webhook/{provider}`), idempotency, late payments, refund flag (fake endpoint removed) | HTTP clients, webhook security, idempotency, Spring bean maps |
| 5 | Organizer/admin approval lifecycle, visibility, ownership checks, event cancel, sales summary, attendees + check-in | Method security, state machines |
| 6 | Free ticket types and comps | Reusing the confirm step |
| 7 | Redis waiting room | Spring Data Redis, sorted sets |
| 8 | App Dockerfile, full Compose stack, Actuator health checks | Containerizing Boot apps |

## Left to the builder

- Exact DTO shapes, field names, pagination defaults, event categories list.
- JWT library choice (jjwt vs `oauth2-resource-server` with HMAC) and token lifetime.
- Package base name and file layout inside each feature package.
- Seed data details.
- Logging format.
