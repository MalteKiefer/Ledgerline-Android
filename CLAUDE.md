# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline**. **Server-Pivot v1.5xx (2026):
das Zero-Knowledge-/Vault-/Sealed-Store-Modell wurde vollständig entfernt.** Der Server ist jetzt
eine **plaintext-relationale API** (Laravel-13, `/api/v1`) mit den Modulen `files, finance, contacts,
calendar, notes` (`config/modules.php`). **Diese Android-App bildet ab: Dateien (Files) +
Finanzen (Finance) + Aufgaben (Todos = nur der VTODO-Task-List-Teil des Calendar-Moduls, keine
Events) + Notizen (Notes) + Galerie (Gallery, seit 2026-08-13 im Aufbau — Phase 1 MVP-Viewing)** —
plus alle Nutzer-Einstellungen. Kalender-Events + Kontakte bleiben ausgeschlossen.
Web-App = Referenz/Superset; iOS = Look-&-Feel-Referenz. (Rebuild-Basis war der Branch
`finance-pivot`; Files wurde 2026-08 neu ergänzt, Todos 2026-08-10, Notes 2026-08-10.)

> **Diese Datei MUSS den aktuellen Stand widerspiegeln. Nach jeder Änderung pflegen.**
> Ground truth API: `../ledgerline/openapi.yaml` + `../ledgerline/routes/api.php`.
> Ground truth Logik/Design: `../ledgerline` Web + `../ledgerline-ios`.

---

## 0. Architektur-Prinzip (NEU)

**Plaintext-relational.** Jedes Modul = relationale Tabelle über per-record REST. Sensible Spalten
sind **server-seitig** at-rest verschlüsselt (Laravel `encrypted`-Cast, APP_KEY) und kommen
**entschlüsselt über TLS** zurück — der Client sendet/empfängt **Klartext**. **Kein Client-Crypto,
kein Vault, kein Vault-Key, keine sealed Manifeste, kein Passphrase-Unlock.** Analytics
(USt-Voranmeldung, KPIs, Duplikate, Kategorie-Vorschläge) werden **server-berechnet**.

Konsequenz: das Bearer-Token gibt **direkten** Datenzugriff. Es gibt nur einen **lokalen
biometrischen App-Lock** (Datenschutz am Gerät), keine kryptografische Entsperrung.

---

## 1. Basis-URL & TLS

Self-hosted, variabel — die App bekommt die Base-URL aus dem QR-Pairing (§2). Nur HTTPS,
`RESTRICTED_TLS` + TOFU-SPKI-Pinning (fail-closed, `CertificatePinner`, bei Pairing gesetzt).

## 2. Authentifizierung — QR Device Pairing (unverändert)

1. Web-Profil erzeugt QR mit Deep-Link `ledgerline://pair?url=<base>&code=<one-time>`.
2. App scannt (CameraX + ZXing), `POST /auth/pair {code, device_name}` → `{status:'pending'}`.
3. Web bestätigt. App pollt `POST /auth/pair/collect {code, install_id?, app_version?, os_version?}`
   → `pending` … dann **einmalig** `approved` → `{token, user}`; danach `410`.
4. Token wird **Keystore-versiegelt** gespeichert (ein biometrischer `CryptoObject`-Prompt beim
   Pairing autorisiert das Siegeln). Header `Authorization: Bearer <token>` für alle `/api/v1`.
5. **Kein Refresh** — widerrufen/verloren → neu pairen. Rate-Limit `/auth/pair*`: 30/min/IP.

**App-Lock:** Beim Öffnen liest **ein** biometrischer/Geräte-PIN-Prompt das versiegelte Token in den
`SessionHolder` (`AppLockScreen`/`AppLockViewModel`). Hintergrund (`ON_STOP`) + Idle-Timeout →
`AppLockState.lock()` → Lock-Screen. `FLAG_SECURE` app-weit (Release). Remote-Wipe-Kill-Switch:
`GET /me` `wipe:true` → `AuthEventBus.wipe` → `ForceLogout` (alles lokal löschen + neu pairen);
auch bei authentifiziertem `401` (widerrufenes Token).

## 3. API — `/api/v1` (Files + Finance)

**Konto/Gerät:** `GET /me` (`{user, wipe}`; `user.modules ⊆ {files, finance, contacts, calendar}` —
`ModuleAccess` blendet Tabs für nicht erlaubte Module aus; `groups`=["admin"] → Admin), `GET /avatar`,
`POST /device/heartbeat`, `DELETE /auth/session`, `GET/DELETE /devices`, `POST /devices/{t}/wipe`,
`GET /notifications` (+read/read-all), `GET /account/export`, `DELETE /account`,
`DELETE /account/sessions/{id}`, `POST /locale` · `/theme` · `/preferences`, `GET/PUT /settings`,
`/user/two-factor/*`, `PUT /user/password`, `/device-pairings/*` (owner-side).

