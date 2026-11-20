# Event Ticketing Backend

A ticketing API for events worldwide — concerts, conferences, workshops, festivals — launching first in the
Philippines. Organizers create events (an admin approves them), customers hold seats or general-admission tickets,
pay, and get ticket codes. High-demand sales go through a fair waiting room.

Built as a project to learn **Java 25 + Spring Boot 4** the way it's done in practice: transactions and locking
that never oversell a seat, verified payment webhooks, and Testcontainers-backed tests.

## Status

🚧 **Design approved, implementation starting.** Built in milestones; each one is a working, tested slice.

| # | Milestone | Status |
|---|---|---|
| 1 | Project skeleton, Postgres, Flyway, users + JWT auth | ⏳ Next |
| 2 | Countries, venues, events, general-admission holds | — |
| 3 | Reserved seating | — |
| 4 | Payments (PayMongo test mode) | — |
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

Instructions arrive with milestone 1 (`docker compose up -d`, then `./mvnw spring-boot:run`).
