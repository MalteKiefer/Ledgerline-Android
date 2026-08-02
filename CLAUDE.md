# Ledgerline Android — Projekt- & Technik-Kontext

Native Android-Client für die selbst-gehostete **Ledgerline** Personal Cloud
(Dateien, Workspace = Notizen/Lesezeichen/Todos/Kontakte, Galerie). Der Server ist
ein Laravel-13-Backend mit einer versionierten Mobile-API unter `/api/v1`
(aktuell **Server v1.505.x**). Es gibt drei Clients: die **Web-App** (Referenz /
Superset), der native **iOS-Client** (`../ledgerline-ios`, SwiftUI) und **diese
Android-App**. Web ist Ground Truth für Krypto und Transport-Contract; iOS ist
Ground Truth für **Look & Feel** (die App soll den iOS-Stil treffen).

> **Diese Datei ist der Bauplan und MUSS den aktuellen Stand widerspiegeln.**
> Nach jeder Änderung mitpflegen. Ground truth Krypto: `../ledgerline/resources/js/vault.js`.
> Ground truth Design: `../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`.
> Ground truth API: `../ledgerline/openapi.yaml` + `../ledgerline/routes/api.php`.

---

## 0. Das eine Prinzip: ZERO-KNOWLEDGE

Der Server sieht **niemals** Klartext. Er speichert nur: Ciphertext-Blobs,
versiegelte (client-verschlüsselte) Manifeste, KDF-Parameter und eine Byte-Quota.
**Alle Ver-/Entschlüsselung passiert in der App.** Das Bearer-Token beweist nur
**Identität** — es entsperrt nichts. Der Vault-Key (VK) wird aus der Passphrase
abgeleitet und verlässt das Gerät nie.

Konsequenz: Es gibt keine „REST-API die Klartext-Dateien liefert". Die App bekommt
Ciphertext + versiegeltes Manifest und entschlüsselt selbst. Wer das ignoriert,
bricht das ganze Sicherheitsmodell.

---

## 1. Server-Basis-URL

Self-hosted, also variabel. Die App bekommt die Base-URL **aus dem QR-Code beim
Pairing** (§2). Alle Endpunkte sind relativ dazu, nur über **HTTPS/TLS** (SPKI-Pinning,
fail-closed). Referenz-Instanz: `https://home.kiefer-networks.de`.

---

## 2. Authentifizierung — QR Device Pairing

Kein OIDC in der App. Der Nutzer ist im **Web** (per Pocket-ID) eingeloggt und
autorisiert dort jedes neue Gerät.

1. **Web-Profil** → „Gerät verbinden" erzeugt einen QR mit Deep-Link:
   `ledgerline://pair?url=<url-encoded base_url>&code=<one-time-code>` (256-bit,
   ~2 min gültig; der echte Token steht **nicht** im QR).
2. **App** scannt (CameraX + ML Kit Barcode), parst `url` + `code`.
3. **App** claimt: `POST {url}/api/v1/auth/pair` `{ code, device_name }` → `{ status:'pending' }`.
4. **Web** bestätigt „Gerät <device_name> verbinden?".
5. **App** pollt `POST {url}/api/v1/auth/pair/collect` `{ code }` alle ~2 s:
   - `pending` → weiter; **einmalig** `approved` → `{ token, user }`; danach `410`.

   > **Behoben 2026-07-25:** der Poll war früher `GET /auth/pair?code=`; der Server hat
   > ihn auf `POST /auth/pair/collect { code }` verschoben (GET 405t jetzt) → Kopplung
   > schlug mit NETWORK fehl. `pollPair` in `LedgerlineApi` ist umgestellt (Test
   > `NetworkFactoryPairingTest` prüft POST + Pfad).
6. App speichert das **Bearer-Token** in **EncryptedSharedPreferences** (Keystore),
   optional Biometric davor. Header `Authorization: Bearer <token>` für alle `/api/v1`.

**Token-Modell:** langlebiges Sanctum-Token, einzeln widerrufbar. **Kein Refresh** —
widerrufen/verloren → neu pairen. Rate-Limit `/auth/pair`: 30/min/IP; bei `429` Backoff.

**Auth-/Konto-Endpunkte (Bearer, außer /auth/pair):**
| Methode | Pfad | Zweck |
|---|---|---|
| POST | `/api/v1/auth/pair` | Code claimen |
| POST | `/api/v1/auth/pair/collect` `{code}` | Pollen; nach Freigabe `{token,user}` (einmalig) |
| GET | `/api/v1/me` | `{ user, usage:{files,gallery,quota?}, wipe?, prefs }` — `usage.quota` = kombiniertes Files+Gallery-Limit in Bytes, `null`=unbegrenzt (web `7b2ad183`); Remote-Wipe-Flag §Kill-Switch |
| GET | `/api/v1/avatar` | IdP-Avatar streamen |
| POST | `/api/v1/device/heartbeat` | Idle/Sync melden → Wipe-Flag |
| DELETE | `/api/v1/auth/session` | Aktuelles Token widerrufen (Logout) |
| GET/DELETE | `/api/v1/devices` · `/devices/{token}` · `POST /devices/{token}/wipe` | Geräte-Verwaltung + Remote-Wipe (**noch nicht in Android**) |
| GET | `/api/v1/notifications` · `POST .../read` · `.../read-all` | Benachrichtigungen (**noch nicht in Android**) |
| POST | `/api/v1/locale` · `/theme` · `/preferences` | Sprache/Theme/Einheiten (server-seitig) |
| GET | `/api/v1/account/export` · DELETE `/account` | Export / Krypto-Shred (**noch nicht in Android**) |

---

## 3. API-Referenz — `/api/v1` (Store v3)

Alle Payloads sind **opak** (Ciphertext / versiegeltes Manifest / KDF-Params).
Owner-scoped (fremder Blob → `404`). **Wichtige Änderung ggü. der ersten Fassung:**
der Monolith `GET/PUT /api/v1/store` ist **serverseitig entfernt** — die Route ist
`/store/{module}` (`whereAlpha`), bare `/store` **404t**. Der Workspace nutzt die
**per-Modul-Stores** (todos/bookmarks/contacts, §14 R0 erledigt); **Notes** wurde web-seitig
auf den **sharded** `/notes/store` graduiert, **Passwords** auf `/passwords/store` — Android
liest/schreibt beide jetzt sharded (mit One-Time-Monolith-Migration, §14 erledigt 2026-07-27).
Gallery **und Files** nutzen den v3-Sharded-Store (§14 R1 erledigt 2026-07-26).

### Vault (Entsperren)
- `GET /vault` → `{ configured, salt, kdf_ops, kdf_mem, wrapped_vault_key,
  wrap_nonce, has_recovery, wrapped_vault_key_recovery, recovery_nonce }`. Siehe §4.

### Store v3 — pro Modul ein versiegelter Store
- `GET /store/{module}` → `{ ciphertext|null, version }` (ETag/304).
- `PUT /store/{module}` `{ ciphertext, version, shards?[] }` → `{ version }`; **`409`**
  bei Versionskonflikt (neu laden → mergen → mit neuer `version` erneut PUT; optimistic
  concurrency, Fremd-Keys IMMER erhalten). `422 missing_shard` bei referenzieller Lücke.
  - `module ∈ { todos, bookmarks, contacts, invoices, health, sharing, explore }` (monolith).
    **`notes` + `passwords` sind NICHT mehr monolith** — sie leben jetzt in eigenen Sharded-Stores
    (`/notes/store`, `/passwords/store`, web `LLNotesStore`/`LLPasswordsStore`); der alte
    `/store/{notes,passwords}`-Slot wird nur noch für die One-Time-Migration gelesen + danach geblankt.
- **Notes-Index (sharded, eigener Store):** `GET/PUT /notes/store` (recordKey `notes`, keine Collections).
- **Passwords-Index (sharded):** `GET/PUT /passwords/store` (recordKey `secrets` + `secretFolders`-Collection = `foldersRef/Key/Hash`).
- **Files-Index (sharded, eigener Store):** `GET/PUT /files/store`.
- **Gallery-Index (sharded):** `GET/PUT /gallery/store`.
- **Shared-Vault-Manifest:** `GET/PUT /vaults/{vault}/store`.

