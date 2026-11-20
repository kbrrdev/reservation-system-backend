# Event Ticketing Backend

A ticketing API for events worldwide — concerts, conferences, workshops, festivals — launching first in the
Philippines. Organizers create events (an admin approves them), customers hold seats or general-admission tickets,
pay, and get ticket codes. High-demand sales go through a fair waiting room.

Built as a project to learn **Java 25 + Spring Boot 4** the way it's done in practice: transactions and locking
that never oversell a seat, verified payment webhooks, and Testcontainers-backed tests.

## Status

🚧 **Milestones 1–4 done; milestone 5 next.** Built in milestones; each one is a working, tested slice.

| # | Milestone | Status |
|---|---|---|
| 1 | Project skeleton, Postgres, Flyway, users + JWT auth | ✅ Done |
| 2 | Countries, venues, events, general-admission holds | ✅ Done |
| 3 | Reserved seating | ✅ Done |
| 4 | Payments (PayMongo test mode) | ✅ Done |
| 5 | Organizer / admin approval, cancel, check-in | — |
| 6 | Free tickets and comps | — |
| 7 | Redis waiting room | — |
| 8 | Full Docker stack | — |

## Tech stack

Java 25 · Spring Boot 4 · Spring Security (JWT) · Spring Data JPA · PostgreSQL · Flyway · Redis ·
PayMongo (test mode) · Testcontainers · Docker Compose · Maven

## Docs

- [Design](docs/superpowers/specs/2026-11-19-event-ticketing-design.md) — goals, domain model, concurrency rules, flows, API outline
- Implementation plans — `docs/superpowers/plans/` (one per milestone)

## Running locally

Prerequisites: Java 25 and Docker.

```bash
cp .env.example .env            # then fill in DB_URL, DB_USERNAME, DB_PASSWORD, JWT_SECRET (git-ignored)
docker compose up -d            # PostgreSQL on localhost:5433 (5433 avoids clashing with a locally installed Postgres)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

If Postgres rejects the password because the Compose volume was created with an older `.env`, run `docker compose down -v` (wipes the local DB).

Swagger UI: <http://localhost:8080/swagger-ui.html> (log in via `POST /api/auth/login`, then use **Authorize**
with the `accessToken`).

The `dev` profile seeds three accounts (created only if missing), all with the password `devpassword123`:

| Email | Roles |
|---|---|
| `admin@example.test` | CUSTOMER, ADMIN |
| `organizer@example.test` | CUSTOMER, ORGANIZER |
| `customer@example.test` | CUSTOMER |

The password is for local development only; the seeder never runs without the `dev` profile.

The `dev` profile also seeds two sample events, each created only if the organizer has no venue of that name:
"Sample Concert" (general admission, VIP and Regular tickets) and "Sample Play" (reserved seating, 86 seats in Orchestra
and Balcony sections).

### Try it

Replace `$TOKEN`, `$EVENT`, `$REGULAR`, `$BOOKING`, `$TOTAL`, `$PLAY`, `$SEAT1` and `$SEAT2` with the values from the previous responses.

1. Log in: `curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"email":"customer@example.test","password":"devpassword123"}'` and copy `accessToken` into `$TOKEN`.
2. List events: `curl -s 'localhost:8080/api/events?country=PH'` (copy the event `id` and the Regular ticket type `id`).
3. Availability: `curl -s localhost:8080/api/events/$EVENT/availability` (Regular `remaining` is 500).
4. Hold 2 tickets: `curl -s -X POST localhost:8080/api/bookings -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"'$EVENT'","items":[{"ticketTypeId":"'$REGULAR'","quantity":2}]}'` (status `PENDING_PAYMENT`, a `checkoutUrl`; unpaid holds expire after 10 minutes). Copy `id` into `$BOOKING` and `total.amountMinor` into `$TOTAL`.
5. Pay: in dev the payment provider is a fake, so send the webhook it would send yourself: `curl -s -i -X POST localhost:8080/api/payments/webhook/fake -H 'Fake-Signature: dev-fake-secret' -H 'Content-Type: application/json' -d '{"id":"evt_1","type":"payment.paid","bookingId":"'$BOOKING'","amountMinor":'$TOTAL',"currency":"PHP"}'` (`200`; the booking is now `CONFIRMED`). Sending it again is a no-op (`200`); a wrong signature gets `401`.
6. Ticket codes: `curl -s localhost:8080/api/bookings/$BOOKING/tickets -H "Authorization: Bearer $TOKEN"` (2 codes).
7. Availability again: Regular `remaining` is now 498.

Reserved seating: the same flow with "Sample Play". Its availability response lists every seat with an `eventSeatId` (copy two into `$SEAT1` / `$SEAT2`, and the event `id` into `$PLAY`); hold seats by
sending those ids instead of ticket types: `curl -s -X POST localhost:8080/api/bookings -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"eventId":"'$PLAY'","eventSeatIds":["'$SEAT1'","'$SEAT2'"]}'`.
Pay it with the webhook, and the tickets carry `section` / `row` / `number`; those seats show as `SOLD` in availability.

Admin (log in as `admin@example.test`): `GET /api/admin/bookings?refundRequired=true` lists paid bookings that need a manual refund (late or double payments), `POST /api/admin/bookings/{id}/refund-handled` with `{"version":N}` clears one; `GET /api/admin/payments/unmatched` and `POST /api/admin/payments/{id}/refund-handled` do the same for payments that matched no booking.

### PayMongo test mode

1. In `.env` set `PAYMONGO_SECRET_KEY` (a `sk_test_…` key), `PAYMONGO_WEBHOOK_SECRET` and `PH_PAYMENT_PROVIDER=paymongo`, then restart the app.
2. Expose the app with a tunnel (e.g. `ngrok http 8080`) and, in the PayMongo dashboard, register a webhook to `https://<tunnel>/api/payments/webhook/paymongo` for `checkout_session.payment.paid`. Its signing secret is `PAYMONGO_WEBHOOK_SECRET`.
3. Hold tickets as above and open the returned `checkoutUrl`; pay with a test method. The webhook confirms the booking.

Run the tests (needs Docker, for Testcontainers): `./mvnw test`