**Finance** (alle `Bearer`, `403` wenn Modul deaktiviert):
- `GET /finance/data` → Snapshot `{invoices, partners, paymentMethods, projects, financeCategories,
  transactions}`.
- **Analytics (read-only, server-berechnet):** `GET /finance/reports?year` (VatReturn/KPIs/customers/
  months), `/finance/reports/account-vat?account_id&year`, `/finance/duplicates`,
  `/finance/category-suggestions`, `/finance/trash`.
- **Per-record CRUD** (POST create, PUT update mit `version` → **409 `{error,version}`** bei Konflikt,
  DELETE = soft-delete, `POST …/{id}/restore`, `DELETE …/{id}/force`) für: `invoices` (+`/finalize`
  = gapless GoBD-Nr, `GET/POST /pdf`), `transactions` (+`/bulk` sig-dedup, `+/receipts` multipart,
  `/receipts/{r}/raw`), `partners`, `payment-methods`, `projects` (+`/move`), `categories`.
- `POST /invoices/ocr` (multipart, transient Klartext → `{text, source, pages}`).
- `GET/PUT /company` (Firmenprofil + Rechnungs-Defaults; PUT JSON oder multipart mit `logo`/
  `remove_logo`), `GET /company/logo`. Fremdbelege: `POST /finance/receipts` (multipart),
  `PUT/DELETE .../{id}` (+restore/force), `GET .../{id}/raw`.

**Files** (alle `Bearer`, `module:files`; Bytes = Klartext octet-stream, **kein Client-Crypto**):
- `GET /files/data` → `{folders[], files[], usage{used,quota?}, labels[]}` (flache Ordner mit
  `parent_id`; kein Paging/ETag). `GET /files/trash`, `GET /files/search?q`, `GET /files/stats`.
- **FileEntry:** `id, file_folder_id, name, mime, size, sha256, tags[], note, favorite, version`
  (optimistic → **409** `{error:version_conflict,version}`), `created/updated/deleted_at, labels[]`.
- Upload: `POST /files/entries` (multipart) **oder** chunked `upload/chunk/{init,part(8 MiB),
  complete,abort}`. `GET .../{id}/raw` + `.../thumb` (400² webp, 404 für Nicht-Bilder). Quota → **413**.
- `PUT .../{id}` (Metadaten), `.../toggle`, `.../content` (neue Version), Versionen (`versions`,
  `versions/{v}/{raw,restore}`), Trash (`restore`/`force`/`trash/empty`), `POST /files/zip`.
- Ordner CRUD + `.../{id}/move` (422 `cycle`). Labels CRUD + `POST .../{id}/labels {label_ids}`.
- Sharing (Datenschicht fertig, UI = „Rest"): `/files/rel-shares` (Public-Link, Token in
  `/file-share/{token}`, Passwort + HMAC-Grant), `/files/folder-shares` (+members viewer/editor),
  `/shared-with-me` (browse/raw/upload/rename/delete).

**Record-Shape:** int `id`, `version` (optimistic), `created_at/updated_at`, meist `deleted_at`.
Additiv/lenient dekodieren (`ignoreUnknownKeys`); offene Spalten (invoice `customer`/`lines`,
tx `receipts`, partner `contacts`) als rohes `JsonObject` halten.

## 4. Sicherheit (NEU, reduziert)

- **Kein Client-Crypto mehr.** Einziges persistiertes Secret: das Bearer-Token, **AES-256-GCM im
  Keystore** (`KeystoreSealer`, StrongBox wo verfügbar, `setUserAuthenticationRequired(true)` +
  per-use `CryptoObject`-Biometrie). `allowBackup=false`, Data-Extraction-Rules schließen Cloud/
  Device-Transfer aus.
- Transit: HTTPS erzwungen (`network_security_config` cleartext=false), TOFU-SPKI-Pinning fail-closed.
- `FLAG_SECURE` (Screenshot/Recents-Block, Release). Biometrischer App-Lock + Idle-Timeout +
  Hintergrund-Lock. Remote-Wipe-Kill-Switch. Kein Secret-Logging.
- **Bewusst NICHT mehr vorhanden** (mit dem ZK-Modell entfernt): Vault/Passphrase/VK, PQ-KEM,
  Remember-Vault, Duress-Wipe, Unlock-Throttle, Clock-Guard, Security-Audit-Log, Integrity-Attestation,
  Autofill/Passkey-Provider. Server hält die Daten (encrypted-at-rest via APP_KEY) — Vertrauen liegt
  jetzt server-seitig, nicht mehr zero-knowledge.