> **Sharded-Store-Format (Files/Gallery, „v3"):** der Store-Ciphertext entsiegelt zu
> einem kleinen **Root** `{ v:3, shards:[…], collections:[…] }`; die eigentlichen
> Records liegen in separaten, content-addressed **Shard-Blobs** (id-Buckets) und
> **Collection-Blobs** (Alben/People/Ordner). Laden = Root ziehen → Shard-/Collection-
> Blobs parallel entschlüsseln. Speichern = „dirty-save": nur geänderte Buckets +
> geänderte Collections + Root neu versiegeln, verwaiste Blobs freigeben, PUT mit
> 409-Retry. Records als roher `JSONValue` halten → Fremd-Felder überleben read-modify-write.
> (Android liest Gallery-v2 heute inline; v3-Migration nötig, §14.)

### Blobs (Files / Gallery / Contacts / Explore / Vault) — opake Inhalts-Bytes + Quota
Identisches Muster je Prefix (`files`, `gallery`, `contacts`, `explore`, `vaults/{vault}`):
- `GET /{p}/usage` → `{ used, quota }`
- `GET /{p}/raw/{blob}` → **Ciphertext-Bytes** (octet-stream), §4 entschlüsseln
- `POST /{p}/raw-batch` `{ blobs:[…] }` → gerahmter Concat (bis 512 Blobs, spart Roundtrips)
- `POST /{p}/upload` (multipart `file`) → `{ id }` (201; <64 MiB)
- `POST /{p}/upload/init` `{ size }` → `{ token, id, partSize }` · `.../part`
  (multipart `token,part,chunk` → `{ part, etag }`) · `.../complete` `{ token, parts[] }`
  → `{ id }` · `.../abort` `{ token }` (S3-Multipart, ≥64 MiB, ≥5-MiB-Parts)
- `DELETE /{p}/blob/{blob}` → `{ deleted:true }` (idempotent)
- `POST /{p}/blobs/reconcile` `{ blobs:[uuid,…] }` → `{ used, quota }` (living-set,
  24 h Grace; Liste = alle vom Manifest referenzierten Blob-IDs)

> **Android-Stand:** nur single-shot `POST /files|gallery|contacts/upload` +
> `raw/{blob}` + `blob/{blob}`. **Fehlt:** `upload/init|part|complete|abort`
> (chunked >64 MiB), `raw-batch`, `files/usage`-Nutzung, `explore/*`, `vaults/*/blobs/*`.

### Gallery-Processing (stateless, Klartext transient, sofort verworfen)
- `POST /gallery/process` (multipart `file` = Klartext) → `{ thumb, medium, motion?, exif,
  place, embedding, phash, faces:[…] }`. Renditions + `meta`-JSON client-verschlüsseln,
  padden, hochladen, im Gallery-Store referenzieren.
- `POST /gallery/analyze` → nur CLIP-Embedding + Faces (deferred Backfill).
- `POST /gallery/embed-text` `{ q }` → `{ embedding }` (semantische Suche, Cosinus lokal).
- `GET /gallery/reverse?lat=&lng=` → `{ place, address }` (Reverse-Geocode; grob gerastert,
  nie server-gecacht; App cached lokal **VK-verschlüsselt**, Coarse-Grid).
- `GET /gallery/geocode?q=` → Vorwärts-Geocode (Ort → Koordinate).

### Öffentliche Share-Links (**Android: create/update/revoke erledigt 2026-07-27**)
- Files/Ordner: `POST /files/shares` · `PUT/DELETE /files/shares/{token}`
- Gallery-Alben: `POST /gallery/shares` · `PUT/DELETE /gallery/shares/{token}`
- Android: `ShareRepository` (create/**update**/revoke Files+Ordner+Alben) + `ShareManifests`
  (byte-exakte sealed-manifest-Builder: file `{kind,name,files:[{name,mime,size,path,ref,key}]}`,
  gallery `{name,allowDownload,photos:[{id,t,at,w,h,cap,tR,tK,…}]}`; SK nur im `#s:`-Fragment,
  per-blob-key re-wrap unter SK). Link = `{baseUrl}/s/{token}#s:{sk}`. `update` = Re-Push (gleicher
  Token+SK; Passwort leer = beibehalten, `clear_password` web-parity). **Optimistic-Version-Guard
  (2026-07-27, web `a4ec0747`):** create/update liefern `version`; Android persistiert sie in
  `ShareInfo.version` und sendet sie als `expected_version` beim Update → 409 (loud Err) statt
  Clobber bei Parallel-Edit (older shares ohne Version = blind path; self-heals beim Reload).
  `share`-Feld typisiert auf FileEntry/NamedFolder/GalleryAlbum, presence-aware Codec-Overlay (kein
  Datenverlust; `version` jetzt Teil von `ShareInfo` → wird nicht mehr gedroppt). UI: Overflow
  „Link teilen" → `ShareLinkSheet` (Passwort/Ablauf/Download-Toggle, Create/**Update**/Copy/Send/Revoke).
  Interfaces `FileSharing`/`AlbumSharing` (Hilt). Tests `ShareManifestsTest`+`ShareCodecTest`.
- **`raw-batch` erledigt (2026-07-27):** `POST /gallery/raw-batch {blobs}` → `RawBatchFraming`
  (u32le(idLen)+id+u32le(size)+cipher, fehlende Blobs übersprungen) → `GalleryBlobRepository.prefetchBatch`
  (≤512/Chunk, write-through BlobDiskCache). `Prefetcher` batcht Gallery-Refs (Files/Contacts bleiben
  per-blob). Test `RawBatchFramingTest`.
- Sealed Manifest + `blob_refs` + optional Passwort/Ablauf/Download; Share-Key nur im
  URL-Fragment (`#s:`), verlässt den Server nie.

### Cross-User Shared Vaults / Ordner (**noch nicht in Android**, PQ-Krypto)
- Identität: `GET/PUT /vaults/keys` (X25519 **und** ML-KEM-768; write-once, `409` bei Änderung).
- `GET/POST /vaults` · `DELETE /vaults/{vault}` · `GET/PUT /vaults/{vault}/store`
- `POST /vaults/{vault}/resolve-recipient` (enum-resistent) · `GET/POST members` ·
  `PATCH/DELETE members/{member}` · `POST members/{member}/accept` · `POST /vaults/{vault}/rotate`
  (Re-Key bei Mitglied-Entfernen = kryptografische Rücknahme).
- Wrap/Unwrap von VK_vault per **PQ-Hybrid** (§4, unten): X25519+ML-KEM-768 KEM.

### Passwörter (Anreicherung — nichts wird gespeichert; **noch nicht in Android**)
- Records leben im `store/passwords`-Manifest. Server-Hilfen: `GET /passwords/icon`
  (Favicon/BIMI → data-URI), `GET /passwords/breach` (HIBP k-Anonymität, 5-Hex-Prefix),
  `GET /passwords/tfa-directory`.

### Explore / Maps (**noch nicht in Android**)
- Track-Blobs `explore/*`; `GET /maps/route` (Waypoints → Straßen), `GET /maps/resolve`
  (Google-Maps-Shortlink → Koordinate).

### Contacts (Android hat: Avatar-Blobs)
- `GET /contacts/usage` · `POST /contacts/blobs/reconcile` · `POST /contacts/upload`
  · `GET /contacts/raw/{blob}` · `DELETE /contacts/blob/{blob}` · `POST /contacts/notify`
  (Geburtstags-/Jahrestags-Relay, **noch nicht in Android**). Kontakt-Records selbst
  leben im `store/contacts`-Manifest.

Pro-Route-Throttles gesetzt. Bei `429` → Backoff/Retry-After. **Bulk-Delete/Reconcile
client-seitig throtteln** (4 parallele Lanes, 429-aware) — wie der Web-Client.

---

## 4. Krypto-Contract (EXAKT — aus vault.js; unverändert gültig)

Bibliothek: **libsodium** (Android: `com.goterl:lazysodium-android` + JNA).

### Key-Hierarchie
```
Passphrase --Argon2id--> KEK --secretbox-open--> Vault Key (VK, 32 B)
VK --secretbox--> per-blob keys, Datei-Metadaten, Manifest
Recovery-Code (32 B) --generichash--> Recovery Key --secretbox-open--> VK
```

### KDF (Passphrase → KEK)
```
crypto_pwhash(
  outlen  = crypto_secretbox_KEYBYTES (32),
  passwd  = UTF-8 passphrase,
  salt    = base64-decode(vault.salt),        // crypto_pwhash_SALTBYTES = 16
  opslimit= vault.kdf_ops,                     // Setup: OPSLIMIT_SENSITIVE
  memlimit= vault.kdf_mem,                     // Setup: MEMLIMIT_MODERATE
  alg     = crypto_pwhash_ALG_ARGON2ID13
)
```
**`opslimit`/`memlimit` NICHT hardcoden** — immer aus `GET /vault`. Argon2id im
`Dispatcher.Default`.

### VK entsperren (nach `GET /vault`)
```
KEK = deriveKek(passphrase, salt, kdf_ops, kdf_mem)
VK  = crypto_secretbox_open_easy(b64dec(wrapped_vault_key), b64dec(wrap_nonce), KEK)
```
Recovery: `recoveryBytes = from_hex(code ohne Spaces)`; `recoveryKey =
crypto_generichash(32, recoveryBytes)`; `VK = secretbox_open(wrapped_vault_key_recovery,
recovery_nonce, recoveryKey)`. Anzeige-Format hex, 4er-Gruppen.

### „seal/open" = secretbox (Keys, Metadaten, Manifest)
```
seal(data,key): nonce=randombytes(24); cipher=crypto_secretbox_easy(data,nonce,key)
                → { c: base64(cipher), n: base64(nonce) }
open(c,n,key):  crypto_secretbox_open_easy(b64dec(c), b64dec(n), key)
```
Base64 = libsodium `base64_variants.ORIGINAL` (Standard, mit Padding).

### Manifest ver-/entsiegeln (jeder `store/{module}`, `files/store`, `gallery/store`)
```
sealManifest(obj):
  json = JSON.stringify(obj); bucket = 4096
  target = ceil((json.length+1)/bucket)*bucket
  json += " " * (target - json.length)          // 4-KiB-Padmé-Floor, hidet Größe
  { c, n } = seal(utf8(json), VK)
  ciphertext = JSON.stringify({ suite:1, c, n }) // <- Feld "ciphertext"
openManifest(ciphertext):
  { c, n } = JSON.parse(ciphertext); obj = JSON.parse(utf8(open(c, n, VK)))
```
Beim Speichern aktuelle `version` mit-PUTten; bei `409` neu laden + mergen. Der neue
`suite`-Marker: unbekannte Suite → fail-closed.

### Inhalts-Blobs (Dateien, Fotos, Renditions) — secretstream, eigener Key/Blob
```
CHUNK = 4 MiB
Encrypt: fk = crypto_secretstream_xchacha20poly1305_keygen()
  {state,header}=init_push(fk); blob = header ++ für jeden 4-MiB-Slice:
     cipher=push(state,slice,null,isLast?TAG_FINAL:TAG_MESSAGE)
     frame = u32le(cipher.length) ++ cipher        // 4-Byte LE Längenpräfix
  encFileKey = JSON.stringify(seal(fk, VK))         // {c,n} des gewrappten fk
  encMeta    = JSON.stringify(seal(utf8(JSON({name,mime,size})), VK))
Decrypt (aus GET .../raw/{blob}):
  fk = open(JSON.parse(encFileKey), VK); state=init_pull(bytes[0..24], fk); off=24
  loop: len=u32le(bytes[off..off+4]); off+=4; {msg,tag}=pull(state,bytes[off..off+len])
        off+=len; append msg; bis tag==TAG_FINAL
```
Konstanten: HEADERBYTES 24, ABYTES 17. Framed-Größe: `HEADER + total + chunks*(ABYTES+4)`.

### Padmé-Padding vor Upload (Größen-Hiding)
```
padmeSize(n): e=floor(log2(n)); s=floor(log2(e))+1; bits=e-s
              if bits<=0: return n; mask=(1<<bits)-1; return (n+mask) & ~mask
padBlob(blob): pad = padmeSize(size)-size; blob ++ pad zufällige Bytes (falls pad>0)
```
Web+iOS machen das für Files UND Gallery. Gallery snappt `created_at` server-seitig auf
die Stunde.

### PQ-Hybrid-KEM (`core/crypto/PQKEM.kt` — IMPLEMENTIERT + byte-exakt, 2026-07-25)
Für geteilte Vaults/Ordner wird VK_vault per **Hybrid-KEM** gewrappt: `MLKEM768`
(64-Byte-Seed, **BouncyCastle 1.84 FIPS-203**) **und** `X25519` (libsodium scalarmult),
kombiniert via `HKDF-SHA256(ss_ec‖ss_pq, salt=∅, info="ledgerline/kem/v1"+ctx)` (BC HKDF),
dann `crypto_secretbox`. Envelope `{suite:1,epk,kem_ct,c,n}` (base64 ORIGINAL). ML-KEM-Leg
in `MlKem768` (sodium-frei, unit-testbar). **Byte-exakt zu Web `shared/pq-kem.js` + iOS
`PQKEM.swift`:** ML-KEM gegen die FIPS-203-NIST-KAT verifiziert (`PQKEMKatTest`,
seed→ek/ct/ss byte-gleich), Hybrid-Roundtrip on-device (`PQKEMInstrumentedTest`,
fail-closed bei falschem Context/Recipient/Suite). TOFU-Anker = X25519-Fingerprint (Identity
+ Sharing-Flows = §14 R-S2/S3, folgen).

---

## 5. Datenmodell (Manifest-Schemata)

Client-versiegeltes JSON. **Nur additive Felder annehmen; unbekannte Felder beim
Schreiben ERHALTEN (roher JSONValue, nicht in getippte Modelle zwingen — sonst
Datenverlust).** Ground truth: `../ledgerline/resources/js/app.js`.

**Per-Modul-Stores** (jeweils `store/{module}`, entsiegelt):
```jsonc
// store/notes      → { "notes":     [ { id, title, content /*md*/, tags[], trashed, … } ] }
// store/bookmarks  → { "bookmarks": [ { id, url, title, description, tags[], folder, … } ] }
// store/todos      → { "todos":     [ { id, title, done, list, due, … } ] }
// store/contacts   → { "contacts":  [ { id, name, fields[], avatarRef?, avatarKey?, personIds?[], … } ] }
// store/passwords  → { "secrets":   [ { id, type, name, vault, tags[], fields…, totp?, favorite, trashed } ] }
// store/sharing    → TOFU-Fingerprint-Map für geteilte Vaults
```

**Files-Index** (`files/store`, sharded v3):
```jsonc
{ "files":   [ { id, blob, encFileKey /*{c,n}-JSON-string*/, name, mime, size,
                 folder /*id|null*/, tags[], favorite, trashed, created,
                 versions:[ { blob, encFileKey, created, … } ], share? } ],
  "folders": [ { id, name, parent, share? } ] }
```
Datei laden: `GET /files/raw/{file.blob}` → mit `encFileKey` entschlüsseln (§4).

**Gallery-Index** (`gallery/store`, sharded v3):
```jsonc
{ "photos": [ { id, media_type /*image|video*/, sig,
                originalRef,originalKey, thumbRef,thumbKey, mediumRef,mediumKey,
                motionRef?,motionKey?, metaRef,metaKey, faceCropRefs[],
                lat,lng, deviceAssetId?, trashed, created, … } ],
  "albums": [ { id, name, photoIds[], cover, created, share? } ],
  "people": [ { id, name, hidden, centroid[], contactId?,
                faces:[ { photoId, idx, cropRef, cropKey } ] } ] }
```
`metaRef` entsiegelt zu `{ exif, place, embedding, phash, faces:[{embedding,cropRef,cropKey}] }`.
`sig` = SHA-256 der Original-Bytes (Exakt-Dedup, §13).

---

## 6. Kern-Flows

**Entsperren:** `GET /vault` → Passphrase → VK ableiten (§4) → VK nur im Speicher; auf
Hintergrund/Idle löschen (außer opt-in „Remember-Vault", §Security).

**Datei/Foto ansehen:** Modul-Manifest laden → entsiegeln → Ref+Key aus Eintrag →
`GET .../raw/{blob}` → secretstream-entschlüsseln → anzeigen. Thumbnails lazy + cache-first.

**Datei-Upload:** bytes → secretstream → padBlob → `POST /files/upload` (chunked >64 MiB) →
Eintrag ins `files/store`-Manifest → dirty-save PUT.

**Foto-Upload:** Original verschlüsseln+padden+upload; parallel Klartext an
`POST /gallery/process` → Renditions+meta verschlüsseln+padden+upload → Photo-Eintrag mit
allen `*Ref`/`*Key` → `PUT /gallery/store`.

**Löschen:** Eintrag entfernen → PUT store → `DELETE .../blob/{blob}` je freiem Blob
(throtteln, 429-aware). Alternativ periodisch `reconcile` mit allen referenzierten IDs.

---

## 7. Tech-Stack (Ist-Stand)
- **Kotlin** + **Jetpack Compose** (Material 3), **Hilt** DI, **Retrofit**+**OkHttp**
  (Bearer-Interceptor, SPKI-Pinning, 429-Backoff), **lazysodium** (Argon2id im
  `Dispatcher.Default`, VK in-memory), **CameraX**+**ML Kit** (QR), **mapsforge**
  (die **einzige** Karten-Engine appweit — offline `.map`-Vektor + online-OSM-Fallback;
  MapLibre 2026-07-27 vollständig ersetzt, inkl. eigenem Grid-Clustering `PhotoClusterLayer`),
  raw AOSP **LocationManager** (GPS-Tracker, kein Play-Services), **Foreground-Service**
  (`location`-Typ, Track-Aufnahme-Notification), **EncryptedSharedPreferences**
  (Keystore, optional Biometric), **WorkManager** (Backup/Sync while-alive).
- `minSdk 36`, `targetSdk 36`, `compileSdk 37`. Paket `de.ledgerline.app`.
- Schichten: `ui/*` (Compose-Screens + ViewModels), `domain/*` (Modelle, UseCases),
  `data/*` (Repositories, `remote/LedgerlineApi.kt`, offline-Cache), `core/*`
  (crypto, security, ops, backup, offline), `di/*`.

---

## 8. Design-System — Material 3 Expressive (indigo/violet, adaptiv)

> **RICHTUNGSWECHSEL (2026-07-27, user-directed):** **kein iOS-Nachbau mehr.** Ziel ist ein
> modernes, professionelles **Android**-Design nach dem **Juli-2026-Standard = Material 3
> Expressive** — alle aktuellen Nav-/Stil-/Page-Elemente, 100% OSS (kein Google-Services).
> Umgesetzt (Wave 1–2): `material3 1.5.0-alpha24` (§16), adaptive `NavigationSuiteScaffold`
> (Bottom-Bar/Rail/Drawer je WindowSizeClass, `NavigationSuiteType.None` im Vollbild),
> `MaterialExpressiveTheme` + expressive `MotionScheme`, wavy `LoadingIndicator`, Tab-Fade-Through,
> `SwipeToTrashBox` (Notes/Todos/Bookmarks), Gallery-`FloatingActionButtonMenu`, Theme-Umschalter +
> Dynamic-Color. **Offen (mit Gerät):** ListDetail-Two-Pane, Shared-Element, Dashboard, Predictive-
> Back, M3-Settings-Rows, SearchBar überall. Die indigo/violett-Markenpalette bleibt.

**(Historisch — iOS-Tokens, Palette bleibt gültig:)** Tokens waren gespiegelt aus
`../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`.

**Palette (Brand):** Akzent-Verlauf **Indigo `#7066F5` → Violett `#9E70FA`**
(diagonal top-left→bottom-right), fix in Light **und** Dark. Kategorie-Tints (Icon-Chips,
web-aligned): blue `#3B9FD6`, green `#59AD6B`, orange `#E2915A`, teal `#3FAE9F`,
violet `#9E70FA`, gray `#6B7280`.

**Schemes:** vollständige M3 Light- **und** Dark-Schemes, indigo-Familie, kein Dynamic
Color. Aktives Scheme folgt der System-Einstellung (adaptiv, wie iOS). Dark =
„Vault-Depth" (`#131318` Grund, faint indigo). Files:
- `ui/theme/Color.kt` — Light-/Dark-Rollen (`Light*`/`Dark*`).
- `ui/theme/Theme.kt` — `LedgerlineTheme(darkTheme = isSystemInDarkTheme())`.
- `ui/theme/Type.kt` — M3-Typescale, Platform-Font (privacy, kein Bundle).
- `ui/theme/Shape.kt` — Corner-Ramp 6/10/14/20/28 dp (etwas runder = Expressive).
- `ui/theme/Brand.kt` — **das Design-System**: `Brand` (accent/accentSoft/tints/
  `accentGradient`/Metriken), `IconChip`, `HeroIcon`, `Modifier.cardSurface()`,
  `PrimaryGradientButton`, `SecondaryBrandButton`, `LedgerlineBackground` (Grund +
  zwei blasse Akzent-Glows). **Neue UI nutzt diese Bausteine.**

**Metriken (iOS-gleich):** cardRadius 18, chipRadius 12, chipSize 38, cardPadding 16,
screenPadding 20 dp.

**System-Bars:** `MainActivity` wählt SystemBarStyle + `isAppearanceLightStatusBars`
nach `uiMode` (Light→dunkle Icons, Dark→helle). XML-Fallback-Theme: `values/themes.xml`
(Light) + `values-night/themes.xml` (Dark), Fenster-Background aus `values[-night]/colors.xml`.

**App-Icon:** Shield + weißes Vorhängeschloss auf Indigo→Violett-Verlauf (wie iOS).
Adaptive Icon: `drawable/ic_launcher_background.xml` (Gradient-Vektor),
`drawable/ic_launcher_foreground.xml` (transluzenter Shield + weißes Schloss),
`drawable/ic_launcher_monochrome.xml` (themed icons). In-App-Mark:
`drawable/ic_ledgerline_logo.xml` (Gradient-Shield + weißes Schloss).

**Navigation (Ist Android):** Bottom-Bar Files · Gallery · Todos · Notes + „More"
(Bookmarks, Contacts, Settings) in `ui/workspace/WorkspaceScaffold.kt`. **iOS** hat
stattdessen Passwords · Files · Gallery · Settings. Angleichung/Passwords-Tab = Roadmap
(§14) — keine harte Anforderung dieses Passes.

**Verifikation:** `FLAG_SECURE` blockt `adb screencap` (schwarz) — visuelle Änderungen
muss der Nutzer am Gerät prüfen. Immer light **und** dark testen.

---

## 9. Versionierung / Stabilität
- Basis-Pfad `/api/v1`. Additiv; unbekannte Felder ignorieren/erhalten. Breaking →
  `/api/v2` (`Sunset`-Header) + In-App-„bitte updaten".
- **Eingefroren (Transport-Contract):** Blob-UUIDs, `{ciphertext,version}`-Envelope,
  KDF-Feldnamen, Upload/Chunk-Protokoll, secretstream-Framing, Padmé.
- **App-Sache:** Klartext-Manifest-Schema darf additiv wachsen.

## 10. Sicherheits-Checkliste
- Nur HTTPS + SPKI-Pinning fail-closed. Token in Keystore, Access-Token bevorzugt nur RAM.
- VK nie unverschlüsselt persistieren; bei Hintergrund/Idle löschen. (Ausnahme: opt-in
  „Remember-Vault" = biometric-versiegelter VK mit TTL — bewusste, dokumentierte Abweichung.)
- Padmé vor jedem Blob-Upload. Kein Klartext-Cache auf Disk. Temp-Dateien überall löschen.
- Bulk-Blob-Deletes throtteln (429-aware, Retry-After). Nie VK/Passphrase/Recovery loggen/senden.
- `FLAG_SECURE` (Screenshot/Recording/Recents-Block) an. Remote-Wipe-Kill-Switch (`/me`
  `wipe:true` → alles lokal löschen + neu pairen), geprüft bei Unlock + BackgroundSync.

## 11. Offline-Verfügbarkeit (Kern-Anforderung)

> **Write-Robustheit gehärtet (2026-08-02, v0.9.1):** JEDER Store-Write queued jetzt bei
> Offline **und** bei jedem wiederherstellbaren Serverfehler (`RECOVERABLE_SAVE_ERRORS` =
> `{NETWORK, HTTP, RATE_LIMITED}` in `core/offline/SaveErrors.kt`; 5xx/429/erschöpftes-409
> kollabiert zu `HTTP`) → durable VK-versiegelte Outbox + optimistischer Cache; nur echt
> unrettbare Fehler (DECRYPT/WRONG_PASSPHRASE/GONE) reverten. Vorher enqueten Gallery/Health/
> Explore NUR bei `NETWORK` (→ 5xx/429 verlor still die Bearbeitung); Passwords verlor bei
> erschöpftem-409; **Finance hatte gar keine Outbox** (verlor sogar offline). Alle gefixt:
> `FinanceRepository` implementiert jetzt `SyncableStore` (+ `collectionsOf`/`applyDelta`/
> `enqueueFinance`/`replayPending` über invoices + 4 Collections, per-Collection-Replay über die
> byte-exakten Online-Saves mit `queue=false`; Invoice-Replay pausiert bei `degraded`), in
> `OfflineModule` registriert. **Reconnect-Drain:** `ReconnectSyncTrigger` (NetworkCallback →
> `syncNow` sofort bei Netz-Rückkehr) + `BackgroundSync` drained die Outbox jetzt auch bei
> auto-refresh=0 und ungated vom Offline-Master-Schalter. Tests: `WorkspaceSaveTest`,
> `FinanceRepositoryTest` (recoverable-error + offline queued).
>
> **Blob-Import-Outbox — ERLEDIGT (2026-08-02, v0.9.2):** ein fehlgeschlagener/offline Foto- oder
> Datei-**Import** wird jetzt durable gequeued + bei Reconnect nachgeholt (nicht nur Manifest-Deltas).
> `ImportQueue` (data) versiegelt die Klartext-Quellbytes pro Item **VK-sealed auf Disk**
> (`SealedImportBlob` = Blob-Upload-Framing; kein Klartext at-rest, ZK-konform) + einen VK-sealed
> Index (Name/MIME/Geo/Ordner/encFileKey/sig); Dedupe per `ContentSig` (windowed Hash). `ImportPhotosImpl`
> + `ImportFileImpl` bekommen `queue`-Param + `Connectivity`/`VaultKeyHolder`: offline ODER
> wiederherstellbarer Upload-Fehler → in die Queue (Foto-`ImportResult.queuedSources`, Datei → Ok). Replay:
> `PendingImportRepository : SyncableStore` (`hasPendingWork` neu am Interface → `OfflineSyncEngine`
> läuft auch bei leerer Manifest-Outbox) fährt die volle Upload+Append-Pipeline aus der versiegelten
> Quelle (lazily-decrypting Stream, konstanter Speicher) mit `queue=false`; entfernt Item bei Erfolg.
> `GalleryBackupManager` schließt `queuedSources` von Mark/Delete aus (sig-dedup verhindert Doppel).
> `ForceLogout` wischt die Queue (`ImportQueue.clearAll`). Tests: `ImportQueueTest` (seal→decrypt-
> Roundtrip/Dedupe/Remove). UI: „N in Warteschlange" statt „fehlgeschlagen". **Grenze:** transiente
> SAF/SEND-URIs werden durch Bytes-Sealing at-enqueue abgedeckt (kein `takePersistableUriPermission`
> nötig); Kamera-Backup läuft weiterhin nur online.

Kein Sync außerhalb dieser App. **Lokaler Cache = Ciphertext** (versiegelte Manifeste +
Blob-Bytes, wie vom Server). Entschlüsselt nur in-memory bei Zugriff (VK nötig). Gesperrt =
kein Zugriff. Online → ziehen+cachen; offline → aus Cache. Schreib-Ops offline in **Queue** →
bei Reconnect Manifest-PUT mit optimistic `version` (409 → merge, last-write-wins pro Feld);
Blob-Uploads resumable nachholen. Settings: Master-Schalter „Alles offline" + pro Modul
(Files/Gallery/Notizen/Lesezeichen/Todos), Blob-Strategie (alle/Favoriten/Thumbnails/aus),
Wi-Fi-/Laden-Constraint, Cache-Limit + „Cache leeren" pro Modul.

> **Sharded-Offline erledigt (2026-07-26):** die **sharded** Gallery- **und** Files-Stores
> assemblen jetzt kalt-offline. Shard-/Collection-Blob-Fetches laufen cache-first + write-through
> über `BlobDiskCache` (content-addressed Refs = unveränderlich → Cache-Hit ist immer aktuell,
> beschleunigt auch warme Online-Loads). `assembleManifest`/`assembleFilesSlice` tragen ein
> `allowNetwork`-Flag (online lädt+cached, offline liest nur Cache; nicht-gecachte Slices fehlen).
> Der **Root**-Envelope wird ebenfalls gecacht (Gallery `storeCache["gallery"]`, Files neuer Key
> `workspace_files_root`; Files-Save + `refreshStoreCache` halten ihn aktuell). Frisch geschriebene
> Shard-Content-Blobs werden erst beim nächsten Online-Load gecacht (Uploader hält den Ciphertext
> nicht). Tests: `OfflineStoreLoadTest.{gallery,files}_v3_online_caches_shard_blobs_then_offline_
> assembles_them`. Behebt den früheren „Tab offline leer"-Bug (Root dekodierte flach → leer).

## 12. Share-Target (Kern-Anforderung)
App als `ACTION_SEND`/`ACTION_SEND_MULTIPLE`-Ziel. `image/*`,`video/*` → Gallery-Import;
alles andere → Files-Import (Ziel-Ordner wählbar). Kurzes Bestätigungs-Sheet. Offline → Queue.
Gleiche Verschlüsselungs-/Upload-Logik inkl. Padmé.

## 13. Roadmap (später): Immich-artiger Auto-Upload
Hintergrund-Backup gewählter Geräte-Alben (WorkManager, Wi-Fi/Laden), resumable, Status-UI.
On-Device-Exakt-Dedup via `sig` (SHA-256 Original) vor Upload. EXIF/GPS/Zeit lokal auslesen.
Motion-Photos als `motionRef`. **Nur bei entsperrtem Vault** (kein app-closed Sync — Token
ist biometric-versiegelt).

---

## 14. Parität & Gaps — Android vs iOS/Web (verbindliches Register)

Web (`v1.505.x`) ist der Superset; beide Mobile-Clients hängen unterschiedlich hinterher.
iOS (`v0.6.x`) ist beim Contract + Sharing/Passwords voraus, hat aber Notes/Todos/Bookmarks/
Contacts NICHT. Ehrlich geführt, nicht schöngeredet.

**P0 — Store-v3-Contract-Drift:**
1. **Workspace-Module-Stores — ERLEDIGT (2026-07-24).** `WorkspaceRepository` fächert
   jetzt auf `GET/PUT /store/{notes,todos,bookmarks,contacts}` auf (per-Modul-Version,
   409-Merge pro Modul, per-Modul-Offline-Cache `workspace_<mod>`). App-Contract
   (`WorkspaceManifest` + `save(mutate)`) unverändert; Tests grün (`WorkspaceSaveTest`,
   `OfflineStoreLoadTest`). Modul-Wire-Shapes = `NotesManifest`/`TodosManifest`/
   `BookmarksManifest`/`ContactsManifest` (`v:3`, byte-gleich zu Web `MODULE_BLANKS`).
   `moduleStore()`/`putModuleStore()` in `LedgerlineApi`; altes `store()` @Deprecated.
2b. **Gallery-v3-Sharded-Write — ERLEDIGT (2026-07-25).** `GalleryRepository` schreibt jetzt
   den v3-Sharded-Root (Photos → Shard-Content-Blobs, Albums/People → Collection-Blobs,
   `shards[]`-Guard, 409-Rebase, Dirty-Save) und **liest Collection-Blobs** (Albums/People
   waren bei web-geschriebenem v3 vorher leer). Byte-exakte Bausteine: `CanonicalJson`
   (code-point-sortiert, ECMAScript-Escaping, dec6 — gegen die Web-`canonical-json`-Fixture
   verifiziert), `GallerySharding` (bucketOf/recommendedShardBits/shardHash), `GalleryShardWriter`
   (Dirty-Save-Engine, Unit-getestet). `StorePutRequest` trägt jetzt optionales `shards[]`.
   **Byte-exakte Record-Parität + kein Datenverlust:** `GalleryRecordCodec` serialisiert
   Photo/Album/Person **byte-identisch zum Web** — encodet aus dem originalen Raw-JsonObject
   (bei Load erfasst) und überlagert nur Android-editierte Felder web-gerendert (lat/lng→dec6-
   String, trashed→ISO/null, Faces web-shape nur bei Änderung). Unbekannte Web-Felder + rohe
   Float-Tokens (`centroid` `1e-7`) bleiben verbatim erhalten (kotlinx bewahrt das Zahlen-Literal
   → kein JS-Float-Formatter nötig). Verifiziert gegen eine **web-generierte Fixture**
   (`app/src/test/resources/gallery-record-canonical.json`, `GalleryRecordCodecTest`).
   **Chunked Upload (2026-07-25):** `ChunkedUpload` (S3-Multipart, `init→part…→complete`/`abort`,
   verschlüsselt zu Temp-Datei → Teile ≥ Server-`partSize`, konstanter Speicher) für Dateien
   ≥64 MiB; `FileBlobRepository.upload` schaltet ab dem Schwellwert um. API/DTOs für Files
   **und** Gallery vorhanden (`{files,gallery}/upload/{init,part,complete,abort}`).
   **Gallery-Original streamt (2026-08-02):** `GalleryUploader.upload` + `ImportPhotos` streamen
   Original **und** `/gallery/process`-Plaintext aus `PhotoSource.openInput` (re-openable URI-Stream),
   nie voll in RAM — Chunked ab 64 MiB. Die alte „In-Memory-Bytes"-Notiz war stale.
   **Upload-Durchsatz + Backup-Härtung (2026-08-02):** `ImportPhotosImpl` neu — **4 parallele Lanes**
   (Semaphore) statt sequenziell, **atomarer Sig-Reserve** (Mutex, kein Doppel-Upload identischer
   Fotos in einem Batch), **gebatchter Index-Commit** (`COMMIT_BATCH=8` statt ein voller Sharded-
   Store-PUT pro Foto); Commit sequenziell → Optimistic-Version bleibt konsistent. **Quota-aware:**
   413 → `ErrorKind.QUOTA` (Gallery+Files single-shot), stoppt den Batch, `ImportResult.quotaExceeded`.
   **Delete-after-backup (iOS-Parität, `BackupPolicy.deleteAfterUpload`):** Opt-in
   `backup_delete_after`; `GalleryBackupManager` markiert **pro-erfolgreichem** Item (nicht mehr
   all-or-nothing) und queued die Original-URIs (`BackupStateStore.pendingDelete`). Löschung nie
   silent — Scoped-Storage: UI drained die Queue via `MediaStore.createTrashRequest` (30-Tage-
   Papierkorb, OS-Consent-Dialog pro Batch) in Settings→Kamera-Backup. Tests: `ImportPhotosImplTest`
   (dedup/batch/quota/commit-fail), `GalleryBackupManagerTest` (per-succeeded mark + enqueue).
   **Bewusst deferred:** `ml=false`-Fast-Path (`/gallery/process?ml=false` + `/gallery/analyze`-
   Backfill, wie iOS `mlPending`) — würde Faces+FaceCrops einen zweiten Backfill-Pass + Record-Migration
   kosten; der Inline-`process` (ml=true) ist korrekt, nur langsamer pro Foto. On-device-Verifikation offen.
2. **Files-Store (sharded `/files/store`) — ERLEDIGT (2026-07-26, on-device-Verifikation offen).**
   `WorkspaceRepository` lädt/schreibt die Files-Slice jetzt über den v3-Sharded-Store (identische
   Engine wie Gallery), der App-Contract `WorkspaceManifest`/`save(mutate)` bleibt unverändert —
   `FilesViewModel`/UI/`FileOps` sind unberührt. Bausteine: `FilesRoot`/`GalleryShard`-Descriptor,
   `FilesShardWriter` (Dirty-Save, eine `fileFolders`-Collection → `foldersRef/foldersKey/foldersHash`,
   `GallerySharding`+`CanonicalJson` wiederverwendet), `FileRecordCodec` (Raw-Overlay wie
   `GalleryRecordCodec` → **kein Datenverlust** bei web-Feldern `encMeta`/`note`/`share`/… + `trashed`
   ISO-or-null), `GET/PUT /files/store` in `LedgerlineApi`. Load: Root → Shard-Blobs parallel +
   `fileFolders`-Collection; Save: Dirty-Save + Seal + PUT mit `shards[]`-Guard + 409-Rebase. Tests
   `FilesShardWriterTest` + `WorkspaceSaveTest`. **Offen:** Offline-Cache der Files-Slice (heute
   online-only — transienter Netzfehler ⇒ leere Files, kein Crash), iOS' 422-`missing_shard`-Full-
   Rederive + degraded-Read-Freeze (Gallery fehlt beides ebenfalls). Root-Schema: `{v:3,suite:1,
   shardBits,shards[],caps:{},foldersRef/Key/Hash}`; Bucket = `uint32(hexPrefix8(id)) >>> (32-shardBits)`.
3. **Gallery-v3-Sharded schreiben** (liest heute schon v2/v3-Shards) + **Chunked Upload**
   (`upload/init|part|complete|abort`) für Dateien/Videos >64 MiB; `raw-batch` für Thumbnail-Batches.
4. **Notes + Passwords → Sharded — ERLEDIGT (2026-07-27, on-device-Verifikation offen).** Web hat
   `notes` (`c4d25d7c`) **und** `passwords` (`bc7f3694`) vom Monolith `/store/{module}` auf eigene
   **Sharded-Stores** graduiert (`/notes/store`, `/passwords/store`; `LLNotesStore`/`LLPasswordsStore`)
   **und den alten Monolith geblankt** → ein Android-Client, der weiter den Monolith las, zeigte für
   jeden Web/Extension-Nutzer **leere Notizen/leeren Passwort-Tresor**. Behoben via generischer
   `ShardedStoreEngine` (+ `SealedShardWriter`/`ShardRoot`, modul-agnostisch, gleiche
   `CanonicalJson`/`GallerySharding`-Bausteine wie Files/Gallery): `WorkspaceRepository` fährt Notes
   als Sharded-Slice (`notesEngine`, load/save/409-Rebase neben der Files-Slice), `PasswordsRepository`
   komplett auf die Engine portiert. **Raw-Overlay-Codecs** (`WorkspaceRecordCodec.encode/decodeNote`
   bereits vorhanden; neu `SecretRecordCodec` für `secrets`+`secretFolders`) → kein Feldverlust.
   **One-Time-Dual-Read-Migration** byte-exakt zu Web (`migrateFromMonolith`): Monolith lesen → in
   Sharded-Store verschieben → Monolith blanken (Notes `{v:3,notes:[]}`, Passwords
   `{v:3,secrets:[],secretFolders:[],pwVaultMigrated:true}`), nur solange der Sharded-Store leer ist.
   Offline-Root-Cache je Store (`workspace_notes_root`, `passwords_root`). Tests: `WorkspaceSaveTest`
   (sharded-notes-write + monolith→sharded-migration), `SecretRecordCodecTest` (6 Raw-Overlay-Fälle).
   **Offen:** Invoices-Modul ist web-seitig ebenfalls sharded (`d2d5180a`) — in Android noch gar nicht
   gebaut (P1). Gallery-Original-Streaming (§oben) unverändert offen.

**P1 — Feature-Paritätslücken (iOS/Web haben, Android nicht):**
- **Passwort-Manager — GRUNDGERÜST ERLEDIGT (2026-07-26; Sharded-Store 2026-07-27, s. P0-Punkt 4).**
  `PasswordsRepository` schreibt jetzt den **sharded** `/passwords/store` (nicht mehr Monolith),
  `SecretItem`-Model (opake `fields:JsonObject` = verlustfrei,
  9 Typen `SecretTypes`), `PasswordsViewModel` (Liste/Suche/Filter/Favoriten/Trash/Versionen),
  UI `ui/passwords/PasswordsScreen` (Liste→Detail→Edit, Typ-Picker, Live-TOTP, Reveal/Copy,
  Generator). Logik: `Totp` (RFC-6238 SHA1/6/30, gegen RFC-Vektor getestet), `PasswordStrength`
  (pwScore), `BreachCheck` (HIBP k-Anon), `PasswordGenerator`. Enrichment-API
  (`/passwords/{breach,icon,tfa-directory}`) + DTOs vorhanden. **Passwords ist jetzt primärer
  Tab** (Todos ins More-Sheet). Secret-Copy nutzt `SecureClipboard` (sensitive + Auto-Clear).
  **Autofill — ERLEDIGT (2026-07-26, on-device-Verifikation offen):** `AutofillService`
  (`core/autofill/LedgerlineAutofillService`) ist **zero-knowledge** — `onFillRequest` inspiziert
  nur die `AssistStructure` (Feld-Klassifikation `AutofillParsing`: AutofillHints + Heuristik) und
  liefert **eine** auth-gated Dataset-Zeile. Tap öffnet `AutofillUnlockActivity` (FragmentActivity,
  reused `UnlockScreen`+`VaultAuthorizers` wie ShareActivity): entsperrt (Biometrie/Passphrase),
  matcht Credentials an Domain/Package (`DomainMatch`: eTLD+1 + Package-Token, Test
  `DomainMatchTest`), zeigt Picker, gibt `Dataset` zurück. `onSaveRequest` bietet neu eingegebene
  Logins zum Speichern an (`SecretItem` login). Manifest: Service (`BIND_AUTOFILL_SERVICE`) +
  `xml/autofill_service`; aktivieren via Settings→Autofill (`ACTION_REQUEST_SET_AUTOFILL_SERVICE`).
  Kein Klartext verlässt die App vor Auth. Strings EN/DE/RU.
  **Favicon + TFA-Anzeige — ERLEDIGT (2026-07-26).** `SecretAvatar` zeigt das Site-Favicon
  (`GET /passwords/icon?domain=`, server-proxied → data-URI; `Favicons`-Decoder für PNG/JPEG,
  SVG/ICO-Fallback auf Typ-Icon; VM cacht pro Domain) in Liste + Detail, sonst Typ-`IconChip`.
  Detail zeigt eine „Diese Seite bietet 2FA"-Zeile für Logins **ohne** TOTP, deren Domain in der
  `GET /passwords/tfa-directory` (2fa.directory) steht — mit „Einrichten"-Link (Doku-URL). Match
  = Host + Parent-Domains (web `_tfaMatch`). Strings EN/DE/RU.
  **Passkeys — ERLEDIGT (2026-07-26, on-device-Verifikation offen).** App ist WebAuthn-
  **Credential-Provider** via Android Credential Manager (`androidx.credentials` 1.6.0). Krypto-Kern
  (byte-exakt zu Web `passkey.js` + iOS, unit-getestet `PasskeyCryptoTest`): `core/passkey/` —
  `P256Key` (JCA secp256r1/ES256, JWK `{kty,crv,d,x,y}`, DER-Signatur), `WebAuthnCbor` (hand-rolled
  COSE_Key + authData create 0x5D/assert 0x1D + none-Attestation, kein CBOR-Lib), `PasskeyStore`
  (standalone `passkey`-Item **oder** in `login.fields["passkeys"]` eingebettet; `candidates`/
  `standaloneItem`/`attach`/`rpIdAllowed`), `PasskeyResponses`/`PasskeyRequests` (WebAuthn-JSON).
  `LedgerlinePasskeyService` (BeginCreate→CreateEntry, BeginGet→**AuthenticationAction** da locked)
  + `PasskeyProviderActivity` (unlock-gated wie Autofill/Share; CREATE→generieren+speichern,
  GET→entsperren+Kandidaten enumerieren, ASSERT→signieren). Manifest `BIND_CREDENTIAL_PROVIDER_SERVICE`
  + `xml/passkey_provider`; aktivieren via Settings→Passkeys. **In-App-Verwaltung (2026-07-27):**
  standalone `passkey`-Items werden als normale Secrets gelistet/angezeigt/gelöscht; in Login-Items
  **eingebettete** Passkeys (`fields["passkeys"]`) erscheinen jetzt im Login-Detail (`PasskeysSection`:
  rpId+userName + Löschen je Eintrag) via `PasskeyStore.embedded`/`detach` (+ `PasswordsViewModel.
  deleteEmbeddedPasskey`; Test `PasskeyManagementTest`). Create/Get-Flows sind FLAG_SECURE + unlock-gated
  (verifiziert: `PasskeyProviderActivity` setzt FLAG_SECURE). **Offen/best-effort:** clientDataJSON-Origin
  für App-Caller (Browser liefern `clientDataHash`, korrekt behandelt; App-Caller-Origin `https://<rpId>`
  provisorisch), **on-device-Ceremony-Verifikation gegen echte RP** (nur am Gerät möglich).
  **Offen:** geteilte Passwort-Vaults (PQ-Sharing, §S3), Inline-Autofill-Presentations
  (Menu-Presentation, `Dataset.Builder(RemoteViews)` deprecated aber funktional), volle
  RU-Übersetzung der übrigen Passwort-UI-Strings.
- **Sharing — KRYPTO-READY, FEATURE DEFERRED (bewusste Entscheidung, Rebuild §4.7).** Die Krypto
  ist fertig + byte-verifiziert (`PQKEM` ML-KEM-768+X25519, `IdentityCrypto`/`IdentityRepository`
  write-once `/vaults/keys`, `ShareCrypto` Share-Link-SK-im-Fragment — alle mit KAT/Fixture/On-Device-
  Tests). **Ungebaut:** die REST/UI-Flows — `/vaults` (create/members/resolve-recipient/accept/rotate),
  Shared-Vault-Store lesen/schreiben, öffentliche `*/shares`-Links, Invite/Accept/Rotate-UI, TOFU-
  Anzeige. Nicht mit „geliefert" verwechseln. Empfohlener nächster Bau: **Shared-Vault read/accept**
  (Krypto liegt bereit) vor dem vollen Rollen/Rotation-Epic.
- **Passkeys/WebAuthn.**
- **Geräte-Verwaltung + Remote-Wipe-UI — ERLEDIGT (2026-07-27):** Settings→Account listet die
  gekoppelten Geräte (`GET /devices`) mit Widerruf (`DELETE /devices/{token}`) + Remote-Wipe
  (`POST /devices/{token}/wipe`); aktuelles Gerät markiert, nicht selbst-widerrufbar
  (`AccountRepository.devices/revokeDevice/wipeDevice` + `DeviceDto`).
- **Konto-Kontrolle komplett — ERLEDIGT (2026-07-29, Commit 02e13fb):** Login-2FA (`/user/two-factor/*`:
  enable→QR+Secret, confirm mit Live-Code, Recovery-Codes anzeigen/neu, disable — orthogonal zur ZK-
  Passphrase), Login-Passwort ändern (`PUT /user/password`, min 12), DSGVO-Export (`GET /account/export`
  → SAF-ZIP), Konto-Löschung (`DELETE /account` mit E-Mail-Bestätigung → Wipe-Kill-Switch), locale/theme-
  Server-Sync (`POST /locale`,`/theme`), `GET /maps/resolve` (Explore/Foto-Ort-Suche löst eingefügten
  Google/Apple-Maps-Link server-proxied auf; `PlaceRepository.searchOrResolve`). Alles Settings→Account.
  **Sharing-Epos ebenfalls komplett** (read/accept `0f6c504` + owner-side create/invite/rotate `046e86f`).
  **Damit ist das openapi-Audit-Backlog geräumt** — jeder mobil-relevante Endpunkt ist gebunden; nur
  Admin/Backup/Users/Groups/Server-Security-Log bleiben bewusst out-of-scope (Web-Konsole).
- **Kombinierter Speicher-Ring — ERLEDIGT (2026-07-27, web `7b2ad183`):** `/me` liefert jetzt
  `usage.quota` (kombiniertes Files+Gallery-Limit, `null`=unbegrenzt). Der Home-Hub-Ring zeigt jetzt
  den **kontoweiten** Verbrauch (`AccountRepository.snapshot()` = ein `/me`-Call für Name + `used`
  (files+gallery) + `quota`) statt nur Files; unbegrenzt → „—". Test `AccountRepositoryTest`.
- **Globale Anzeige-Präferenzen — ERLEDIGT (2026-07-28, web `fd490ce3`).** Einheiten (distance km/mi,
  elevation m/ft, weight kg/lb, temp c/f, glucose mgdl/mmoll) + 12/24h-Uhr sind jetzt **globale,
  server-synchronisierte** Prefs (`core/prefs/DisplayPrefs`, in `SettingsStore` persistiert; seed aus
  altem `unit_system`). Sync: `GET /me.preferences` → `AccountRepository.adoptPrefs`, `POST
  /api/v1/preferences` → `pushPreferences`. Angewandt: Explore (distance/speed/pace aus `distance`,
  elevation aus `elevation` — `MeasureFormatter.elevation` nimmt jetzt `feet:Boolean`), Health
  (Einheiten aus den globalen Prefs statt `healthProfile.units`; One-Time-Seed aus profile.units;
  Toggles aus dem Health-Stammdaten-Sheet nach Settings→Darstellung verschoben) + 12/24h in den
  Health-Zeiten. Settings→Darstellung: granulare Unit- + Clock-Toggles. Tests `DisplayPrefsTest`,
  `AccountRepositoryTest` (adopt). **Offen:** 12/24h nur in Health angewandt (andere Screens =
  System-Format).
- **Galerie-Integrität — ERLEDIGT (2026-07-28, web `cdff0af7`+`4fff782b`).** (1) **Reconcile-on-load:**
  `POST /gallery/blobs/reconcile` (neu in `LedgerlineApi`) meldet nach jedem Online-Load die live-set
  aller referenzierten Blobs (per-Foto-Renditions + Face-Crops + Person-Crops + Shard-/Collection-Refs)
  → Server gibt Orphans frei (24h-Grace); behebt das „~3,3 GB verwaiste Blobs nach alles-gelöscht"-Leck.
  NUR im Online-Vollload-Pfad (nie offline/partiell — würde live Blobs freigeben). (2) **Self-Heal:**
  ein permanent fehlender Shard (404) fror bisher Writes ein; jetzt re-sealt der Load den Root aus nur
  den geladenen Records (toter Shard raus), löst die degraded-Freeze (`healDegraded`, best-effort).
  Test `GallerySaveTest.load_reconciles_referenced_blobs`.
- **Explore/Maps — ERLEDIGT (2026-07-27, on-device-Verifikation offen).** Neuer „Entdecken"-Tab
  (Drawer) mit **Karte** (passiver mapsforge-Viewer + Locate-Button), **Tracker** (GPS-Aufnahme
  hike/run/cycle) und **Tracks** (Liste + Detail mit Höhenprofil + GPX-Export). Datenschicht:
  `store/explore`-Modul (`ExploreRepository`, 409-Merge, `{v:3,tracks,couplings,settings}`,
  No-Data-Loss-Overlay `ExploreTrackCodec`, Suite-1/Padmé — byte-kompatibel Web/iOS). Recorder:
  `TrackerEngine` (AOSP `LocationManager`, Punkt-Filter 0–50 m/≥1 m) + `TrackingService`
  (Foreground `location` + Ongoing-Notification als Live-Activity-Ersatz). Stats **byte-exakt**
  zu Web `track-parse.js` (`TrackStatsComputer`, ±5 m-Hysterese, 38 Tests). Offline-Karten:
  `OfflineMapStore` + Region-Katalog (`assets/map-regions.json`, download.mapsforge.org) mit
  Download/Fortschritt/Löschen. Einheiten (metrisch/imperial) + Koordinatenformat (dd/ddm/dms/
  utm/mgrs) als Settings. `ExploreCache` bei Lock/Logout geleert. **Karte** zusätzlich: Orts-/
  Koordinaten-**Suche** (Nominatim-Forward → recenter), **Teilen** von Ort+Koordinaten (`geo:`-URI,
  iOS-`LocationShare`-Parität), **Reverse-Geocode-Ortschip**, **Kompass + Heading-Ausrichtung**
  (Rotation-Vector-Sensor rotiert die Karte, Kompass-Reset), **Tourenplanung** (Wegpunkte →
  `/maps/route` snappen → als `planned`-Track speichern), **GPX/KML-Import** (`TrackImport`,
  namespace-agnostisch, byte-nah zu Web `track-parse.js`; inline, ohne `explore/*`-Roh-Blob).
  **Tour-Enrichment (2026-07-28, web-Parität):** **Kalorien** (`ExploreCalories`, byte-nah zu
  `explore-calories.js` — MET-by-speed + Vertikalarbeit; Gewicht/Sex aus `HealthCache`; im
  Track-Detail-Stat-Grid) + **Richtungspfeile** (`TrackArrows`, byte-nah zu web `_directionArrows`,
  4..40 Pfeile distanzverteilt; gerenderte rotierte Dreieck-Marker via `MapsforgeController.
  setDirectionArrows`). Tests `ExploreTourTest`. **Offen (Explore-Tour, mittel):** Foto↔Tour-**Kopplung**
  (Picker + Auto-Match GPS/Zeit + Foto-Pins auf der Track-Karte — `couplings` überlebt schon per
  Raw-Overlay, aber keine UI) und **Routen-Vergleich** (gleiche-Route-Touren, Track-Similarity).
  **Offen (klein):** `explore/*`-Roh-Blob für exakten Re-Export importierter Dateien, FIT/KMZ-
  Import, Hintergrund-Location-Rationale-Feinschliff.
- **Health-Modul — ERLEDIGT (2026-07-27, on-device-Verifikation offen).** Voller ZK-Health-Client
  auf dem monolithischen `store/health` (`{v:3,healthEntries,healthProfile,healthFasts}`, gleiche
  Optimistic-409-Engine wie Explore via `optimisticSave`/`cachedOrStore`): `HealthRepository` +
  `HealthCache` + `HealthRecordCodec` (Raw-Overlay → **kein Feldverlust**; unbekannte Top-Level- und
  Per-Record-Keys überleben; Zahlen als saubere Tokens `numToken`). 6 Metriken (weight/bp/pulse/spo2/
  temp/glucose, kanonische Einheiten kg/°C/mg-dL; Display-Konvertierung kg↔lb, °C↔°F, mg-dL↔mmol-L
  aus `healthProfile.units`). Pure Logik byte-nah zum Web (`core/health/HealthMetrics` = computeAge/
  computeBmi/classify/Konvertierungen, `HealthFasting` = Intervallfasten inkl. `normalizeFasts`-
  Single-Active-Invariante, `HealthCompute` = Stats/Display/CSV). UI (`ui/health/`, M3-Expressive
  über `Brand`/`cardSurface`): Metrik-Auswahl mit Ampel-Dot, Detail mit nativem Compose-Chart
  (`HealthChart`, kein uPlot) + Referenzbändern + Zielgewicht-Linie + Bereichsfiltern (7d/30d/90d/1y/
  all) + Stats + Einträgen (Edit/Delete), Intervallfasten-Karte (Live-Timer, Templates 12:12…20:4,
  Verlauf), Stammdaten (Alter/BMI, Größe/Geburtsdatum/Geschlecht/Zielgewicht/Einheiten), CSV-Export
  je Metrik (ACTION_SEND EXTRA_TEXT wie GPX). Drawer-Tab „Gesundheit". Cache bei Lock/Logout geleert.
  Strings EN/DE/RU. Tests: `HealthMetricsTest`/`HealthFastingTest`/`HealthComputeTest`/
  `HealthRecordCodecTest`/`HealthRepositoryTest` (21). **Offen (klein):** Doctor-Report/Print-PDF (Web
  hat eine Print-Ansicht; Android macht CSV-Share), globale Einheiten-Prefs-Sync (`/preferences`;
  Android hält die Einheiten in `healthProfile.units`, web-kompatibel), on-device-Verifikation.
- **Finance / Invoices — READ + WRITE ERLEDIGT (2026-07-28, Read on-device verifiziert).** Neuer Drawer-Tab
  „Finanzen" liest **und schreibt** die versiegelten Rechnungen im **sharded** `/invoices/store`
  + das nicht-geheime `/company`-Profil. **Write ohne Datenverlust:** der sharded Root trägt zwei
  Collection-Blobs (`payRef`/`txRef` = paymentMethods/transactions) + Inline-Keys (`partners`,
  `financeCategories`, `invoiceSeq`) — die `FinanceRepository` re-shardet NUR die Rechnungen und macht
  ein **Root-Level-Raw-Overlay** (nur `shardBits`/`shards` ersetzt, ALLE anderen Root-Keys verbatim
  erhalten; deren Blob-Refs kommen in den `shards[]`-Guard, damit der Server sie nie freigibt) +
  409-Rebase.
  > **Backlog-Batch 2026-07-29 (Commit ca78e67):** 3 Top-Gaps geschlossen — (1) **Öffentliche
  > Share-Link-Konsumtion** (`/s/{token}/*`): `SharedLinkRepository` (parse `…/s/{token}#s:{sk}` →
  > meta→unlock-grant→manifest, SK aus URL-Fragment, `ShareCrypto.openManifest`, Blob-Decrypt via neuem
  > `Crypto.contentDecryptorFromKey`/`BlobDownloader.decryptWithKey` — **kein VK nötig**), UI `ui/share/`
  > (Paste→Passwort→Datei-Liste mit SAF-Save / Galerie-Grid), Session-Server-scoped (fremder Host → Browser).
  > (2) **Notifications** (`/notifications`+read+read-all, `NotificationsViewModel`, Settings→Benachrichtigungen).
  > (3) **`/settings`** (GET/PUT: Geburtstags-/Jahrestags-Kanäle + `file_max_versions`, Settings→Account).
  > Parser-Test grün, on-device-Verifikation der Krypto-Flows offen (Vault locked).
  Create/Edit/Issue/Mark-Paid/Trash: FAB→`InvoiceEditScreen` (Empfänger, Datumsfelder,
  Positionen-Editor mit Live-Totals, Notiz), Detail-Aktionen. **Ausstellen** vergibt eine gapless
  GoBD-Nummer (`InvoiceMath.nextSeqForYear`→`formatNumber`, seq pro Rechnung). Test
  `FinanceRepositoryTest` beweist die Collection-Erhaltung (payRef/txRef/partners überleben + im Guard).
  **Company-Profil-Editor + Datepicker (2026-07-28):** `CompanyEditScreen` (`GET/PUT /company`, Top-Bar-
  Business-Icon → Firmendaten + Rechnungs-Voreinstellungen inkl. `number_format`/`next_number`);
  `InvoiceEditScreen` nutzt jetzt Material3-Datepicker für Rechnungs-/Fälligkeitsdatum.
  **Offen (Finance):** payment-methods/transactions-Editor, VAT-Return-Statistik, ZUGFeRD/Factur-X-Export,
  e-invoice-XML-Import, Client-PDF-Import, Bankauszug-Import, Currency-Dropdown-Feinschliff. Bausteine:
  `domain/model/Finance` (Invoice/InvoiceLine/InvoiceCustomer/CompanyProfile, Raw-Overlay = kein
  Feldverlust), `core/finance/InvoiceMath` (byte-nah zu `invoices.js`/`invoice-numbering.js` —
  `totals` net/vatByRate/vat/gross, GoBD-Nummerierung `nextSeqForYear`/`formatNumber`/`duplicateNumbers`,
  `yearKpis`), `FinanceRepository` (read+write, Root-Level-Raw-Overlay) + `FinanceCache`,
  `FinanceRecordCodec` (decode+encode raw-overlay, `numToken` byte-stabile Zahlen → Dirty-Save-Reuse),
  `CompanyDto`/`invoicesStore`/`invoicesStorePut`/`rawInvoice`/`uploadInvoice`/`company` in
  `LedgerlineApi`. UI (`ui/finance/`): Liste je Jahr + KPIs → Detail (Empfänger, Positionen,
  Netto/MwSt-je-Satz/Gesamt, Aktionen) + `InvoiceEditScreen` (FAB/Edit). M3-Karten, €-Format, EN/DE/RU.
  Tests `InvoiceMathTest` + `FinanceRepositoryTest` (Collection-Erhaltung). **On-device (Read) verifiziert:**
  echte Rechnungen entschlüsseln, Totals korrekt (900 net ×19% → 1.071 brutto).
- **Galerie-ML-Parität — CLIP-Suche jetzt modell-korrekt (2026-07-29, web `_reembedOne`/`reindexAll`).**
  Semantische Suche vergleicht nur Embeddings **desselben** CLIP-Modells (web `embModel === config.clipModel`).
  Android schrieb/beachtete das Tag nie → Android-Uploads waren für Web/iOS-Suche unsichtbar (Embedding galt als
  stale-model). Behoben: `GalleryUploader` schreibt `embModel` (aus Process-`model`), `PhotoMetaBlob.embModel`
  liest es, Suche filtert auf das **aktuelle** Modell (`SemanticSearch.currentModel` = modaler Tag, da kein
  Bootstrap-`config.clipModel`; null → alle vergleichen, keine Regression). **Backfill** via `POST /gallery/analyze`
  (vorher ungenutzt): Jobs-Sheet „Suchindex aufbauen" → `GalleryBlobRepository.reembed` (medium entschlüsseln →
  analyze → Meta neu versiegeln, nur embedding+embModel, Faces unangetastet → metaRef swap; alte Meta-Blobs via
  P0-Reconcile-on-load freigegeben). On-device-Verifikation offen. **Noch offen:** Duplikate, Alben-Feinschliff.
- **Beleg-OCR (Server, `/invoices/ocr`) — konform.** Server hat die OCR-Spec umgesetzt (multipart `file`+`lang?` →
  `{text,source,pages}`, 422 `no_text`/501-Fallback); Android (`attachReceipt`→`ocrDocument`→`ReceiptOcr.analyze`)
  entspricht dem Contract exakt (non-2xx → null, manuelle Eingabe bleibt).
- **P0-Endpunkt-Abdeckung erledigt (2026-07-28):** notes/passwords `blobs/reconcile` (Living-Set = Shard+
  Collection-Refs, nur Full-Online-Load), files/notes/passwords `raw-batch` (ein Fetch statt per-Blob),
  `device/heartbeat` (`AccountRepository.heartbeat` + Wipe-Flag; `BackgroundSync` sendet `syncing`).
- **Deep-Audit-Fixes (2026-07-29, Commit 396facf):** 6 paralleler openapi↔Android-Audit. 5 kritische Fehler
  behoben: (1) **Explore-Crash** `ExploreTrackCodec` `"t":null` → `longOrNull`/encode-`null`/GpxWriter-omit;
  (2) **Geocode-ZK-Leak** — `Geocoder`(direkt-Nominatim) entfernt → `GET /gallery/geocode` server-proxied via
  `PlaceRepository.geocode`; (3) Rechnung-`trashed` ISO-Erhalt (`applyTrashed`, auch pm/tx); (4) Galerie-Person
  `centroid` persistiert (fresh+merge); (5) Passwörter Legacy-`custom[].secret` Raw-Overlay. Reverse-`address`
  defensiv (`JsonElement?`, Server `[]`-Sonderfall). Web fixt `address`-Server-Seite separat. Regressions-Tests
  grün. **Offene Gaps** (mobil): Sharing-Epos (25 Ops, Krypto ready, Link-Konsumtion zuerst), Notifications,
  Konto-Export/Löschung, `/settings` (Geb.-Kanäle/`file_max_versions`), Login-2FA, `maps/resolve`, Explore-Blobs.

**P2 — Nav-/UX-Angleichung an iOS:** Tab-Struktur (Passwords-Tab), Grouped-List +
IconChip überall, Wischgesten konfigurierbar, Info-Sheets.

**Android-only (iOS fehlt):** Notes/Todos/Bookmarks-Tabs, Geräte-Adressbuch-Sync
(vault↔device Contacts). Nicht wegwerfen — Web hat diese Module ebenfalls.

**Dieser Pass liefert:** Design-System + adaptives Light/Dark-Theme + neues Icon +
Gradient-CTAs auf Chrome (Unlock/Welcome) + dieses Register. Store-v3 + Feature-Parität =
Folge-Arbeit (siehe Spec `docs/superpowers/specs/2026-07-24-ios-parity-and-restyle-design.md`).

---

## 15. SECURITY-STANDARD & GAP-REGISTER (verbindlich)

Ehrlich geführt wie in der iOS-App (`../ledgerline-ios/CLAUDE.md`) — nicht
schöngeredet, nicht erfüllte Punkte stehen offen hier. Ausführliche
Nutzer-Transparenz: `SECURITY.md`. Dimensionen an den iOS-„Security-Engineering"-
Standard angelehnt (ZK, Primitives, at-rest, Transit, Side-Channels, PQ, Metadaten,
Supply-Chain). **Bei jeder Änderung mitpflegen.**

**Erfüllt / großteils:**
- **ZK (Kern):** Server nur Ciphertext; VK on-device abgeleitet, nur in-memory,
  auf `ON_STOP` + 5-min-Idle genullt. Kein Klartext at-rest (Offline-Cache =
  Ciphertext). Einziges persistiertes Secret: Session-Blob (AES-256-GCM, Keystore).
- **Vetted Primitives:** libsodium (Argon2id/secretbox/secretstream/generichash),
  byte-exakt zu Web `vault.js`. Kein Custom-Krypto. KDF-Params server-geliefert, nie hardcoded.
- **Transit:** HTTPS erzwungen (`network_security_config` cleartext=false),
  `RESTRICTED_TLS`, **TOFU-SPKI-Pinning** (leaf SPKI-SHA-256 bei Pairing, `CertificatePinner`,
  fail-closed). Alle Payloads opak.
- **At-rest / Keystore:** AES-256-GCM-Key im Keystore (StrongBox wo verfügbar),
  `setUserAuthenticationRequired(true)` + `setInvalidatedByBiometricEnrollment(true)`.
  **Per-use `CryptoObject`-gebundene Biometrie** (ein Prompt authorisiert genau den
  Keystore-Decrypt, keine Zeitfenster-Lücke). `allowBackup=false`, `fullBackupContent=false`,
  Data-Extraction-Rules schließen Cloud-/Device-Transfer aus.
- **Side-Channels (teilweise):** `FLAG_SECURE` app-weit (Screenshot/Recording/Recents-Block).
  Kein Secret-Logging (R8 strippt Debug; CI-Greps). **Idle-Lock monotonic** (`IdleLocker`
  elapsedRealtime — Wall-Clock-Sprung verschiebt Lock nicht). **IME-Härtung (2026-07-26):**
  alle Secret-Felder (Master-Passphrase, Recovery-Code, Passwort-Manager-Secrets inkl. Custom-
  Secret-Felder) nutzen `KeyboardType.Password` + `autoCorrectEnabled=false` (zentral
  `ui/common/secretKeyboardOptions()`) → aus IME-Learning/Autocorrect ausgeschlossen.
- **Client-Integrität (2026-07-26, advisory, §3.6):** `IntegritySignal`/`AndroidIntegritySignal`
  (AOSP-only, `foss`-Baseline, via `CryptoModule` gebunden) — **Keystore-Key-Attestation** (Temp-
  EC-Key mit `setAttestationChallenge`, Cert-Chain → KeyDescription-Extension `1.3.6.1.4.1.11129.2.1.17`
  via BouncyCastle → `STRONGBOX`/`TEE`/`SOFTWARE`/`UNVERIFIED`) **+ Root-/Tamper-Heuristik** (su/Magisk/
  test-keys). **Nie blockierend** (GrapheneOS-safe). Assessment bei Pairing (loggt `INTEGRITY_WARNING`
  wenn nicht clean) + Live-Anzeige in Settings→Sicherheit (`IntegrityCard`). Test `IntegrityReportTest`.
- **Clock-Rollback-Guard (2026-07-25):** forward-only Wall-Clock-High-Water
  (`ClockRollbackGuard`, Keystore-versiegelt); ein Rückwärts-Sprung > 5 min sperrt den
  passphrase-freien Remember-Vault-Pfad (fail-closed → Passphrase). Gate in `UnlockViewModel`.
- **Constant-Time-Compare (2026-07-25, libsodium 2026-07-26):** Secret-Vergleiche laufen über
  `Crypto.constantTimeEquals` = libsodium `sodium_memcmp` in `SodiumCrypto` (vetted primitive;
  `IdentityCrypto`-TOFU-Fingerprint nutzt es). `ConstantTime.equal` bleibt als pure-Kotlin-Fallback
  (Default der Interface-Methode, für Test-Fakes). Uniformer opaquer Decrypt-Fehler: `SodiumCrypto`
  liefert für alle Fehlermodi `null` (ununterscheidbar).
- **Unlock-Throttle (2026-07-25):** monotoner exponentieller Lockout (`UnlockThrottle`,
  elapsedRealtime, 3 Freiversuche → 2s/4s/8s… cap 300s, **nie destruktiv**). Gate im
  `UnlockViewModel`; nur echte Passphrase-Fehler zählen. Tests `SecurityGuardsTest`.
- **Duress-Auto-Wipe (2026-07-25):** nach X falschen Passphrasen alles Lokale löschen
  (`DuressGuard`+`WipePolicy`, Schwellen {3,5,10,15,20}, **Default 10, immer an, kein Aus** —
  wie iOS). Zähler Keystore-versiegelt (überlebt Force-Quit). Nur echte Passphrase-Fehler
  (kein Biometric/Recovery/Transport). Wipe reused den Remote-Wipe-Pfad (`AuthEventBus.emitWipe`
  → `ForceLogout`). Settings-Warnung im Footer.
- **Security-Audit-Log (2026-07-25):** verschlüsseltes, gerätelokales Ereignis-Log
  (`SecurityLog`, Keystore-AES-GCM, `requireAuth=false` → schreibt auch pre-unlock;
  Ring 300). Events: PAIRED (Kopplung), UNLOCK_SUCCESS/FAILED, RECOVERY_UNLOCK,
  THROTTLE_LOCKOUT, DURESS_WIPE. Ansicht + „Löschen" in Settings→Sicherheit; von jedem
  Full-Wipe (`ForceLogout`) mitgelöscht.
- **Remote-Wipe-Kill-Switch:** `/me` `wipe:true` → `AuthEventBus`/`ForceLogout` löscht alles
  Lokale + erzwingt Re-Pair; geprüft bei Unlock **und** BackgroundSync.
- **Opt-in „Remember-Vault":** biometric-versiegelter VK mit TTL — bewusste, dokumentierte
  Abweichung von „VK nie persistieren" (§10).

- **Zwischenablage-Härtung (2026-07-26):** `SecureClipboard` — kopierte Secrets als
  `EXTRA_IS_SENSITIVE` markiert (raus aus Clipboard-History/Preview) + Auto-Clear nach 60 s.
  Genutzt beim Passwort-/TOTP-/Card-Copy im Passwort-Manager.

**Teilweise / offen (ehrlich):**
- **Post-Quantum (Krypto-Kern erledigt 2026-07-25):** `PQKEM` (ML-KEM-768 FIPS-203 +
  X25519 + HKDF-SHA256, byte-exakt, KAT + on-device verifiziert, §4).
- **Sharing-Identität (2026-07-25):** `IdentityCrypto`+`IdentityRepository` — X25519+ML-KEM
  erzeugen, Secrets unter VK versiegeln (`Crypto.sealValue`), publish `PUT /vaults/keys`
  (write-once), unwrap fail-closed bei Fingerprint-Mismatch. `ensure()` post-unlock;
  Secrets nur in-memory (clear bei Lock/Logout). Verifiziert `IdentityCryptoInstrumentedTest`.
- **Share-Link-Krypto (2026-07-25):** `ShareCrypto` (SK im URL-Fragment, per-file-key re-wrap
  + Manifest-Seal unter SK), fixture-verifiziert (`ShareCryptoInstrumentedTest`). REST/UI = §14 R-S3/S4.
- **Verbleibend:** Sharing-Flows (Invite/Accept/Rotate, TOFU-Map), Share-REST/UI, Passkeys — §14.
- **Unbekannte Felder (Integrität) — GESCHLOSSEN (2026-07-26, Rebuild-Phase 0).** <!-- banned-token-ok: historical release-note reference --> Notes/Todos/
  Bookmarks/Contacts nutzen jetzt den **Raw-JSON-Overlay** (`WorkspaceRecordCodec`, wie
  `FileRecordCodec`): jeder Record trägt sein originales `@Transient raw:JsonObject`, beim Save
  werden nur die bekannten Felder web-shaped überlagert (presence-aware) → **jedes unbekannte
  Top-Level-Record-Feld von Web/iOS überlebt** den Android-Round-Trip. Zusätzlich: Bookmark-Ordner
  mappen `parent↔parentId` (keine flach-gedrückte Hierarchie mehr), `Contact.vatId` ergänzt,
  `trashed` wird `false | ISO` gerendert (Timestamp nicht mehr auf bool kollabiert), IDs sind
  32-Hex (`Ids.newId`, byte-shape wie Web). Test `WorkspaceRecordCodecTest`.
- **Konto-Kontrolle:** Remote-Devices-Liste/-Widerruf, Notifications, Export/Löschen (`/devices`,
  `/notifications`, `/account/*`) **noch nicht in Android** (nur Web).

**Nicht umgesetzt (backend-/prozess-abhängig, wie iOS deferred):** Metadaten-Schutz
(OHTTP/Privacy-Pass/Cover-Traffic/Padding-Klassen), Binary-Transparency (Reproducible/
Signing/Runtime-Self-Verify), SBOM/Dependency-Scan/HSM-Signing, Audit-Log, Shamir/Social-
Recovery, signierte Server-Zeit für absolute Deadlines. Argon2-Parameter (ops/mem) sind
web-geteilt fixiert → Client kann keinen höheren Floor erzwingen (Byte-Kompat-Zwang).

**Konflikt (eskaliert, nicht still kompromittiert):** Argon2 ops/mem kommen vom Server
(`GET /vault`); ein client-fixierter höherer Floor bräche die Byte-Kompat der KEK/VK zu
Web + iOS. Nur cross-repo gemeinsam änderbar. OFFEN.

---

## 16. Dependency-Aktualität (Policy — verbindlich)

**Alle Libs/Plugins/Abhängigkeiten MÜSSEN immer der neuesten stabilen Version
entsprechen. Das wird VOR JEDEM Build und Release geprüft.** Single source of truth:
`gradle/libs.versions.toml` (Kommentarkopf trägt das letzte Prüfdatum).

Prüfung (Maven-Central + Google-Maven-Metadata, Prereleases ausschließen):
```
# Google Maven (androidx/agp/compose):  https://dl.google.com/android/maven2/<group/path>/<artifact>/maven-metadata.xml
# Maven Central (rest):                 https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml
# je Artefakt letzte <version> ohne alpha|beta|rc|dev|snapshot|M|eap nehmen.
```
Nach jedem Bump: `:app:testDebugUnitTest` + `:app:assembleDebug` grün, Krypto-Interop-
Tests grün (`PQKEMKatTest` on-JVM, `*InstrumentedTest` + **`CryptoKatTest`** on-device).

> **Interop-KATs (Phase 8, 2026-07-27):** <!-- banned-token-ok: historical release-note reference --> `CryptoKatTest` (androidTest) prüft **byte-exakt** gegen
> eine **unabhängige** libsodium (PyNaCl, andere Bindung an dieselbe C-Lib): Argon2id-VK-Derivation,
> secretbox seal+open, secretstream-Decrypt+Framing. Fixtures `androidTest/assets/crypto_kat.json`,
> reproduzierbar via `tools/gen_crypto_kat.py`. sealManifest-Byte-Exaktheit = CanonicalJson
> (`CanonicalJsonTest`) + secretbox (KAT) + Padmé (`PadmeTest`), Envelope-Roundtrip im KAT geprüft.

**Bewusst zurückgehalten (dokumentiert, bei jeder Prüfung neu bewerten):**
- **BouncyCastle 1.84** (statt 1.85/1.85.1): ab 1.85 sind bcutil-Klassen in bcprov
  gemergt → `checkDebugDuplicateClasses`-Konflikt mit dem transitiven bcutil. 1.84 ist die
  neueste saubere Version und hat FIPS-203 ML-KEM.
- **Retrofit 2.11.0 / OkHttp 4.12.0 gehalten** (statt 3.0.0 / 5.4.0): der Bump brach on-device
  das QR-Pairing (NETWORK, obwohl der Server den Claim erhielt); Unit-Tests (Fakes + CLEARTEXT-
  MockWebServer) fangen den Real-TLS-Pfad nicht. Erst nach First-Party-Converter + OkHttp-5-TLS-
  Review + On-Device-Pairing-Test re-adoptieren.
**Bewusste Alpha-Adoption (user-approved 2026-07-27, Ausnahme zur „nur stable"-Regel):**
- **material3 1.5.0-alpha24** (statt BOM-stable 1.4.0) — für den **vollen Material-3-Expressive**-
  Komponentensatz (FAB-Menu, Button-Groups, Split-Button, wavy Progress, Floating-Toolbars,
  `motionScheme`). 100% OSS/androidx, kein Google. Explizit vom Nutzer genehmigt trotz Alpha
  (Design-Pivot: modernes Android-Design nach Juli-2026-Standard, **nicht** mehr iOS-Nachbau).
  Begleitartefakte: `material3-adaptive-navigation-suite:1.5.0-alpha24` (adaptive Nav Bar/Rail/
  Drawer) + `material3.adaptive:{adaptive,adaptive-layout,adaptive-navigation}:1.3.0-rc01`
  (`ListDetailPaneScaffold`, `currentWindowAdaptiveInfo`). Bei 1.5.0-stable zurück auf stable.
- Sonst alle auf neuestem Stand (2026-07-25): AGP 9.3.1, Kotlin 2.4.10, lifecycle 2.11.0,
  camerax 1.6.1, mockk 1.14.11, **mapsforge 0.25.0** (Karten-Engine; MapLibre entfernt),
  jna 5.19.1, zxing 3.5.4, BC 1.84,
  androidx.credentials 1.6.0 (Passkeys; 1.7.0 ist alpha → gehalten).
  androidx.autofill 1.3.0 (Inline-Autofill-Presentations).
  (KSP 2.3.10, Hilt 2.60.1, Compose-BOM 2026.06.01 waren bereits latest.)

---

## 17. Referenzen
- Krypto ground truth: `../ledgerline/resources/js/vault.js` · PQ: `../ledgerline/resources/js/pq-kem.js`
- Manifest/Flows: `../ledgerline/resources/js/app.js` (`vaultFiles`, `vaultGallery`)
- API: `../ledgerline/openapi.yaml` · `../ledgerline/routes/api.php`
- iOS-Design: `../ledgerline-ios/App/DesignSystem/{Theme,Components}.swift`
- iOS-Contract: `../ledgerline-ios/Sources/LedgerlineKit/{Store,Net,Crypto}/`
- iOS-Kontext: `../ledgerline-ios/CLAUDE.md`
- Web-Specs: `../ledgerline/docs/superpowers/specs/` (files-redesign, files-api-and-shared-folders,
  pwmanager-tier1, vault-sharing, nav-and-dashboard, health-module, passkeys, zk-gallery)
- Restyle-/Parität-Spec: `docs/superpowers/specs/2026-07-24-ios-parity-and-restyle-design.md`
