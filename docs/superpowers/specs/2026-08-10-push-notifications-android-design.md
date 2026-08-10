# Push Notifications (Android) — Design

**Date:** 2026-08-10
**Status:** Approved, in implementation
**Repo:** ledgerline-android
**Companion spec:** `2026-08-10-push-notifications-server-design.md` (server-side generators + dispatch)

## Goal

Surface Ledgerline notifications (invoice due/dunning, and later task-due, calendar
alarms, birthdays) to the user as real Android system notifications — working in the
background, on a de-Googled / F-Droid device, without weakening the biometric token seal.

## Key constraint

The bearer token is Keystore-sealed with `setUserAuthenticationRequired(true)` and
per-use `CryptoObject` biometrics (validity 0). A background worker therefore **cannot
decrypt the token** to poll `GET /notifications`. Silent background polling is impossible
under the current seal model. This rules out polling as the delivery mechanism.

## Decision: UnifiedPush

Push delivery does not need the app's token — the server sends a display-ready payload to
the device's push endpoint; the app just renders it. This leaves the biometric seal
untouched and works on F-Droid.

- Transport: **UnifiedPush** connector `org.unifiedpush.android:connector:3.3.3`.
- Distributor: the user's own UnifiedPush distributor app (e.g. **ntfy**, which the
  Ledgerline server already speaks). No FCM, no Google.
- Graceful degrade: no distributor installed → foreground-only refresh of the in-app
  notification centre (poll while app is open + unlocked). A banner explains how to enable
  real push (install a distributor).

## Data flow

```
Server (Laravel) --publish--> ntfy (self-hosted) --push (no token)--> Android
  new notification row                                                  PushService.onMessage
                                                                          -> system notification
                                                                          -> tap -> App -> Lock -> Centre
```

## Android components

New package `de.ledgerline.app.push`:

- **`LedgerlinePushService : PushService`** (`@AndroidEntryPoint`) — the UnifiedPush v3
  service. Callbacks:
  - `onMessage(message, instance)` → parse JSON payload → `PushNotifier.show(...)`.
  - `onNewEndpoint(endpoint, instance)` → hand the URL to `PushRegistrar` (send now if the
    session is live, else stash for the next unlock).
  - `onRegistrationFailed(reason, instance)` / `onUnregistered(instance)` → update
    `PushPrefs` status.
- **`PushRegistrar`** — orchestrates `UnifiedPush.register/unregister`, distributor
  selection (`getDistributors`, `saveDistributor`, `tryUseCurrentOrDefaultDistributor`),
  and endpoint delivery to the server. Flushes any pending endpoint when the app becomes
  unlocked/foregrounded (session token is in memory only while unlocked).
- **`PushNotifier`** — creates notification channels (one per `level`:
  info/success/warning/error → Importance), maps a payload to a `NotificationCompat`
  notification, requests `POST_NOTIFICATIONS` at first enable, builds the deep-link
  `PendingIntent` into the notification centre. Lockscreen visibility defaults to
  `VISIBILITY_PRIVATE` (title only); user toggle promotes to `VISIBILITY_PUBLIC`.
- **`PushPrefs`** (DataStore, mirrors `DisplayPrefs` pattern) — `enabled`, per-category
  filter set, `showContentOnLockscreen`, `pendingEndpoint`, `lastSentEndpoint`,
  `distributor`, `status`.

Payload (JSON, sent by server through the endpoint):
`{ "id": <int>, "category": "<string>", "level": "info|success|warning|error",
   "title": "<string>", "body": "<string?>" }`
Decoded leniently (`ignoreUnknownKeys`). Category filtered client-side against `PushPrefs`.

## Reuse (already present)

- In-app centre: `AccountViewModel.notifications` + `NotificationsScreen`
  (`ui/money/SettingsScreens.kt`, Konto → Benachrichtigungen) — list + mark-read. Unchanged
  except a new **Push** settings block is added at its top.
- `SessionHolder` (in-memory token while unlocked), `AppLockState` (unlock signal to flush
  a pending endpoint), `AccountRepository` (add `registerPushEndpoint`/`clearPushEndpoint`).

## Networking

Add to `LedgerlineApi` (server side documented in companion spec):
- `POST /api/v1/device/push-endpoint { endpoint }`
- `DELETE /api/v1/device/push-endpoint`
`AccountRepository` wraps both via the existing `call {}`/`apiProvider(session)` helpers.
Android ships ahead of the server; a 404 is treated as "server not ready", status surfaced
in settings, retried on next enable/unlock.

## Settings / UX

Konto → Benachrichtigungen, new **Push** block:
- Registration status line (registered / no distributor / server-not-ready / failed).
- "Push aktivieren" switch → picks distributor (`tryUseCurrentOrDefaultDistributor`, else a
  chooser over `getDistributors`) → `register`.
- Per-category toggles (local filter over incoming payloads).
- "Inhalt auf Sperrbildschirm zeigen" toggle.
- No-distributor state: explainer + link hint to install ntfy.

Bonus: unread badge on the Konto bottom-nav tab.

## Manifest

```xml
<service android:name=".push.LedgerlinePushService" android:exported="false">
    <intent-filter>
        <action android:name="org.unifiedpush.android.connector.PUSH_EVENT"/>
    </intent-filter>
</service>
```
`POST_NOTIFICATIONS` permission already declared.

## Deep link

`MainActivity` (already `singleTask`) reads an intent extra
`open=notifications` set by the notification `PendingIntent`; after unlock, `AppNav`
routes to the Konto notification centre.

## Testing

- Unit: payload parse (valid/garbage/unknown-keys), `level`→Importance mapping, category
  filter, endpoint diff / re-register logic (`onNewEndpoint` idempotence,
  pending-vs-sent), lockscreen visibility mapping.
- Manual on-device (FLAG_SECURE, visual): install ntfy → enable push → server test push →
  system notification appears → tap → unlock → centre. No-distributor degrade path.

## Out of scope (v1)

- Server generators for task-due / calendar / birthday (companion spec backlog).
- WebPush payload encryption (`vapid`/`KeyManager` path) — self-hosted ntfy + plaintext
  payload is acceptable; can be added later without API change.