## 5. Tech-Stack

Kotlin + Jetpack Compose (Material 3 Expressive), Hilt, Retrofit + OkHttp (Bearer-Interceptor,
SPKI-Pinning, 429-Backoff, forced-logout-Interceptor bei 401), kotlinx.serialization
(`ignoreUnknownKeys`, snake_case via `@SerialName`), CameraX + ZXing (QR), AndroidX Biometric,
EncryptedSharedPreferences/Keystore, DataStore (UI-Prefs). `minSdk 36`, `targetSdk 36`,
`compileSdk 37`. Paket `de.ledgerline.app`. Versions-Policy: neueste stabile Libs (§`gradle/
libs.versions.toml`), material3 1.5.0-alphaXX bewusst adoptiert.

**Schichten:** `ui/*` (Compose + VMs), `domain/model/{finance,files}/*` + `domain/model/Session`,
`data/*` (Repos), `core/*`, `di/*`.

## 6. Android-Architektur (Ist)

- **Flow** (`ui/nav/AppNav`): `WELCOME → PAIRING → LOCK (biometrisch) → HOME (`ui/shell/AppShell`)`.
  `RootViewModel` gated auf `AppLockState.unlocked`; 401/Remote-Wipe → `ForceLogout` + re-pair.
- **`ui/shell/AppShell`** — Multi-Modul-Bottom-Nav **Dateien · Galerie · Finanzen · Aufgaben · Notizen ·
  Suche · Konto**, gated über `ModuleAccess` (`/me.modules`; Galerie-Tab nur bei Modul `gallery`,
  Aufgaben nur bei `calendar`, Notizen nur bei `notes`; Suche+Konto immer sichtbar). Suche = globale
  Cross-Modul-Suche (§7).
  Finance-Detail/Edit-Flows als `MoneyRoute`-Overlays.
