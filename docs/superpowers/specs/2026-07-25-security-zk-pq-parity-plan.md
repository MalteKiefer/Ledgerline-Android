# Security / ZK / Post-Quantum Parität — Plan

- **Datum:** 2026-07-25
- **Ziel:** Android bringt alle neuen Sicherheits-, ZK- und PQ-Features aus Web +
  iOS (deren CLAUDE.md + Specs) byte-exakt nach. Krypto wird gegen Fixtures verifiziert.
- **Quellen:** `../ledgerline/resources/js/shared/pq-kem.js`, `.../vault.js`,
  `../ledgerline/docs/superpowers/specs/2026-07-18-vault-sharing-design.md`,
  `.../2026-07-18-passkeys-design.md`; iOS `Sources/LedgerlineKit/{Crypto,Vaults,Passkey}/`.

## Phasen

### S1 — PQ-Hybrid-KEM — ERLEDIGT (2026-07-25)
`core/crypto/PQKEM.kt` + `MlKem768` (sodium-frei). ML-KEM-768 via BouncyCastle 1.84
(FIPS-203, seed-basiert), X25519 via libsodium, HKDF-SHA256 (BC), secretbox. Envelope
`{suite:1,epk,kem_ct,c,n}`. **Byte-exakt:** `PQKEMKatTest` (FIPS-203 NIST-KAT, seed→ek/ct/ss)
grün on-JVM; `PQKEMInstrumentedTest` (Hybrid-Roundtrip + fail-closed) grün on-device.
BouncyCastle auf 1.84 gehoben (ML-KEM); alle Deps auf latest (§16-Policy).

### S2 — Identität + Publikation — ERLEDIGT (2026-07-25)
- `core/crypto/IdentityCrypto` + `data/IdentityRepository`. X25519 (`PQKEM.x25519Keypair`)
  + ML-KEM (`PQKEM.mlkemKeypair`); Secrets (X25519-sk, 64B-Seed) **unter VK versiegelt**
  als `{c,n}` (`Crypto.sealValue`, neu). Publish `PUT /api/v1/vaults/keys`
  `{public_key, wrapped_secret_key, fingerprint, mlkem_public_key, wrapped_mlkem_secret_key}`
  (write-once, 409 key_conflict); `GET /vaults/keys` → unwrap unter VK (fail-closed bei
  Fingerprint-Mismatch, `ConstantTime.equal`). TOFU-Fingerprint = hex(BLAKE2b-16(x25519-pub)).
- `IdentityRepository.ensure()` fire-and-forget nach Unlock; `clear()` bei Lock+Logout
  (Secrets nur in-memory).
- Verifiziert: `IdentityCryptoInstrumentedTest` (on-device) — generate→publishBody→unwrap
  byte-gleich, und die entpackten Secrets entschlüsseln ein Hybrid-Envelope.
- **Offen:** `store/sharing` `knownFingerprints`-Map (TOFU-Anker) — kommt mit S3 (Sharing).

### S3 — Cross-User Shared Vaults/Ordner
- REST: `/vaults` (list/create/delete), `/vaults/{v}/store`, `resolve-recipient`,
  `members` (invite/accept/role/remove), `rotate`; Blobs `/vaults/{v}/blobs/*`.
- Wrap/Unwrap VK_vault per `PQKEM.hybridWrap`/`hybridUnwrap` je Mitglied (self-wrap,
  invite, accept, **rotate bei Entfernen**). Rollen viewer/editor/manager. TOFU-Anzeige.
- **Voraussetzung:** ein Vault-Produkt-Surface (Passwörter-Modul und/oder geteilte Ordner)
  — Android hat das noch nicht → großes Epic, eigener Plan.

### S4 — Öffentliche Share-Links — KRYPTO ERLEDIGT (2026-07-25); REST/UI offen
- `core/crypto/ShareCrypto`: SK = 32 rand Bytes (nur im URL-Fragment `#s:`); `wrapFileKey`
  (per-file key re-wrap unter SK), `sealManifest`/`openManifest` (secretbox `{c,n}`, KEIN
  Padding). Verifiziert: `ShareCryptoInstrumentedTest` (on-device, fixe Fixture-SK
  `0x00..0x1f`, Roundtrip + generic-{c,n}-Format-Kompat).
- **Offen (mit Files/Gallery-Surface, R1):** REST `POST/PUT/DELETE /files/shares` +
  `/gallery/shares` (kind/sealed_manifest/blob_refs/allow_download/expires_at/password,
  clear_password bei PUT) + Manifest-Builder (byte-genaues `JSON.stringify` je Foto/Datei)
  + Share-UI (System-Share-Sheet, Link mit `#s:`).

### S5 — Passkeys / WebAuthn
- `WebAuthn`/`Assertion`/`AttestationObject`/`JWKP256` (iOS-Referenz). Android:
  Credential Manager API. Eigenes Epic.

### S6 — ZK-/Security-Härtung — GRÖSSTENTEILS ERLEDIGT (2026-07-25)
- **`ClockRollbackGuard`** (forward-only Wall-Clock-High-Water, Keystore-versiegelt; Rollback
  > 5 min sperrt Remember-Vault-Pfad, Gate in `UnlockViewModel`). **`ConstantTime.equal`**
  (Secret-Compares; genutzt in `IdentityCrypto.unwrap`). Uniformer Decrypt-Fehler:
  `SodiumCrypto` liefert überall `null`. Tests: `SecurityGuardsTest` (isRollback + equal).
- **Offen:** Clipboard-Härtung (deferred — kein Secret-Copy-Ziel; kommt mit Passwort-Modul).

## Reihenfolge & Realismus
S1 ✓. S2 ist self-contained (baut auf PQKEM). S3/S5 sind große Produkt-Epics
(brauchen Vault-/Passwort-Surface, das Android fehlt) → mehrere Sessions, je eigener Plan.
S4/S6 sind mittel. Krypto immer fixture-verifiziert (KAT + Roundtrip), keine „fertig"-
Behauptung ohne grünen Test.
