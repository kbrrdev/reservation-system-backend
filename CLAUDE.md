# CLAUDE.md

Event ticketing backend (Java 25, Spring Boot 4). Personal project to learn Spring Boot.
Design: `docs/superpowers/specs/2026-11-19-event-ticketing-design.md`.

## How to work in this repo

Always think and act as a **senior Java / Spring Boot developer**:

- Follow common, idiomatic Spring Boot practices; prefer what the framework already gives you over custom code.
- Think about correctness under concurrency first: transactions, locking, idempotency. Never double-sell a seat.
- Validate at trust boundaries (requests, webhooks); never trust the client for payment or seat state.
- Keep it simple: no abstractions, layers, or dependencies the task doesn't need.
- Every non-trivial change comes with a test (Testcontainers for anything touching the database).
- This is a learning project: when making a design choice, briefly explain the why and the trade-off.