- **Notizen (`ui/notes/`):** `NotesSection`/`NotesViewModel` — plaintext-relationales Notes-Modul
  (Markdown). Ordner-Filter-Chips, Volltextsuche (`GET /notes/search`), Notiz-Liste (Pin/Favorit/Tags),
  Quick-Add/Edit-Sheet (Titel + **Markdown-Body mit Edit/Vorschau-Toggle** via
  `com.mikepenz:multiplatform-markdown-renderer-m3`, Ordner-Picker, Tags), Ordner-Verwaltung, Trash
  (restore/force für Notizen + Ordner). `data/notes/NotesRepository` (online-only Snapshot
  `folders`/`notes`/`tags` + per-Record-CRUD, optimistic `version`→409) + `NetworkFactory.createNotes`
  + `NotesApi` (`/notes/data`|`/trash`|`/search` + CRUD + `/favorite`|`/pin`|`/restore`|`/force` +
  Ordner-CRUD). Body wird on-demand (`GET /notes/{id}`) für den Editor geladen.
  **Wikilinks/Backlinks:** `[[Titel]]` im Body wird in der Vorschau zu tappbaren internen Links
  umgeschrieben (`ll-wiki:`-Scheme via `LocalUriHandler`-Override → Titel→id-Auflösung gegen die
  geladenen Rows → öffnet Ziel-Notiz); Backlinks-Panel („Verlinkt von") aus `note.backlinks`
  (show/store/update liefern sie). **Attachments:** `note.attachments` (show), Editor-Sektion mit
  SAF-Upload (`POST /notes/{note}/attachments` multipart, MIME-Allowlist pdf/jpg/png/webp/gif),
  Öffnen via Cache-Download (`GET .../raw`) + `DocOpener`, Löschen. **Attach-from-Files:**
  `POST /notes/{note}/attachments/from {source:file,id}` bettet ein bestehendes Files-Bild/-Video ohne
  Re-Upload ein (Editor „Aus Dateien hinzufügen" → Picker über `FilesRepository`-Snapshot, image/video
  gefiltert). **Markdown-Export:**
  `GET /notes/{note}/export` (YAML-Frontmatter + Body) → SAF-`CreateDocument("text/markdown")`.
- **Aufgaben (`ui/todos/`):** `TodosSection`/`TodosViewModel` — VTODO-Task-Lists (nur der Task-Teil des
  Calendar-Moduls, keine Events). Listen-Auswahl (VTODO-Kalender via `GET /calendar/data`, filter
  `component==VTODO`; neue Liste = `POST /calendars {component:VTODO}`), Offen/Alle-Filter, Complete-
  Toggle, Editor (Titel/Notiz/Fällig-DatePicker/Ganztägig/Priorität/**Erinnerung** =
  `alarm_minutes_before`→VALARM, Web-Parität), Löschen. **Complete/Uncomplete** über die dedizierten
  Endpunkte `POST /calendar/todos/{id}/complete`|`/uncomplete` (server-seitig feld-erhaltend +
  **recurring roll-forward** von DUE; ersetzt das alte Voll-VTODO-Resend). **Listen-Sharing**
  `/calendar/shares` (GET/POST/DELETE): eine VTODO-Liste per E-Mail an registrierte Nutzer teilen
  (viewer/editor). **Listen-Verwaltung:** Top-Bar-Overflow bei ausgewählter Liste → Teilen /
  **Umbenennen** (`PUT /calendars/{id}`) / **Löschen** (`DELETE /calendars/{id}`, 422 bei letzter Liste).
  `data/calendar/TodosRepository` (online-only; per-Record REST mit DAV-`etag`-Optimistic-Concurrency)
  + `NetworkFactory.createCalendar` + `CalendarApi` (`/calendar/todos` CRUD + `/complete`|`/uncomplete`
  + `/reorder` + `/shares` + `/calendars` create/update/delete + ICS `/import`|`/export`). Bewusst
  weggelassen: Kalender-Events, Kontakte, Spezial-Kalender (`/calendars/special`), Feed-Regenerate.
- **Galerie (`ui/gallery/`) — Phase 1 (MVP-Viewing):** `GallerySection`/`GalleryViewModel` +
  `GalleryScreens` (Lightbox/Trash). Capture-Date-Timeline (`GET /gallery/data`, non-archived,
  COALESCE(taken_at,created_at) DESC), adaptives Thumbnail-Grid (`/{id}/thumb`, thumb=false →
  Processing-Spinner), Vollbild-Lightbox (`/{id}/preview` WebP, Fallback-Hinweis wenn pending; Video =
  „speichern zum Öffnen", Playback = Phase 2), EXIF-Sheet (`/{id}/exif`), Favorit (`/{id}/favorite`
  PATCH), Rotieren (non-invasiv `PUT /{id}` rotation), Löschen + **Multi-Select-Bulk** (`DELETE /{id}`,
  `/bulk-destroy`), Trash-Grid (`/trash`, `/{id}/restore`|`/force`, `/trash/empty`), SAF-Upload
  (multipart + chunked ≥32 MiB, Quota-413) + Download/Save (`/{id}/download`). `data/gallery/
  GalleryRepository` (online-only `StateFlow<GalleryData>` + In-Memory-Patch) + `NetworkFactory.
  createGallery` + `GalleryApi`. Tab gated über `/me.modules` „gallery". **Keyset-Pagination** (Server
  2026-08-14): `/gallery/data?limit&cursor&cursor_ym` liefert Seiten à 200 + `next_cursor`; `load()`
  holt Seite 1, `loadMore()` (Grid-Scroll-`snapshotFlow`) hängt an; Album/Archiv-Sub-Listen laufen alle
  Seiten via `fetchAllPages`; `/gallery/dates` Monats-Histogramm (Scrubber, Datenschicht). Server macht
  readiness (thumb/preview) jetzt DB-basiert (kein per-Row-Disk-Stat mehr). **Cache** (2026-08-14):
  cache-first Metadaten (`gallery_data.json` = erste Seite, sofort für cold-offline-Browse) +
  **Thumbnail-Disk-Cache** (`cacheDir/gallery_thumbs/{id}-{version}.webp` → schnelles Scrollen + offline
  Thumbs); `clear()`/Wipe löscht beides. Thumb/Preview-Fetch bewusst **flag-unabhängig** (Server-404
  = wirklich nicht bereit; `thumb_ready`-Backfill egal).
  **Phase 2 (fertig 2026-08-13):** Video-Playback (`/{id}/play` web-MP4 → pinned-Download → inline
  `VideoView`, kein Media3) + Live-Photo (`/{id}/motion`); **Archiv** (`/{id}/archive` PATCH,
  `/bulk-archive`, `GalleryArchiveScreen` + load `?archived=1`); **Alben** (`/albums` CRUD +
  `/albums/{id}/photos` attach/detach; `GalleryAlbumsScreen`/`GalleryAlbumGridScreen`, „zu Album
  hinzufügen" aus Multi-Select); **Datum-Navigation** (Monats-Section-Header im Grid) +
  **Capture-Date-Edit** (`PUT /{id}` taken_at via DatePicker im EXIF-Sheet).
  **Phase 4 People (2026-08-14, ML-gated):** `GalleryPeopleScreen`/`GalleryPersonPhotosScreen` +
  `GalleryPerson`-Modell — Personen-Cluster (`GET /gallery/people`) als Cover-Face-Avatare
  (`/gallery/faces/{id}/crop`), Umbenennen (`PUT /gallery/people/{id}` name), Löschen, **Merge**
  (`/gallery/people/merge`), Personen-Fotos (`GET /gallery/people/{id}`) + Lightbox. Top-Bar-Aktion in
  `GallerySection`. **Ohne Kontakt-Link** (kein Contacts-Modul). Leer wenn ML-Sidecar/Face aus.
  **Offen (Rest P4/P3/P5):** CLIP-Suche/Duplikate/Memories/reprocess + per-Photo-Face-Tagging
  (assign/hide); P3 Sharing (public/internal/upload-links/Kommentare/Reaktionen); P5 Geräte-Backup.
- **Files (`ui/files/`):** `FilesSection`/`FilesViewModel` — Ordner-Browser (Breadcrumb, gruppierte
  Listen), Upload (SAF, single+chunked), Datei-Detail mit Inline-Vorschau (Bild/Text) + Metadaten +
  Label-Zuweisung + Versionen, Trash, Suche, Statistik, Label-Verwaltung, **Favoriten-Filter, Label-
  Filter, Thumbnails, Quota-Balken, Multi-Select-ZIP, In-App-PDF-Viewer (`ui/common/PdfViewerScreen`
  via PdfRenderer)**, volles Sharing (Public-Links + Ablauf/Bearbeiten, Ordnerfreigaben +
  **Einzeldatei-Cross-User-Share** `kind=file`, für-mich-freigegeben inkl. Lone-File),
  **Trash restore/force für Ordner** (nicht nur Dateien), **Multi-Select Bulk copy/move/delete**
  (`POST /files/entries/{id}/copy` + per-record move/delete; Selection-Bar), **„Von mir geteilt"**
  (`SharedByMeScreen`: Public-Links-Index `GET /files/rel-shares` mit Copy/Revoke + **Inbound-Upload-Links**
  `GET/POST/DELETE /files/upload-links`, Public-Seite `{base}/u/{token}`). `data/files/FilesRepository`
  (**cache-first** Snapshot: Plaintext-Disk-Cache `files_data.json` in `filesDir`, sofort publiziert +
  bei Erfolg überschrieben → Ordner/Datei-Browser funktioniert cold-offline [Bytes/Thumbs brauchen
  weiter Netz]; `clear()`/Wipe löscht den Cache; per-Record-CRUD patcht Snapshot+Cache,
  `NetworkFactory.createFiles` + `FilesApi`); Download →
  `DocOpener.openFile` (FileProvider). **Info-Panel** (`GET /files/entries/{id}/info`): Metadaten
  (EXIF/PDF/STL/Text via `FileInfoMetadata`), SHA-256, Pfad, Versionszahl, Inhalts-Snippet,
  Share-Status, Duplikate + Aktivität als Dialog im Detail-Screen. **Aktivitäts-Feed**
  (`GET /files/activity` + `/entries/{id}/activity`, `FileActivity`) in der Datenschicht/VM.
  **Offen (Rest):** Aktivitäts-Feed-Screen; externe Mounts (`/mounts/*`
  S3+SFTP, bewusst verschoben).
- **`ui/money/FinanceSection`** — Top-Tab-Row (Dashboard/Rechnungen/Umsätze/Mehr) über geteiltes
  **`FinanceViewModel`**; „Mehr" → Partner/Zahlungsmittel/Projekte/Belege(Fremdbelege)/Insights/
  Firmenprofil. Dashboard (Server-KPIs/USt/Top-Kunden), Rechnungen (Anlegen/Bearbeiten/Finalize/
  Storno/Mahnung/PDF), Umsätze (+Belege/CSV-Import).
- **`ui/money/MoneySettingsScreen`** (Konto-Tab) — Profil, Darstellung (Theme + **Sprache** via
  Android-13-`LocaleManager` + `/locale`), **Dateien** (`file_max_versions` via `GET/PUT /settings`),
  Geräte, Notifications, About, Security (Passwort/2FA/**Recovery-Codes**/Export-SAF/Löschen), Logout.
- **Push (`ui/push/` bzw. `push/`):** **UnifiedPush** (`org.unifiedpush.android:connector`, F-Droid, kein
  FCM). `LedgerlinePushService : PushService` empfängt server-gepushte `PushPayload`
  (`{id,category,level,title,body}`) und zeigt sie via `PushNotifier` (Channels pro `level`,
  Lockscreen-Visibility Toggle) als System-Notification; Tap → `DeepLinkBus` → Konto→Benachrichtigungen.
  `PushRegistrar` wählt Distributor + meldet den Endpoint an den Server (`POST/DELETE
  /device/push-endpoint`, **serverseitig noch offen** — Spec `docs/superpowers/specs/…-server-design.md`).
  **Empfang braucht keinen Bearer-Token** (Biometrie-Siegel unberührt); der Endpoint wird nur bei
  entsperrter App gesendet (`SessionHolder` in-memory), sonst in `SettingsStore` gequeued + bei Unlock
  geflusht (`MainActivity`). Push-Prefs (enabled/Kategorie-Mute/Lockscreen-Inhalt) in `SettingsStore`;
  **Kategorie-Mute wird server-synchronisiert** (`/me.preferences.notifications` lesen → hydrieren;
  Toggle → `POST /preferences {notifications:{cat:{push}}}`, damit `SendPushJob` an der Quelle filtert).
  `PushFilter` = reine, getestete Parse-/Filter-Logik (`PushFilterTest`).
- **`data/finance/FinanceRepository`** — cache-first Read (Klartext-Disk-Cache `finance_data.json`) +
  Online-CRUD (patcht In-Memory-Snapshot `StateFlow<FinanceData>` + Disk), live Analytics/Company.
  `NetworkFactory.createFinance` (+ `FinanceApi`). **Offline:** Reads aus Cache; **Writes offline
  liefern derzeit NETWORK-Fehler** (kein Datenverlust) — Offline-Write-Queue = TODO.
- **`core`:** `AppLockState`, `SessionHolder`, `AuthEventBus`, `ServerReachability` (GET /up →
  Offline-Modus), `ModuleAccess`, `AvatarCache`, `AccountSnapshotCache`, `prefs/DisplayPrefs`,
  `security/{KeystoreSealer,VaultAuthorizers,AppLock,BiometricAvailability}`, `offline/Connectivity`.
- **`data`:** `AccountRepository` (/me, avatar, devices, notifications, account, preferences, heartbeat),
  `SessionStore` (Keystore-sealed Token), `PairingRepository`, `SettingsStore` (timeout/theme/keep-
  screen/display-prefs), `ForceLogoutImpl`, `remote/LedgerlineApi` (Konto/Gerät — enthält noch tote
  Alt-Endpunkte, TODO trimmen) + `FinanceApi` + dto.
- **`i18n`:** EN/DE/RU (`values[-de|-ru]/strings.xml`). Neue Strings in allen dreien.
- **Design:** `ui/theme/Brand.kt` (`Brand` accent-gradient indigo→violett, tints, `cardSurface()`,
  `IconChip`, `HeroIcon`, `PrimaryGradientButton`, `LedgerlineBackground`), `ui/common/{AppScaffold,
  AppTopBar,SectionLabel}`. `FLAG_SECURE` → visuelle Änderungen muss der Nutzer am Gerät prüfen.

## 7. Stand & TODO

**Server-Sync-Abgleich 2026-08-13 (Server v1.663 vs. Android @`a04e87ca`):** OpenAPI + letzte 100
Server-Commits geauditet. Out-of-scope (Gallery/Contacts/Kalender-Events/Admin/Docker/Passkeys/
FrankenPHP-Infra) bewusst ignoriert. Umgesetzt: **Files-Info-Panel** (`/files/entries/{id}/info`) +
**Aktivitäts-Feed** (`/files/activity` +per-file) Datenschicht/Dialog; **Notes attach-from-Files**
(`/notes/{note}/attachments/from`); **globale Suche** (`GET /search`, `GlobalSearchResponse`) mit eigener **Bottom-Nav-Sektion „Suche"**
(`ui/search/GlobalSearchScreen` + `GlobalSearchViewModel`, debounced, Gruppen files/notes/finance,
Tap öffnet den Record: files → `FileDetailScreen`, notes → Notiz-Editor, finance → Rechnung
(`MoneyRoute.InvoiceEdit`; die Server-Suche liefert für finance nur Invoices) — `AppShell` reicht die
Ziel-id via `openFileId`/`openNoteId`/`openInvoiceId` in die Sektion) + **Reindex** (`POST /me/reindex`,
Settings-Button) in
`AccountRepository`; Prefs `timezone`+`date_format` additiv in `DisplayPrefsDto` (round-trip-sicher).
Kein Finance-Drift (GoCardless serverseitig wieder entfernt). `assembleDebug` + Unit-Tests grün.

**P1–P3-Nachzug 2026-08-13 (umgesetzt):**
- **P1 Force-2FA-Gate:** Server-403 `{status:"two_factor_required"}` (Policy `force_2fa`) wird im
  OkHttp-Interceptor via `peekBody` erkannt (`AuthNotifier.onTwoFactorRequired` → `AuthEventBus.
  twoFactorRequired`) → neue `Destination.TWO_FACTOR` in `AppNav` → `ui/auth/TwoFactorRequiredScreen`
  (Passwort-Step-up → TOTP-Secret → Confirm, wiederverwendet `AccountViewModel`; kein Enrollment-Loop,
  bei Erfolg `toHome()`, plus Logout).
- **P2 Devices-Detailfelder:** `DeviceDto` +`ip`/`last_used_at`/`created_at`/`expires_at`/`os_version`/
  `app_version`/`abilities`; ausklappbares Detail-Panel im Devices-Screen. **Passwort-Policy:**
  `changePassword` liefert jetzt die 422-Server-Meldung (`parseValidationMessage`) statt nur generischem
  Fehler; Security-UI zeigt sie an.
- **P3 Files-Aktivitäts-Screen** (`FilesActivityScreen`, Overflow-Menü) + **Datumsformat-Picker**
  (Appearance-Settings; `DisplayPrefs.dateFormat`/`timezone` durch Sink/Store/`/preferences`
  round-trip, `setDateFormat` synct server).

**Nachgezogen (2026-08-13, 2.+3. Runde):** Todos = dedizierte complete/uncomplete (recurring
roll-forward) + Listen-Sharing `/calendar/shares`; globale Suche = record-level Deep-Open für **alle**
in-scope Module (files→Detail, notes→Editor, finance→Rechnung); **Timezone-Picker** (durchsuchbare
IANA-Zonenliste + „Systemzeitzone", `setTimezone`→`/preferences`). `assembleDebug` + Tests grün.

**Offen (Rest):** externe Mounts `/mounts/*` (S3+SFTP, großes eigenes Feature — noch nicht gebaut).
`/files/entries/{id}/show` bewusst ungenutzt (Flat-Snapshot deckt Files-Deep-Open ab).
**Bewusst out-of-scope:** Gallery, Contacts, Kalender-Events (rsvp/imip/free-busy/slots), Admin, Passkeys, Docker.



**Plaintext-Rebuild 2026-08 (Files + Finance):** Auf `finance-pivot` (ZK bereits entfernt) aufgesetzt.
Neu: **Files-Modul** komplett (Datenschicht `FilesModels`/`FilesApi`/`FilesRepository` + Browser/
Detail/Trash/Suche/Stats/Labels-UI), **Multi-Modul-Shell** `AppShell` (Tabs gated über `/me.modules`),
**Finance-Abgleich** auf aktuellen Server (`standaloneReceipts`/Fremdbelege, tx `deleted_at`, Partner
`hourly_rate`/`currency`, Company `website`/`font`/`vat_ist`), **Settings** komplettiert (Sprache,
`file_max_versions`, Recovery-Codes). `assembleDebug` + Unit-Tests grün.

**Auth-Umbau (2026-08): QR-Pairing → direkter Login** (URL + E-Mail + Passwort + optional 2FA, `POST
/auth/login`; `data/LoginRepository` + `ui/auth/Login*`; Kamera/QR/Standort/Kontakte-Perms entfernt).
Web-Seite setzt Login-Token als Gerät (spec `$HOME/Downloads/ledgerline-mobile-login-spec.md`,
serverseitig umgesetzt).

**Web-Paritäts-Offensive (2026-08, 8 Batches):** OpenAPI + Web-SPA gegen Android geauditet (API-Schicht
war schon 100 % abgedeckt). Umgesetzt: Files (Tags/Notiz-Edit, Favoriten, Label-Filter, Thumbnails,
Quota-Balken, Multi-Select-ZIP, Share-Ablauf/-Edit, In-App-PDF-Viewer), Finance (Kategorie-Verwaltung,
Fremdbelege im Trash, Dashboard-Tiefe [YoY/Aging/Monatschart/volle Kundenliste], Rechnung Partner-
Auswahl + Als gesendet/bezahlt, tx↔Rechnung-Link, Beleg-Metadaten-Edit, Firmen-Branding-Felder +
Kontaktpersonen, Partner-Kontaktpersonen, Rechnungssuche, Projekt-Nesting, Quartals-USt, Konto-USt).
Datenmodell-Fixes: `company_contacts`, `InvoiceAging.buckets`, `FinanceTrash.standaloneReceipts`.
**Bewusst weggelassen (User):** client-seitige Rechnungs-PDF-Erzeugung (bleibt web-seitig).
**Share-Target (2026-08-14):** `ACTION_SEND`/`SEND_MULTIPLE`-Intent-Filter an `MainActivity`; die
geteilten URIs werden sofort (unter dem lebenden Read-Grant) in `cacheDir/shared` kopiert und im
`core/ShareInbox`-Singleton veröffentlicht; die entsperrte `AppShell` blendet ein `ui/share/
ShareUploadSheet` ein (Ziel **Dateien** [root] oder **Galerie** [nur Bild/Video, wenn Modul aktiv]) →
`FilesRepository`/`GalleryRepository`-Upload, danach Inbox geleert. Kein separater Biometrie-Unlock —
läuft über den bestehenden Lock-Flow.
**Offen (Rest):** MT940/CAMT-Import, mehr Tests. **On-device-Verifikation
offen** (FLAG_SECURE → visuell am Gerät prüfen).

**Push-Notifications (2026-08-10) — Android fertig, Server offen:** UnifiedPush-Client komplett
(siehe §6 Push; Client-Build + `PushFilterTest` grün). Zustellung = kein FCM (F-Droid), Server pusht
fertigen Payload → App zeigt an, ohne Bearer-Token → Biometrie-Siegel unberührt. **Server-Teil = eigenes
Spec** `docs/superpowers/specs/2026-08-10-push-notifications-server-design.md`: `POST/DELETE
/device/push-endpoint` + Dispatch-Choke-Point (jede Notification-Zeile → SendPushJob an ntfy-Endpoints)
+ neue Generatoren `tasks:remind`/`calendar:remind`/`contacts:birthday-remind` (Rechnungs-Dunning läuft
schon). **On-device-Verifikation offen:** ntfy installieren → Push aktivieren → Test-Push → System-
Notification → Tap → Center.

**Finance-Basis (aus finance-pivot):** Datenschicht (Modelle + `FinanceApi` + Repository cache-first/CRUD + Offline-Write-Queue
`FinanceOutbox` für update/delete), biometrischer App-Lock, Finance-Shell + alle Kern-Screens, Nav,
Manifest/DI/Entry-Points auf finance-only rewired, ZK-Stack gelöscht (355→~77 Quell-Dateien). **4b
UI-Tiefe weitgehend erledigt:** Dashboard-Jahr-Picker, Rechnungs-Positionen-Editor (Live-Summen),
importierte Rechnung (Read-only-Kernfelder + Original-PDF via FileProvider/`DocOpener`), PDF-Upload,
Belege anhängen/anzeigen/löschen (multipart), Bulk-CSV-Import (`core/finance/BankCsv`), OCR-Scan,
Insights (Duplikate + Kategorie-Vorschläge), Account-Security (Passwort/2FA/Export-SAF/Löschen),
Geräte + Notifications + Theme. Dead-Libs entfernt (lazysodium/PdfBox/BouncyCastle/mapsforge/Media3/
credentials/autofill/documentfile). `LedgerlineApi` + tote dto getrimmt. Tests: `FinanceOutboxTest`,
`BankCsvTest`. `assembleDebug` + Unit-Tests grün. **On-device-Verifikation weiter offen** (Gerät fiel
wiederholt beim Install ab).

**v1.528 (Invoice-Lifecycle + Tax-Core) umgesetzt:** Invoice-Felder (credit_note/discount/skonto/
dunning/invoice_email), Company `small_business` (§19), Category color/icon (Modell); Endpunkte
`/reports/vat-advance`, `/reports/euer`, invoices `/email` `/storno` `/dun`. UI: Rabatt/Skonto +
Lifecycle-Aktionen im Editor, Tax-Reports in Insights, §19-Switch im Firmenprofil.

**TODO (Rest):**
- **On-device:** Pairing (QR) → biometrisch entsperren → `/finance/data` lädt → Rechnung/Umsatz
  anlegen/PDF/Beleg/CSV-Import/Storno/Mahnung durchklicken. FLAG_SECURE → visuell prüfen.
- **Bulk-Import:** MT940/CAMT.053-Formate (nur CSV fertig).
- **Offline-Create** (provisional int-id-Remap über Fremdschlüssel) — bewusst verschoben (riskant).
- **Firmenlogo** Upload/Anzeige (multipart `logo`/`remove_logo`); Kategorie-Farbe/-Icon-Picker;
  ungenutzte xml/drawables der gelöschten Features entfernen; mehr Finance-Unit-Tests.

## 8. Referenzen
- API: `../ledgerline/openapi.yaml` · `../ledgerline/routes/api.php`
- Web (Logik/Design): `../ledgerline` · iOS: `../ledgerline-ios`
