# Push Notifications (Server) — Design Spec

**Date:** 2026-08-10
**Status:** Spec only — to be implemented in the `../ledgerline` (Laravel 13) repo
**Companion spec:** `2026-08-10-push-notifications-android-design.md`

This documents the server-side work the Android UnifiedPush client depends on. It is a
spec for the Laravel repo, not implemented here.

## Context (current server state)

- Notification centre exists: `GET /notifications` (ETag/304), `POST /notifications/{id}/read`,
  `POST /notifications/read-all`. Notification schema: `id, level, category, title, body,
  read, at`.
- Delivery channels already configured (admin, global): NTFY, SMTP/mail, webhook. NTFY
  credentials (`url`, `topic`, `token`) stored encrypted in `AppSettings`.
- Scheduler generators today: **only** `invoices:remind` (`RemindInvoices`, daily 08:00 UTC)
  — dunning with `reminded_at` / `reminder_count`.
- Calendar VALARM support exists (`alarm_minutes_before`, 0–40320) but **no** scheduled
  generator turns alarms into notifications.
- **No** per-device push endpoint storage and **no** per-device push dispatch (no
  FCM/VAPID/WebPush). ntfy is used as a global channel, not per-device.

## Part 1 — Per-device push endpoint (required for Android v1)

The Android app registers a UnifiedPush endpoint (an HTTPS URL, typically on the
self-hosted ntfy) and needs to hand it to the server, tied to the calling device token.

New routes (bearer-authenticated, device-scoped):
- `POST /api/v1/device/push-endpoint` — body `{ "endpoint": "<https url>" }`. Upserts the
  endpoint on the current device record. Validate: https only, max length, rate-limit.
- `DELETE /api/v1/device/push-endpoint` — clears it (push disabled / distributor removed).

Storage: add `push_endpoint` (nullable, encrypted) to the device/token model. One endpoint
per device; a user may have several devices → several endpoints.

## Part 2 — Dispatch on notification creation

Introduce a single choke point so every notification-centre row can fan out to push.

- Wrap notification creation in a `Notifications::create(...)` service (or a model
  `created` observer) that, after persisting the row, enqueues a `SendPushJob(user,
  notification)`.
- `SendPushJob`: for each of the user's devices with a `push_endpoint`, HTTP-POST the
  payload to that endpoint (ntfy accepts a POST to the topic URL; UnifiedPush delivers the
  body verbatim to the app). Respect per-user/-category preferences (Part 4). Retry with
  backoff; prune endpoints that return 404/410 (gone).
- Payload (JSON, matches Android decoder):
  `{ "id", "category", "level", "title", "body" }`.
- First integration: route `RemindInvoices`' existing notification rows through this choke
  point (no new generator needed — it already creates rows).

## Part 3 — New generators (backlog, prioritised)

Each is a scheduled console command that **creates notification-centre rows** (dispatch to
push then happens automatically via Part 2). Categories match Android filter keys.

1. **`tasks:remind`** (`category=task`) — VTODO items with a `due`/`DUE` in a lookahead
   window (e.g. due today / overdue / configurable lead time), not completed, throttled per
   task (mirror `reminded_at` pattern). Daily, plus optionally hourly for same-day timed
   dues. Ground truth: VTODO `due`, `related-to` (skip? include subtasks), `status`.
2. **`calendar:remind`** (`category=event`) — events with a VALARM whose
   `trigger = start - alarm_minutes_before` falls in the next tick. Needs a
   minute/5-minute cadence to honour arbitrary lead times; throttle per (event, alarm).
3. **`contacts:birthday-remind`** (`category=birthday`) — contacts with a `BDAY` matching
   today (+ optional lead, e.g. "in 3 days"). Daily 08:00. Year-agnostic match.

Note: the Android app does not render events/contacts/birthday targets (out of module
scope), so these notifications are informational there — tapping opens the centre, no
deep target screen. That is acceptable.

## Part 4 — Preferences (optional, recommended)

Per-user notification preferences so users can disable categories or set lead times:
- Extend `PUT /api/v1/preferences` (or `/settings`) with a `notifications` block:
  `{ "<category>": { "push": bool, "lead_minutes": int } }`.
- `SendPushJob` and generators honour it. Android already filters per category locally as a
  fallback, but server-side honouring saves needless pushes.

## Scheduler additions (`routes/console.php`)

```
Schedule::command('tasks:remind')->dailyAt('07:00')->withoutOverlapping();
Schedule::command('tasks:remind:timed')->everyFifteenMinutes()->withoutOverlapping(); // same-day timed dues
Schedule::command('calendar:remind')->everyFiveMinutes()->withoutOverlapping();
Schedule::command('contacts:birthday-remind')->dailyAt('07:00')->withoutOverlapping();
```
(`invoices:remind` daily 08:00 stays; now fans out via Part 2.)

## Security / privacy

- Payload travels through the (self-hosted) ntfy server in plaintext. Acceptable under the
  post-ZK, server-holds-plaintext trust model. If stricter privacy is wanted later, adopt
  UnifiedPush WebPush encryption (VAPID + client public key from `PushEndpoint.pubKeySet`);
  the Android client already receives that key and can pass it up — additive, no breaking
  change.
- Rate-limit endpoint registration; validate endpoint is https and (optionally) on an
  allowed host. Prune dead endpoints on 404/410.
