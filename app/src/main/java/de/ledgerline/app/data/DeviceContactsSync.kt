package de.ledgerline.app.data

import android.content.ContentProviderOperation
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import dagger.hilt.android.qualifiers.ApplicationContext
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.model.LabeledValue
import de.ledgerline.app.domain.model.PostalAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Bridges the zero-knowledge vault address book to the on-device contacts provider.
 *
 * This is the ONE place plaintext contact data leaves the encrypted store, and it is
 * strictly user-initiated (a manual export/import button, gated behind the
 * `READ_CONTACTS`/`WRITE_CONTACTS` grant). Nothing here talks to the network.
 *
 * Exported records are written as device-**local** raw contacts (null account, never
 * synced to Google or any cloud) and tagged with a [MARKER] `SOURCE_ID` so a re-export
 * is idempotent — the previous export is wiped and rewritten — and so import can skip
 * our own rows (no echo loop).
 */
class DeviceContactsSync @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One vault contact plus its already-decrypted avatar bytes (or null). */
    data class ExportItem(val contact: Contact, val avatar: ByteArray?)

    /** A contact read back from the device, plus its thumbnail photo bytes (or null). */
    data class Imported(val contact: Contact, val photo: ByteArray?)

    /**
     * Mirror [items] into the device address book. Wipes any prior Ledgerline export
     * first, then inserts each contact fresh. Returns the number written.
     */
    suspend fun export(items: List<ExportItem>): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // Idempotent: drop what we wrote last time before rewriting.
        resolver.delete(RawContacts.CONTENT_URI, "${RawContacts.SOURCE_ID} LIKE ?", arrayOf("$MARKER%"))

        val ops = ArrayList<ContentProviderOperation>()
        var count = 0
        for (item in items) {
            val c = item.contact
            val base = ops.size
            ops.add(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_NAME, null)
                    .withValue(RawContacts.ACCOUNT_TYPE, null)
                    .withValue(RawContacts.SOURCE_ID, MARKER + c.id)
                    .build(),
            )

            val display = c.fn.ifBlank { "${c.first} ${c.last}".trim() }
            ops.add(
                data(base, StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.DISPLAY_NAME, display)
                    .withValue(StructuredName.GIVEN_NAME, c.first.ifBlank { null })
                    .withValue(StructuredName.FAMILY_NAME, c.last.ifBlank { null })
                    .withValue(StructuredName.MIDDLE_NAME, c.middle.ifBlank { null })
                    .withValue(StructuredName.PREFIX, c.prefix.ifBlank { null })
                    .withValue(StructuredName.SUFFIX, c.suffix.ifBlank { null })
                    .build(),
            )
            if (c.nickname.isNotBlank()) {
                ops.add(data(base, Nickname.CONTENT_ITEM_TYPE).withValue(Nickname.NAME, c.nickname).build())
            }
            if (c.org.isNotBlank() || c.title.isNotBlank() || c.department.isNotBlank()) {
                ops.add(
                    data(base, Organization.CONTENT_ITEM_TYPE)
                        .withValue(Organization.COMPANY, c.org.ifBlank { null })
                        .withValue(Organization.TITLE, c.title.ifBlank { null })
                        .withValue(Organization.DEPARTMENT, c.department.ifBlank { null })
                        .build(),
                )
            }
            for (e in c.emails) if (e.value.isNotBlank()) {
                ops.add(
                    data(base, Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, e.value)
                        .withValue(Email.TYPE, emailType(e.type))
                        .build(),
                )
            }
            for (p in c.phones) if (p.value.isNotBlank()) {
                ops.add(
                    data(base, Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, p.value)
                        .withValue(Phone.TYPE, phoneType(p.type))
                        .build(),
                )
            }
            for (u in c.urls) if (u.value.isNotBlank()) {
                ops.add(data(base, Website.CONTENT_ITEM_TYPE).withValue(Website.URL, u.value).build())
            }
            for (a in c.addresses) if (!a.isBlank()) {
                ops.add(
                    data(base, StructuredPostal.CONTENT_ITEM_TYPE)
                        .withValue(StructuredPostal.STREET, a.street.ifBlank { null })
                        .withValue(StructuredPostal.CITY, a.city.ifBlank { null })
                        .withValue(StructuredPostal.REGION, a.region.ifBlank { null })
                        .withValue(StructuredPostal.POSTCODE, a.zip.ifBlank { null })
                        .withValue(StructuredPostal.COUNTRY, a.country.ifBlank { null })
                        .withValue(StructuredPostal.TYPE, postalType(a.type))
                        .build(),
                )
            }
            if (c.note.isNotBlank()) {
                ops.add(data(base, Note.CONTENT_ITEM_TYPE).withValue(Note.NOTE, c.note).build())
            }
            if (c.bday.isNotBlank()) {
                ops.add(
                    data(base, Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, c.bday).withValue(Event.TYPE, Event.TYPE_BIRTHDAY).build(),
                )
            }
            if (c.anniversary.isNotBlank()) {
                ops.add(
                    data(base, Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, c.anniversary).withValue(Event.TYPE, Event.TYPE_ANNIVERSARY).build(),
                )
            }
            item.avatar?.let { bytes ->
                ops.add(data(base, Photo.CONTENT_ITEM_TYPE).withValue(Photo.PHOTO, bytes).build())
            }

            count++
            // Keep each contact's ops in one batch; flush between contacts to stay well
            // under the provider's ~500-op applyBatch ceiling.
            if (ops.size >= BATCH_LIMIT) {
                resolver.applyBatch(ContactsContract.AUTHORITY, ops)
                ops.clear()
            }
        }
        if (ops.isNotEmpty()) resolver.applyBatch(ContactsContract.AUTHORITY, ops)
        count
    }

    /**
     * Read every device contact that we did NOT export ourselves and map it to a vault
     * [Contact]. `id`/`fn` are left for the caller to normalize; the caller also uploads
     * any returned photo bytes as an encrypted avatar blob.
     */
    suspend fun import(): List<Imported> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver

        // Which raw contacts are ours (skip) and which are deleted tombstones (skip)?
        val skip = HashSet<Long>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.DELETED),
            null, null, null,
        )?.use { cur ->
            val idIdx = cur.getColumnIndexOrThrow(RawContacts._ID)
            val srcIdx = cur.getColumnIndexOrThrow(RawContacts.SOURCE_ID)
            val delIdx = cur.getColumnIndexOrThrow(RawContacts.DELETED)
            while (cur.moveToNext()) {
                val deleted = cur.getInt(delIdx) == 1
                val src = cur.getString(srcIdx)
                if (deleted || (src != null && src.startsWith(MARKER))) skip.add(cur.getLong(idIdx))
            }
        }

        val builders = LinkedHashMap<Long, ContactBuilder>()
        resolver.query(Data.CONTENT_URI, DATA_PROJECTION, null, null, null)?.use { cur ->
            val rawIdx = cur.getColumnIndexOrThrow(Data.RAW_CONTACT_ID)
            val mimeIdx = cur.getColumnIndexOrThrow(Data.MIMETYPE)
            fun str(col: String): String = cur.getString(cur.getColumnIndexOrThrow(col)).orEmpty()
            fun int(col: String): Int = cur.getInt(cur.getColumnIndexOrThrow(col))
            while (cur.moveToNext()) {
                val rawId = cur.getLong(rawIdx)
                if (rawId in skip) continue
                val b = builders.getOrPut(rawId) { ContactBuilder() }
                when (cur.getString(mimeIdx)) {
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        b.first = str(StructuredName.GIVEN_NAME)
                        b.last = str(StructuredName.FAMILY_NAME)
                        b.middle = str(StructuredName.MIDDLE_NAME)
                        b.prefix = str(StructuredName.PREFIX)
                        b.suffix = str(StructuredName.SUFFIX)
                        b.display = str(StructuredName.DISPLAY_NAME)
                    }
                    Nickname.CONTENT_ITEM_TYPE -> b.nickname = str(Nickname.NAME)
                    Organization.CONTENT_ITEM_TYPE -> {
                        b.org = str(Organization.COMPANY)
                        b.title = str(Organization.TITLE)
                        b.department = str(Organization.DEPARTMENT)
                    }
                    Email.CONTENT_ITEM_TYPE -> str(Email.ADDRESS).takeIf { it.isNotBlank() }
                        ?.let { b.emails += LabeledValue(it, emailLabel(int(Email.TYPE))) }
                    Phone.CONTENT_ITEM_TYPE -> str(Phone.NUMBER).takeIf { it.isNotBlank() }
                        ?.let { b.phones += LabeledValue(it, phoneLabel(int(Phone.TYPE))) }
                    Website.CONTENT_ITEM_TYPE -> str(Website.URL).takeIf { it.isNotBlank() }
                        ?.let { b.urls += LabeledValue(it, "home") }
                    Note.CONTENT_ITEM_TYPE -> b.note = str(Note.NOTE)
                    Event.CONTENT_ITEM_TYPE -> when (int(Event.TYPE)) {
                        Event.TYPE_BIRTHDAY -> b.bday = str(Event.START_DATE)
                        Event.TYPE_ANNIVERSARY -> b.anniversary = str(Event.START_DATE)
                    }
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        val a = PostalAddress(
                            street = str(StructuredPostal.STREET),
                            city = str(StructuredPostal.CITY),
                            region = str(StructuredPostal.REGION),
                            zip = str(StructuredPostal.POSTCODE),
                            country = str(StructuredPostal.COUNTRY),
                            type = postalLabel(int(StructuredPostal.TYPE)),
                        )
                        if (!a.isBlank()) b.addresses += a
                    }
                    Photo.CONTENT_ITEM_TYPE ->
                        cur.getBlob(cur.getColumnIndexOrThrow(Photo.PHOTO))?.takeIf { it.isNotEmpty() }?.let { b.photo = it }
                }
            }
        }

        builders.values.mapNotNull { it.build() }
    }

    // ---- helpers ----

    private fun data(rawBackRef: Int, mime: String) =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawBackRef)
            .withValue(Data.MIMETYPE, mime)

    private fun PostalAddress.isBlank() =
        street.isBlank() && city.isBlank() && region.isBlank() && zip.isBlank() && country.isBlank()

    private fun emailType(t: String) = when (t) {
        "work" -> Email.TYPE_WORK
        "home" -> Email.TYPE_HOME
        else -> Email.TYPE_OTHER
    }

    private fun phoneType(t: String) = when (t) {
        "cell" -> Phone.TYPE_MOBILE
        "work" -> Phone.TYPE_WORK
        "home" -> Phone.TYPE_HOME
        else -> Phone.TYPE_OTHER
    }

    private fun postalType(t: String) = when (t) {
        "work" -> StructuredPostal.TYPE_WORK
        "home" -> StructuredPostal.TYPE_HOME
        else -> StructuredPostal.TYPE_OTHER
    }

    private fun emailLabel(t: Int) = when (t) {
        Email.TYPE_WORK -> "work"
        Email.TYPE_HOME -> "home"
        else -> "other"
    }

    private fun phoneLabel(t: Int) = when (t) {
        Phone.TYPE_MOBILE -> "cell"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_HOME -> "home"
        else -> "other"
    }

    private fun postalLabel(t: Int) = when (t) {
        StructuredPostal.TYPE_WORK -> "work"
        StructuredPostal.TYPE_HOME -> "home"
        else -> "other"
    }

    /** Mutable accumulator; one per device raw contact during import. */
    private class ContactBuilder {
        var display = ""
        var first = ""; var last = ""; var middle = ""; var prefix = ""; var suffix = ""
        var nickname = ""; var org = ""; var title = ""; var department = ""
        var note = ""; var bday = ""; var anniversary = ""
        val emails = mutableListOf<LabeledValue>()
        val phones = mutableListOf<LabeledValue>()
        val urls = mutableListOf<LabeledValue>()
        val addresses = mutableListOf<PostalAddress>()
        var photo: ByteArray? = null

        /** Drop rows with nothing usable; otherwise build a (not-yet-normalized) Contact. */
        fun build(): Imported? {
            val named = display.isNotBlank() || first.isNotBlank() || last.isNotBlank() || org.isNotBlank()
            if (!named && emails.isEmpty() && phones.isEmpty()) return null
            val fn = display.ifBlank { "$first $last".trim() }
            return Imported(
                Contact(
                    fn = fn, first = first, last = last, middle = middle, prefix = prefix, suffix = suffix,
                    nickname = nickname, org = org, title = title, department = department,
                    note = note, bday = bday, anniversary = anniversary,
                    emails = emails.toList(), phones = phones.toList(), urls = urls.toList(),
                    addresses = addresses.toList(),
                ),
                photo,
            )
        }
    }

    companion object {
        /** `SOURCE_ID` prefix marking a raw contact as one WE exported (idempotency + no import echo). */
        private const val MARKER = "ledgerline:"
        private const val BATCH_LIMIT = 400

        private val DATA_PROJECTION = arrayOf(
            Data.RAW_CONTACT_ID, Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
            Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10, Data.DATA15,
        )
    }
}
