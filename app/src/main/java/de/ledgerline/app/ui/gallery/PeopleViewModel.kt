package de.ledgerline.app.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.GalleryCache
import de.ledgerline.app.core.MetaCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.ThumbCache
import de.ledgerline.app.core.WorkspaceCache
import de.ledgerline.app.domain.model.Contact
import de.ledgerline.app.domain.usecase.MutateWorkspace
import de.ledgerline.app.core.ops.OpKind
import de.ledgerline.app.core.ops.OperationManager
import de.ledgerline.app.domain.gallery.FaceClusterer
import de.ledgerline.app.domain.gallery.FaceInput
import de.ledgerline.app.domain.gallery.PrevPerson
import de.ledgerline.app.domain.gallery.SeedCluster
import de.ledgerline.app.domain.model.GalleryPerson
import de.ledgerline.app.domain.model.GalleryPhoto
import de.ledgerline.app.domain.model.PersonFace
import de.ledgerline.app.domain.model.PhotoMetaBlob
import de.ledgerline.app.domain.usecase.GalleryBlobs
import de.ledgerline.app.domain.usecase.MutateGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/**
 * On-device face-scan orchestration + people state, ported from the web
 * `vaultGallery` face-clustering flow. Downloads run on [Dispatchers.IO], the
 * cosine-heavy clustering on [Dispatchers.Default]; all StateFlow updates are
 * safe from any thread.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val cache: GalleryCache,
    private val metaCache: MetaCache,
    private val thumbs: ThumbCache,
    private val blobs: GalleryBlobs,
    private val mutate: MutateGallery,
    private val operationManager: OperationManager,
    private val workspaceCache: WorkspaceCache,
    private val mutateWorkspace: MutateWorkspace,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _people = MutableStateFlow<List<GalleryPerson>>(emptyList())
    val people: StateFlow<List<GalleryPerson>> = _people

    init {
        viewModelScope.launch {
            cache.value.collect { recomputePeople() }
        }
    }

    private fun recomputePeople() {
        val all = cache.value.value?.manifest?.people.orEmpty()
        _people.value = all.filter { !it.hidden && personPhotos(it).isNotEmpty() }
    }

    /** Non-trashed library photos referenced by this person's faces, in library order. */
    fun personPhotos(pp: GalleryPerson): List<GalleryPhoto> {
        val photos = cache.value.value?.manifest?.photos.orEmpty()
        val byId = photos.associateBy { it.id }
        val wanted = pp.faces.mapNotNull { it.photoId }.filter { it.isNotEmpty() }.distinct().toHashSet()
        return photos.filter { !it.trashed && it.id in wanted }
    }

    fun personCover(pp: GalleryPerson): PersonFace? = pp.faces.firstOrNull()

    fun personById(id: String): GalleryPerson? =
        cache.value.value?.manifest?.people?.firstOrNull { it.id == id }

    /** Circular face-crop thumbnail for a person, cached by the crop UUID. Null on any failure. */
    suspend fun faceThumb(face: PersonFace): Bitmap? {
        val ref = face.cropRef ?: return null
        val key = face.cropKey ?: return null
        thumbs.get(ref)?.let { return it }
        return when (val r = blobs.download(ref, key)) {
            is Outcome.Ok -> BitmapFactory.decodeByteArray(r.value, 0, r.value.size)?.also { thumbs.put(ref, it) }
            is Outcome.Err -> null
        }
    }

    fun rename(pp: GalleryPerson, name: String) = viewModelScope.launch {
        mutate.invoke { m ->
            m.copy(people = m.people.map { if (it.id == pp.id) it.copy(name = name.trim()) else it })
        }
    }

    fun hide(pp: GalleryPerson) = viewModelScope.launch {
        mutate.invoke { m ->
            m.copy(people = m.people.map { if (it.id == pp.id) it.copy(hidden = true) else it })
        }
    }

    /** "Not this person": drop all of [photoId]'s faces from [pp]; drop the person if empty. */
    fun removeFromPerson(pp: GalleryPerson, photoId: String) = viewModelScope.launch {
        mutate.invoke { m ->
            val updated = m.people
                .map { p -> if (p.id == pp.id) p.copy(faces = p.faces.filterNot { it.photoId == photoId }) else p }
                .filter { it.faces.isNotEmpty() }
            m.copy(people = updated)
        }
    }

    /** Move [photoId]'s faces from [from] to person [toId]; drop [from] if it ends up empty. */
    fun reassignFace(from: GalleryPerson, toId: String, photoId: String) = viewModelScope.launch {
        mutate.invoke { m ->
            val moving = m.people.firstOrNull { it.id == from.id }?.faces?.filter { it.photoId == photoId }.orEmpty()
            if (moving.isEmpty()) return@invoke m
            val updated = m.people.map { p ->
                when (p.id) {
                    from.id -> p.copy(faces = p.faces.filterNot { it.photoId == photoId })
                    toId -> p.copy(faces = (p.faces + moving).distinctBy { it.photoId to it.idx })
                    else -> p
                }
            }.filter { it.faces.isNotEmpty() }
            m.copy(people = updated)
        }
    }

    /** Merge [source] into [target]: union+dedup faces, size-weighted centroid, keep a name. */
    fun merge(source: GalleryPerson, targetId: String) = viewModelScope.launch {
        mutate.invoke { m ->
            val src = m.people.firstOrNull { it.id == source.id }
            val tgt = m.people.firstOrNull { it.id == targetId }
            if (src == null || tgt == null || src.id == tgt.id) return@invoke m
            val merged = tgt.copy(
                faces = (tgt.faces + src.faces).distinctBy { it.photoId to it.idx },
                centroid = weightedMean(tgt.centroid, tgt.faces.size, src.centroid, src.faces.size),
                name = tgt.name.ifBlank { src.name },
            )
            m.copy(people = m.people.mapNotNull { if (it.id == source.id) null else if (it.id == targetId) merged else it })
        }
    }

    /** Non-trashed workspace contacts, for the link picker. */
    fun contacts(): List<Contact> =
        workspaceCache.value.value?.manifest?.contacts.orEmpty().filter { !it.trashed }

    private fun contactDisplayName(c: Contact): String =
        c.fn.ifBlank { listOf(c.first, c.last).filter { it.isNotBlank() }.joinToString(" ") }

    /**
     * Bidirectionally link [person] to [contact]: the person adopts the contact's name and
     * stores contactId; the contact stores personId + a name snapshot. Writes BOTH the
     * gallery store (person side) and the workspace store (contact side).
     */
    fun linkToContact(person: GalleryPerson, contact: Contact) = viewModelScope.launch {
        val cname = contactDisplayName(contact)
        mutate.invoke { m ->
            m.copy(people = m.people.map {
                if (it.id == person.id) it.copy(contactId = contact.id, contactName = cname, name = cname.ifBlank { it.name }) else it
            })
        }
        mutateWorkspace.invoke { w ->
            w.copy(contacts = w.contacts.map {
                if (it.id == contact.id) it.copy(personId = person.id, personName = cname, updated = nowIso()) else it
            })
        }
    }

    /** Break the person↔contact link on both sides. */
    fun unlinkContact(person: GalleryPerson) = viewModelScope.launch {
        val cid = person.contactId
        mutate.invoke { m ->
            m.copy(people = m.people.map { if (it.id == person.id) it.copy(contactId = null, contactName = null) else it })
        }
        if (cid != null) mutateWorkspace.invoke { w ->
            w.copy(contacts = w.contacts.map {
                if (it.id == cid) it.copy(personId = null, personName = null, updated = nowIso()) else it
            })
        }
    }

    private fun nowIso(): String =
        java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).toString()

    /** Element-wise size-weighted mean of two centroids; falls back to whichever is non-empty. */
    private fun weightedMean(a: List<Double>, na: Int, b: List<Double>, nb: Int): List<Double> {
        if (a.isEmpty()) return b
        if (b.isEmpty() || a.size != b.size) return a
        val total = (na + nb).coerceAtLeast(1)
        return a.indices.map { (a[it] * na + b[it] * nb) / total }
    }

    /** Make [photoId]'s face the person's cover by moving it to the front of the face list. */
    fun setCover(pp: GalleryPerson, photoId: String) = viewModelScope.launch {
        mutate.invoke { m ->
            m.copy(people = m.people.map { p ->
                if (p.id != pp.id) p
                else {
                    val (match, rest) = p.faces.partition { it.photoId == photoId }
                    if (match.isEmpty()) p else p.copy(faces = match + rest)
                }
            })
        }
    }

    /**
     * Scan faces and (re)cluster people. [scanLimit] > 0 = incremental scan over the
     * [scanLimit] most-recent photos (seeds existing people, appends unmatched);
     * 0 = full re-scan of the whole library (matches new clusters back to prior names).
     */
    fun scanFaces(scanLimit: Int) {
        operationManager.run(OpKind.FACE_SCAN) { report ->
            val manifest = cache.value.value?.manifest ?: return@run
            val incremental = scanLimit > 0

            val nonTrashed = manifest.photos.filter { !it.trashed }
            val targets = if (incremental) {
                nonTrashed.sortedByDescending { it.created ?: "" }.take(scanLimit)
            } else {
                nonTrashed
            }

            // 1. Ensure meta blobs are decrypted + cached (downloads on IO).
            withContext(Dispatchers.IO) {
                val toFetch = targets.filter { it.metaRef != null && !metaCache.has(it.id) }
                val total = toFetch.size
                var done = 0
                report(done, total)
                for (p in toFetch) {
                    val ref = p.metaRef ?: continue
                    when (val r = blobs.download(ref, p.metaKey ?: "")) {
                        is Outcome.Ok -> {
                            val meta = try {
                                json.decodeFromString<PhotoMetaBlob>(String(r.value))
                            } catch (_: Exception) {
                                null
                            }
                            metaCache.put(p.id, meta)
                        }
                        is Outcome.Err -> metaCache.put(p.id, null)
                    }
                    done++
                    report(done, total)
                }
            }

            // 2. Collect face inputs in target/index order.
            val faces = ArrayList<FaceInput>()
            for (p in targets) {
                val meta = metaCache.get(p.id) ?: continue
                meta.faces.forEachIndexed { idx, mf ->
                    if (mf.embedding.isNotEmpty() && mf.cropRef != null) {
                        faces.add(
                            FaceInput(
                                emb = mf.embedding,
                                member = PersonFace(p.id, idx, mf.cropRef, mf.cropKey),
                            )
                        )
                    }
                }
            }

            val existing = manifest.people
            val seeds = if (incremental) {
                existing.filter { it.centroid.isNotEmpty() }
                    .map { SeedCluster(it.id, it.name, it.hidden, it.centroid, faces.map { f -> f.member }) }
            } else {
                emptyList()
            }
            val prev = if (!incremental) {
                existing.filter { it.centroid.isNotEmpty() }
                    .map { PrevPerson(it.name, it.hidden, it.centroid) }
            } else {
                emptyList()
            }

            // 3. Cluster on Default (cosine-heavy).
            val built = withContext(Dispatchers.Default) {
                FaceClusterer.cluster(faces, seeds, prev, incremental)
            }

            var builtPeople = built.map {
                GalleryPerson(
                    id = it.id ?: UUID.randomUUID().toString(),
                    name = it.name,
                    hidden = it.hidden,
                    centroid = it.centroid,
                    faces = it.faces,
                )
            }

            // Incremental: keep existing people not represented in the built set.
            if (incremental) {
                val builtIds = builtPeople.mapNotNull { it.id.ifEmpty { null } }.toHashSet()
                val kept = existing.filter { it.id !in builtIds && it.faces.size >= 2 }
                builtPeople = builtPeople + kept
            }

            mutate.invoke { m -> m.copy(people = builtPeople) }
        }
    }
}
