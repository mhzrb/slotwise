# ADR 0002 — Notifications via a transactional outbox

- **Status:** Accepted
- **Context:** Stage 7

## Problem

Appointment changes must trigger notifications (booking request received,
confirmed, cancelled, rescheduled, ...). Requirements:

1. A notification failure (SMTP down, timeout, bug in a template) must never
   roll back or slow down the appointment change.
2. No *phantom* notifications: none for bookings that were rolled back, for
   example after losing a concurrent race with a `409`.
3. No *lost* notifications: a committed booking must eventually produce its
   notification, even if the process crashes right after the commit.
4. Delivery should be asynchronous and retryable.

Sending the email inside the booking transaction violates (1). Sending it in
an `AFTER_COMMIT` listener satisfies (1) and (2) but violates (3): a crash
between the commit and the send loses the message.

## Decision: transactional outbox + asynchronous dispatcher

```
 HTTP request thread                                   scheduler / async thread
 ───────────────────────────────────────────────┐     ┌──────────────────────────────────────────
 BEGIN                                           │     │ BEGIN
   lock provider, validate, INSERT appointment   │     │   SELECT ... FROM notifications
   publish AppointmentEvent                      │     │    WHERE status='PENDING' AND next_attempt_at <= now()
   [BEFORE_COMMIT] INSERT notifications(PENDING) │     │    FOR UPDATE SKIP LOCKED LIMIT 50
 COMMIT ─────────────────────────────────────────┼──►  │   EmailSender.send(...)  → SENT
   [AFTER_COMMIT, @Async] wake dispatcher ───────┘     │                          ↘ attempts++, backoff / FAILED
                                                       │ COMMIT
                                                       └──────────────────────────────────────────
```

* **Outbox write (`@TransactionalEventListener(BEFORE_COMMIT)`):** the
  notification rows are inserted in the *same* transaction as the appointment.
  They commit together or not at all, which satisfies (2) and (3). This step is
  a local insert with no I/O to external systems.
* **Delivery (`NotificationDispatcher`):** runs on a scheduler thread every 5 s
  (`app.notifications.dispatcher.interval`). An `AFTER_COMMIT` + `@Async`
  listener also wakes it immediately for low latency. Each batch runs in its own
  transaction, so delivery failures are isolated from appointments (1).
* **Retries:** a failed attempt increments `attempts`, stores `last_error`, and
  schedules `next_attempt_at` with exponential backoff (1 m, 2 m, 4 m, ...).
  After `max-attempts` (5) the row becomes `FAILED` and is logged at ERROR.
* **Horizontal scaling:** `FOR UPDATE SKIP LOCKED` lets any number of
  dispatcher threads or instances run in parallel. Each row is claimed by
  exactly one of them, and none of them block each other (verified in
  `NotificationIT.parallelDispatchersDeliverEachNotificationExactlyOnce`).
* **Abstraction:** `EmailSender` is an interface. The default
  `LoggingEmailSender` (`NOTIFICATIONS_EMAIL_SENDER=log`) logs a masked
  recipient (`j***@example.com`) and the subject at INFO, and the body only at
  DEBUG. A real SMTP/SES/SendGrid sender is a drop-in bean.
* **Who gets what** is decided by the pure `NotificationComposer`, which is
  unit-tested without a database.

| Event | Customer | Provider |
|---|---|---|
| created | `APPOINTMENT_CREATED` | `APPOINTMENT_REQUEST_RECEIVED` |
| confirmed / rejected / completed | ✔ | |
| cancelled by customer | | ✔ |
| cancelled by provider | ✔ | |
| cancelled by admin | ✔ | ✔ |
| rescheduled | ✔ (old → new time) | ✔ |

## Consequences

* **+** Exactly the committed changes produce notifications. Delivery is at
  least once, and failures are visible (`status`, `attempts`, `last_error`) and
  queryable.
* **−** Delivery is *at-least-once*: if the process dies after `send()` but
  before its transaction commits, that message is sent again. Real providers
  mitigate this with idempotency keys (the notification `id` is a natural one).
* **−** The send happens while the batch's row locks are held. That's fine
  for fast senders. For slow SMTP, switch to a claim-then-send pattern
  (`status = SENDING` with a lease timeout) to keep transactions short.
* **−** Outbox rows grow without bound. A retention job (for example, delete
  `SENT` rows older than 90 days) is a listed future improvement.
