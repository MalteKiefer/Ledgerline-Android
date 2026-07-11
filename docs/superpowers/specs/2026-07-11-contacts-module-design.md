# Contacts module — design (planning only)

Status: **PLANNING**. The user will implement contacts in the **web app first**; this
captures the zero-knowledge data contract (shared by web + Android) and the Android
device-sync approach so the mobile side can follow later.

## Why not CardDAV
CardDAV requires the server to parse/store vCards in plaintext → breaks the
zero-knowledge model. So contacts are a **sealed, client-encrypted** dataset like the
rest of the app; device integration happens **on-device only**, never server-side.

## Zero-knowledge storage
Add a `contacts: []` array to the existing sealed **workspace `/store` manifest**
(alongside notes/bookmarks/todos/files). It's small metadata → additive, **no server
change** (§8: the manifest schema is client-owned and may grow). Same
`sealManifest`/`openManifest` + optimistic-version/409-merge path as today.

Contact record (vCard-mappable, all client-encrypted; additive/tolerant):
```jsonc
{
  "id": "uuid",
  "firstName": "", "lastName": "", "displayName": "",
  "org": "", "title": "",
  "phones":    [ { "label": "mobile|home|work|…", "value": "" } ],
  "emails":    [ { "label": "home|work|…", "value": "" } ],
  "addresses": [ { "label": "home|work|…", "street": "", "city": "", "zip": "", "country": "" } ],
  "urls":      [ { "label": "", "value": "" } ],
  "birthday": "", "note": "", "tags": [],
  "avatarRef": null, "avatarKey": null,   // optional encrypted photo blob (like gallery)
  "favorite": false, "trashed": false, "updated": "iso"
}
```
Avatars, if used, are encrypted content blobs (reuse the files/gallery blob path).

## App behavior (Vault = source of truth)
- CRUD + search + tags + trash, exactly like the other workspace modules
  (`ContactOps` pure ops + a `ContactsViewModel` + screen, mirroring Notes/Bookmarks).
- Vault is authoritative; device sync is a deliberate, manual action.

## Device sync — AOSP `ContactsContract`, NO Google, NO CardDAV
**Export (the good part): a dedicated local "Ledgerline" contacts account.**
- Register a lightweight AOSP account type (`AccountManager` + a stub authenticator +
  a `ContactsContract` sync setup) — a LOCAL, non-network account named "Ledgerline".
- Manual "Sync to phone" writes/updates/removes ONLY the contacts in **that account**
  (`RawContacts` tagged with `ACCOUNT_TYPE`/`ACCOUNT_NAME` = Ledgerline). Because the
  app owns that namespace, reconciliation is a clean add/update/delete against a stable
  per-contact key (store the vault `id` in a `RawContacts.SOURCE_ID`/sync column) —
  **no merge conflicts, no duplicates** with the user's other contacts, and a full
  re-sync is trivial. Contacts appear in the system Contacts/dialer.
- Needs `WRITE_CONTACTS`.

**Import (one-way pull): manual "Import contacts".**
- Read selected device contacts (`ContactsContract`) into the vault; dedup by
  phone/email; last-write-wins (or prompt). User controls when. Needs `READ_CONTACTS`.

Chosen sync scope (per user, when built): TBD — options were (a) both export-account +
import, (b) display + export only, (c) vault-only. Default recommendation: **(a) both**,
export via the owned account, import as a manual pull.

## Scope estimate (Android, when built)
New module: `contacts` model + manifest field, `ContactOps` (+ tests), CRUD UI, an
`AccountManager` authenticator service + a `ContactsContract` sync/reconcile writer, a
device-contacts reader/importer, `READ_CONTACTS`/`WRITE_CONTACTS` permissions + a
Settings toggle for sync. Web builds the ZK model + CRUD first; Android mirrors the
model and adds the on-device sync.
