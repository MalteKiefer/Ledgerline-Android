# Timed Task Reminders (Server) — Design Spec

**Date:** 2026-08-10
**Status:** Spec only — to be implemented in the `../ledgerline` (Laravel 13) repo
**Companions:** `2026-08-10-push-notifications-server-design.md`,
`2026-08-10-push-notifications-android-design.md`

## Problem

The task editor (web, and now Android) lets the user set a per-task reminder
("Erinnerung: 15 Minuten vorher"). The server **already stores** it: the API accepts
`alarm_minutes_before` (validated `0..40320`) and `CalendarTodoService` writes it as a
`VALARM` (`TRIGGER:-PT{n}M`) inside the VTODO's ICS, and reads it back via
`alarmMinutes($ics)`.

**But nothing fires it at trigger time.** The two existing task/reminder generators do not
cover the VTODO alarm:

- `tasks:remind` — daily 07:00, **date-level only** (due today / overdue), throttled per
  task per due-date. It ignores `alarm_minutes_before` entirely.
- `calendar:remind` — every 5 min, time-precise, but scans **`CalendarEvent` (VEVENT) only**.

So a task with "15 minutes before" is saved and round-trips in the editor, yet **no push
ever arrives at the reminder time**. This spec closes that gap.

## Goal

Fire a notification-centre row (→ push via the existing `AppNotification::record` →
`SendPushJob` choke point) when a VTODO's reminder trigger — `due − alarm_minutes_before` —
falls in the current tick. Category `task`, matching Android's category filter and the daily
digest.

## Design — mirror `RemindCalendar` for VTODO

New command **`tasks:remind-alarms`** (keep the name distinct from the date-level
`tasks:remind`). Cadence: **every 5 minutes**, with a lookback window to absorb scheduler
drift — the same shape as `RemindCalendar`.

```
Schedule::command('tasks:remind-alarms')->everyFiveMinutes()->withoutOverlapping();
```

### Handle

```
$now = CarbonImmutable::now()->utc();
$lookback = $now->subMinutes(max(1, (int)$this->option('lookback')));   // default 10

$todos = CalendarTodo::query()
    ->whereNotNull('due')
    ->whereNotIn('status', ['COMPLETED', 'CANCELLED'])
    ->get();

foreach ($todos as $todo) {
    $alarm = app(CalendarTodoService::class)->alarmMinutes($todo->ics);   // null if no VALARM
    if ($alarm === null || $alarm < 0 || $alarm > 40320) continue;

    $due = $todo->due;                       // CarbonImmutable, UTC
    $trigger = $due->subMinutes($alarm);

    // Fire only when the trigger crossed within this tick's window (lookback, now].
    if ($trigger->greaterThan($now) || $trigger->lessThanOrEqualTo($lookback)) continue;

    // One reminder per (task, due) — re-arms if due moves (recurring roll-forward).
    $key = 'tasks:remind-alarms:'.$todo->id.':'.$due->toIso8601ZuluString();
    if (! Cache::add($key, 1, now()->addDays(2))) continue;

    $title = __('notifications.task_due', ['task' => $todo->summary ?? '—']);
    $body  = __('notifications.task_reminder_at', ['time' => $due->toDateTimeString()]);
    AppNotification::record((int)$todo->user_id, 'info', $title, $body, 'task');
}
```

### Notes / edge cases

- **Anchor = `due`.** RFC-5545 VTODO VALARMs trigger relative to `DUE`. `alarm=0` fires at
  the due moment.
- **All-day tasks** (`all_day = true`): `due` is a date (00:00 UTC). Trigger =
  `midnight − alarm`. Acceptable; if a nicer "09:00 the day before" behaviour is wanted,
  clamp all-day triggers to a configurable local hour — out of scope for v1, note it.
- **Recurring tasks:** completing a recurring VTODO rolls `due` forward (existing behaviour),
  and the dedup key includes `due`, so the next occurrence re-arms automatically. No
  occurrence expansion is needed (unlike events) because the model carries a single live
  `due` at a time.
- **Timezone:** compare in UTC (as `RemindCalendar` does); `due` is stored UTC.
- **Throttle window:** 2-day cache TTL prevents double-fire across overlapping ticks and
  survives a missed tick within the lookback.

### Relationship to `tasks:remind`

Keep both — they are complementary:
- `tasks:remind-alarms` = precise, user-set per-task reminder (this spec).
- `tasks:remind` (daily 07:00) = date-level "due today / overdue" digest for tasks that have
  **no** alarm set (or as a safety net). Optionally skip tasks that already have a VALARM to
  avoid a double notification on the due day — recommended: in `tasks:remind`, `continue`
  when `alarmMinutes($todo->ics) !== null`.

## Delivery

No new delivery code: `AppNotification::record` already enqueues `SendPushJob`, which fans
out the `{id,category,level,title,body}` payload to each device's `push_endpoint`. The
Android client renders category `task`.

## i18n

Add one string: `notifications.task_reminder_at` (e.g. "Fällig am :time" / "Due at :time").
Reuse the existing `notifications.task_due` title.

## Test

1. Create a task due in ~6 min with reminder "5 minutes before".
2. Run `php artisan tasks:remind-alarms` at ~T-5 (or let the 5-min schedule tick) with a
   queue worker running.
3. Push arrives on the device; the notification-centre row exists.
Fast manual check: set due `now()+2min`, alarm `0`, run the command → immediate push.
