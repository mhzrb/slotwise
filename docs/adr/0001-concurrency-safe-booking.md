# ADR 0001 — Concurrency-safe booking

- **Status:** Accepted
- **Context:** Stage 6

## Problem

Two customers request overlapping times with the same provider at the same
moment. A naive "check availability, then insert" flow has a time-of-check /
time-of-use race: both transactions check, both see the slot as free, both
insert. The result is a double booking.

Overlap is a *range* property: `10:00–11:00` and `10:30–11:30` collide even
though their start times differ. A plain `UNIQUE (provider_id, start_at)`
constraint cannot express that.

## Decision

Two independent layers.

### 1. Pessimistic lock on the provider row (primary mechanism)

`book` and `reschedule` run in one `READ COMMITTED` transaction:

```
BEGIN;
SELECT ... FROM provider_profiles WHERE id = :providerId FOR UPDATE;   -- serialisation point
-- authoritative availability check (working hours, breaks, exceptions, existing appointments)
INSERT INTO appointments ...;                                          -- flushed immediately
COMMIT;
```

* Requests for the **same provider** queue on the row lock and execute one at a time.
* Under `READ COMMITTED`, each statement gets a fresh snapshot. When the second
  transaction acquires the lock, its availability query therefore **sees the
  appointment the first one committed**, and it returns `409 SLOT_UNAVAILABLE`.
* Requests for **different providers** never block each other.
* The same lock is taken when a provider edits their weekly schedule or
  exceptions, so a booking cannot interleave with a schedule change.
* Lock waits are bounded by `lock_timeout` (default 5 s, set on every pooled
  connection). If it is exceeded, the client gets `503 LOCK_TIMEOUT` with
  `Retry-After`, instead of the request holding a connection indefinitely.

### 2. PostgreSQL exclusion constraint (safety net)

```sql
CONSTRAINT ex_appointments_no_overlap EXCLUDE USING gist (
    provider_id WITH =,
    tstzrange(start_at, blocked_until, '[)') WITH &&
) WHERE (status IN ('PENDING', 'CONFIRMED'))
```

* The database itself refuses two active, overlapping ranges for one provider,
  whatever code path wrote them: a bug, a missed lock, a manual SQL fix, or a
  future batch import.
* Half-open ranges `[)` allow back-to-back appointments.
* `blocked_until = end_at + buffer`, so buffer time is protected as well.
* The insert is flushed (`saveAndFlush`) inside the transaction. A violation
  (SQLSTATE `23P01`) is translated to `409 SLOT_UNAVAILABLE`.

### Why the lock is primary rather than the constraint: deadlocks

The raw-insert concurrency test surfaced this in practice. When several
transactions insert overlapping rows **at the same moment, without the
provider lock**, PostgreSQL can deadlock. Each transaction inserts its tuple,
then waits on the other's *uncommitted* tuple while checking the exclusion
constraint. PostgreSQL detects the cycle after `deadlock_timeout` (1 s by
default) and aborts one transaction with SQLSTATE `40P01`, not `23P01`. Under
heavier contention, wait chains can also exceed `lock_timeout` (`55P03`).

The invariant still holds: exactly one row commits. But the loser pays up to a
second of latency and receives a deadlock error instead of a clean conflict.
Taking the provider row lock first serialises conflicting writers *before*
any tuple is inserted, so on the application path conflicts are always
detected cleanly by the availability check (`409 SLOT_UNAVAILABLE`), and
deadlocks cannot occur. If one ever did (some future code path that skips the
lock), the API maps `40P01` / `55P03` / `40001` to `503` + `Retry-After`. The retry then succeeds or
gets a clean `409`.

## Alternatives considered

| Option | Why not (alone) |
|---|---|
| Availability check only | Race condition: the core problem. |
| `UNIQUE (provider_id, start_at)` | Misses overlaps with different start times and buffers. |
| `SERIALIZABLE` isolation | Correct, but it fails with serialization errors that need retry loops. It aborts more broadly than necessary, and the outcome depends on predicate-lock granularity. |
| Optimistic locking (`@Version`) | A *new* appointment has no existing row to version. You would need an artificial "provider calendar version" row, which is a lock in disguise with retry semantics. |
| Advisory locks (`pg_advisory_xact_lock`) | Works, but the lock is invisible in the schema and keyed by convention. A row lock on an existing entity is easier to reason about. |
| Pre-generated slot rows + `SELECT FOR UPDATE SKIP LOCKED` | Requires materialising every slot for every service. It breaks with variable durations and buffers, and with schedule changes. |
| Exclusion constraint only | Correct, but then the constraint *is* the normal control flow, and a failing statement aborts the transaction. The lock gives deterministic, cheap rejections, and the constraint is a guarantee rather than the mechanism. |

## Consequences

* **+** Correct under any concurrency, verified by `ConcurrentBookingIT` against
  real PostgreSQL and real HTTP:
  * 12 customers racing for one slot (repeated 5×): exactly one `201`, eleven `409`.
  * Overlapping requests with different start times: exactly one wins.
  * Buffer time is respected under concurrency.
  * Disjoint bookings for one provider all succeed, so the lock never rejects valid requests.
  * Contention is isolated per provider.
  * A reschedule racing a new booking for the same slot: exactly one wins.
  * A confirm racing a reject: exactly one applies.
  * Raw concurrent SQL inserts that bypass the application: the constraint alone lets exactly one commit.
  * Lock timeout: the request fails fast with `503`.
* **−** Bookings for one provider are serialised. Each holds the lock for a few
  milliseconds (one indexed range query plus one insert), which is acceptable
  for per-provider booking rates. A very high-volume single calendar would need
  sharding by resource (for example per staff member), which this model supports
  by locking that row instead.
* **−** Requires the `btree_gist` extension (bundled with PostgreSQL contrib,
  available on all major managed offerings).

Status changes (confirm/reject/cancel/complete) do not move time ranges. They
are protected by optimistic locking (`@Version`), and a lost race returns
`409 CONCURRENT_MODIFICATION` or `409 INVALID_STATUS_TRANSITION`.
