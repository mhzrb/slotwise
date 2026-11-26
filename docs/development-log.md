# Development log

Slotwise was built incrementally in ten stages. Each stage ended with a green build of the tests that existed at
that point. This log records what each stage delivered, what was learned along the way, and the technical debt that
was consciously accepted.

| Stage | Delivered | Tests at end of stage |
|---|---|---|
| 1. Project & database design | Maven/Boot 4.1 project, Flyway V1–V5 (full schema incl. exclusion constraint), RFC 9457 error handling, correlation-id filter, base test infrastructure (singleton Testcontainers) | 6 IT |
| 2. Authentication & users | Register/login/refresh/logout, HS256 JWT via resource server, hashed rotating refresh tokens with reuse detection, `jti` denylist, profile & password, admin user search/disable, bootstrap admin | 20 unit, 25 IT |
| 3. Providers & services | Provider profiles (IANA zone, slot interval), public directory, service CRUD with ownership, soft delete, search with filters | 24 unit, 37 IT |
| 4. Availability engine | Pure `SlotCalculator` (breaks, holidays, blocks, extra hours, buffers, notice, horizon, DST), weekly schedule, exceptions, slot endpoint, `BusyTimeProvider` port | 59 unit, 45 IT |
| 5. Appointments | State machine, booking, reschedule, confirm/reject/cancel/complete with role-specific rules, history, daily/weekly agenda, admin search | 103 unit, 57 IT |
| 6. Concurrency control | Explicit READ COMMITTED, bounded `lock_timeout` → 503, `ConcurrentBookingIT` (real HTTP + real PostgreSQL), ADR 0001 | 103 unit, 72 IT |
| 7. Notifications | Transactional outbox, async dispatcher with `SKIP LOCKED`, retries/backoff, `EmailSender` abstraction, ADR 0002 | 110 unit, 78 IT |
| 8. Quality | Authorization matrix, platform tests, sort whitelisting, JaCoCo, bug fixes (below) | 114 unit, 86 IT |
| 9. Docker & CI | Multi-stage layered image (non-root, healthcheck), Compose, `.env.example`, GitHub Actions | — |
| 10. Documentation | README, ER/architecture/sequence/state diagrams, final review | 114 unit, 86 IT |

## Findings worth remembering

- **Exclusion constraint alone can deadlock.** Raw concurrent inserts that bypass the provider lock produced
  `40P01 deadlock_detected` and `55P03 lock_not_available` as well as the expected `23P01`. The invariant held, but this
  confirmed that the row lock should be the primary mechanism (see ADR 0001). Transient SQLSTATEs are mapped to
  `503 + Retry-After`.
- **Method security runs after request body validation.** With only `@PreAuthorize`, a wrong-role caller sending an
  invalid body got `400` with validation details instead of `403`. Role rules were moved into the filter chain, and
  `@PreAuthorize` remains as a second layer. This was found by `AuthorizationMatrixIT`.
- **Disabled provider accounts were still bookable** by service ID, although they were hidden from listings. This is
  fixed, and a regression test was added.
- **Hibernate flushes inserts before deletes.** Replacing a weekly schedule entity by entity would violate the
  `(provider_id, day_of_week)` unique constraint, so a bulk JPQL delete runs first.
- **Notification rows written in one transaction can share a timestamp.** Tests compare them as multisets rather than
  ordered lists, to avoid flakiness.

## Accepted technical debt

| Item | Why accepted | Path forward |
|---|---|---|
| Disabled users' access tokens stay valid for ≤ 15 min | Avoids a per-request user lookup | Cache of disabled user ids checked by the JWT validator |
| One DB lookup per request for the `jti` denylist | Needed for real logout with stateless JWTs | Local cache / Bloom filter, or Redis |
| Bookings for one provider are serialised | Lock is held for milliseconds; correctness first | Per-resource locking for multi-staff providers |
| Working windows cannot end at 24:00 or cross midnight | `LocalTime` ranges within a day keep the engine simple | Model windows as `(LocalDate, LocalTime, Duration)` |
| Outbox send happens while row locks are held | Fine for fast senders | Claim-then-send with a lease for slow SMTP |
| Outbox rows are never purged | Small volume at this scale | Retention job |
| Catalogue search uses `LIKE '%q%'` | Adequate for small catalogues | `pg_trgm` index or full-text search |
| `BaseEntity` audit timestamps use `Instant.now()` rather than the injected `Clock` | Audit data is not a business rule | Hibernate `@CurrentTimestamp` or an entity listener using the clock |
