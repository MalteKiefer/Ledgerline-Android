# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline**. **Server-Pivot v1.5xx (2026):
das Zero-Knowledge-/Vault-/Sealed-Store-Modell wurde vollständig entfernt.** Der Server ist jetzt
eine **plaintext-relationale API** (Laravel-13, `/api/v1`) mit den Modulen `files, finance, contacts,
calendar, notes` (`config/modules.php`). **Diese Android-App bildet bewusst nur ab: Dateien (Files) +
Finanzen (Finance) + Aufgaben (Todos = nur der VTODO-Task-List-Teil des Calendar-Moduls, keine
Events) + Notizen (Notes)** — plus alle Nutzer-Einstellungen. Kalender-Events + Kontakte bleiben ausgeschlossen.
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
- **`ui/shell/AppShell`** — Multi-Modul-Bottom-Nav **Dateien · Finanzen · Aufgaben · Notizen · Konto**, gated
  über `ModuleAccess` (`/me.modules`; Aufgaben-Tab nur bei Modul `calendar`, Notizen nur bei `notes`).
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
  (show/store/update liefern sie). Server-Follow-up **Attachments** noch offen.
- **Aufgaben (`ui/todos/`):** `TodosSection`/`TodosViewModel` — VTODO-Task-Lists (nur der Task-Teil des
  Calendar-Moduls, keine Events). Listen-Auswahl (VTODO-Kalender via `GET /calendar/data`, filter
  `component==VTODO`; neue Liste = `POST /calendars {component:VTODO}`), Offen/Alle-Filter, Complete-
  Toggle, Editor (Titel/Notiz/Fällig-DatePicker/Ganztägig/Priorität/**Erinnerung** =
  `alarm_minutes_before`→VALARM, Web-Parität), Löschen. `data/calendar/
  TodosRepository` (online-only; per-Record REST mit DAV-`etag`-Optimistic-Concurrency; Complete/Edit
  senden das volle VTODO neu, da PUT rebuildet) + `NetworkFactory.createCalendar` + `CalendarApi`
  (`/calendar/todos` CRUD + `/reorder` + ICS `/import`|`/export`). Bewusst weggelassen: Kalender-Events,
  Kontakte.
- **Files (`ui/files/`):** `FilesSection`/`FilesViewModel` — Ordner-Browser (Breadcrumb, gruppierte
  Listen), Upload (SAF, single+chunked), Datei-Detail mit Inline-Vorschau (Bild/Text) + Metadaten +
  Label-Zuweisung + Versionen, Trash, Suche, Statistik, Label-Verwaltung, **Favoriten-Filter, Label-
  Filter, Thumbnails, Quota-Balken, Multi-Select-ZIP, In-App-PDF-Viewer (`ui/common/PdfViewerScreen`
  via PdfRenderer)**, volles Sharing (Public-Links + Ablauf/Bearbeiten, Ordnerfreigaben +
  **Einzeldatei-Cross-User-Share** `kind=file`, für-mich-freigegeben inkl. Lone-File),
  **Trash restore/force für Ordner** (nicht nur Dateien). `data/files/FilesRepository` (online-only Snapshot + per-Record-CRUD,
  `NetworkFactory.createFiles` + `FilesApi`); Download → `DocOpener.openFile` (FileProvider).
  **Offen (Rest):** Share-Target (`ACTION_SEND`).
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
**Offen (Rest):** Share-Target (`ACTION_SEND`), MT940/CAMT-Import, mehr Tests. **On-device-Verifikation
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
