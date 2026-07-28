package de.ledgerline.app.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.ExploreCache
import de.ledgerline.app.core.explore.TrackPoint
import de.ledgerline.app.core.explore.TrackStatsComputer
import de.ledgerline.app.core.tracker.ActivityKind
import de.ledgerline.app.core.tracker.TrackerEngine
import de.ledgerline.app.core.tracker.TrackerUi
import de.ledgerline.app.data.ExploreRepository
import de.ledgerline.app.domain.model.ExploreTrack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val engine: TrackerEngine,
    private val repo: ExploreRepository,
    private val mapStore: de.ledgerline.app.core.map.OfflineMapStore,
    private val demStore: de.ledgerline.app.core.map.DemStore,
    private val settings: de.ledgerline.app.data.SettingsStore,
    private val healthCache: de.ledgerline.app.core.HealthCache,
    cache: ExploreCache,
) : ViewModel() {

    /**
     * Estimated calories for a track from the tour stats + the user's health data (latest weight +
     * sex). Null when no weight is on file (mirrors the web, which only shows it when weight/sex/
     * height are known). Reuses the already-decrypted [HealthCache] — no extra fetch.
     */
    fun caloriesFor(track: ExploreTrack): Long? {
        val m = healthCache.value.value?.manifest ?: return null
        val weightKg = m.entries.filter { it.metric == "weight" }.maxByOrNull { it.ts }?.v ?: return null
        val s = track.stats ?: return null
        return de.ledgerline.app.core.explore.ExploreCalories.estimate(
            distanceM = s.distanceM,
            durationS = s.durationMovingS.takeIf { it > 0 } ?: s.durationTotalS,
            ascentM = s.ascentM,
            weightKg = weightKg,
            sex = m.profile.sex.ifBlank { null },
        )
    }

    /** Terrain relief (hillshading) enabled + a version that bumps as DEM tiles arrive. */
    val terrain: StateFlow<Boolean> = settings.terrainEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val demVersion: StateFlow<Int> = demStore.version
    fun demFolder(): java.io.File = demStore.demFolder()
    fun ensureDem(minLat: Double, minLng: Double, maxLat: Double, maxLng: Double) = demStore.ensureTilesFor(minLat, minLng, maxLat, maxLng)

    /** Live recording snapshot. */
    val ui: StateFlow<TrackerUi> = engine.ui

    /** Global display preferences (units + clock), server-synced. */
    val prefs: StateFlow<de.ledgerline.app.core.prefs.DisplayPrefs> = settings.displayPrefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), de.ledgerline.app.core.prefs.DisplayPrefs())

    /** Distance/speed/pace unit system, derived from the distance preference. */
    val unit: StateFlow<de.ledgerline.app.core.units.UnitSystem> = settings.displayPrefs
        .map { if (it.imperialDistance) de.ledgerline.app.core.units.UnitSystem.IMPERIAL else de.ledgerline.app.core.units.UnitSystem.METRIC }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), de.ledgerline.app.core.units.UnitSystem.METRIC)

    /** Whether elevation renders in feet (its own preference, may differ from distance). */
    val elevationFeet: StateFlow<Boolean> = settings.displayPrefs
        .map { it.feetElevation }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Installed offline `.map` files for the renderer. */
    fun offlineMaps(): List<java.io.File> { mapStore.refreshInstalled(); return mapStore.installedFiles() }

    // ---- First-start world base map offer ----
    /** True once the one-time world-map download offer has been shown. */
    val worldMapOffered: StateFlow<Boolean> = settings.worldMapOffered
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun worldMapInstalled(): Boolean = mapStore.isInstalled(mapStore.worldMapId)
    fun downloadWorldMap() { mapStore.region(mapStore.worldMapId)?.let { mapStore.startDownload(it) } }
    fun markWorldMapOffered() { viewModelScope.launch { settings.setWorldMapOffered(true) } }

    /** Live per-region download state (for progress banners). */
    val mapDownloads: StateFlow<Map<String, de.ledgerline.app.core.map.MapDownloadState>> = mapStore.state
    fun regionName(id: String): String? = mapStore.region(id)?.name
    fun cancelDownload(id: String) = mapStore.cancelDownload(id)

    // ---- Smart offer: reverse-geocode → matching offline region not yet downloaded ----
    suspend fun reverseAddress(lat: Double, lng: Double) = repo.reverseAddress(lat, lng)

    /** A catalog region matching the given place [address] parts that isn't installed/downloading. */
    fun suggestRegion(address: Map<String, String>): de.ledgerline.app.core.map.OfflineMapRegion? {
        val state = address["state"] ?: address["region"] ?: address["province"]
        val country = address["country"]
        fun norm(s: String) = s.lowercase().replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss").trim()
        val leaves = mapStore.catalog.leaves().filter { it.id != mapStore.worldMapId }
        fun match(needle: String?): de.ledgerline.app.core.map.OfflineMapRegion? {
            val n = needle?.let { norm(it) }?.takeIf { it.isNotBlank() } ?: return null
            return leaves.firstOrNull { r ->
                val name = norm(r.name); val stem = norm(r.path?.substringAfterLast('/')?.removeSuffix(".map") ?: "")
                name == n || stem == n || name.contains(n) || n.contains(name) || stem.contains(n) || n.contains(stem)
            }
        }
        val hit = match(state) ?: match(country) ?: return null
        val st = mapStore.state.value[hit.id]
        return if (mapStore.isInstalled(hit.id) || st is de.ledgerline.app.core.map.MapDownloadState.Downloading) null else hit
    }

    fun downloadRegion(region: de.ledgerline.app.core.map.OfflineMapRegion) = mapStore.startDownload(region)

    /** Saved tracks, newest first. */
    val tracks: StateFlow<List<ExploreTrack>> = cache.value
        .map { store -> store?.manifest?.tracks?.sortedByDescending { it.createdAt ?: "" } ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { viewModelScope.launch { repo.load() } }

    fun refresh() { viewModelScope.launch { repo.load() } }

    fun hasLocationPermission() = engine.hasPermission()
    fun setActivity(a: ActivityKind) = engine.setActivity(a)
    fun start(a: ActivityKind) = engine.start(a)
    fun pause() = engine.pause()
    fun resume() = engine.resume()
    fun stop() = engine.stop()
    fun discard() = engine.reset()

    /** Persist the just-recorded track under [name], then reset the recorder. */
    fun save(name: String, onDone: (Boolean) -> Unit) {
        val points = engine.snapshotPoints()
        if (points.size < 2) { onDone(false); return }
        val track = finalize(name.ifBlank { defaultName() }, points, engine.ui.value.activity)
        viewModelScope.launch {
            val res = repo.save { m -> m.copy(tracks = m.tracks + track) }
            val ok = res is de.ledgerline.app.core.Outcome.Ok
            if (ok) engine.reset()
            onDone(ok)
        }
    }

    fun deleteTrack(id: String) {
        viewModelScope.launch { repo.save { m -> m.copy(tracks = m.tracks.filterNot { it.id == id }) } }
    }

    // ---- Reverse geocoding (Karte place chip) ----
    suspend fun reverseGeocode(lat: Double, lng: Double): String? = repo.reverse(lat, lng)

    // ---- Tour planning (tap waypoints → snap to a route → save as a planned track) ----
    private val _waypoints = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val waypoints: StateFlow<List<Pair<Double, Double>>> = _waypoints
    private val _route = kotlinx.coroutines.flow.MutableStateFlow<List<Pair<Double, Double>>>(emptyList())
    val route: StateFlow<List<Pair<Double, Double>>> = _route

    fun addWaypoint(lat: Double, lng: Double) { _waypoints.value = _waypoints.value + (lat to lng); _route.value = emptyList() }
    fun undoWaypoint() { _waypoints.value = _waypoints.value.dropLast(1); _route.value = emptyList() }
    fun clearPlan() { _waypoints.value = emptyList(); _route.value = emptyList() }
    fun snapRoute() { viewModelScope.launch { repo.route(_waypoints.value)?.let { _route.value = it } } }

    fun savePlanned(name: String, onDone: (Boolean) -> Unit) {
        val coords = _route.value.ifEmpty { _waypoints.value }
        val points = coords.map { TrackPoint(it.first, it.second, null, 0L) }
        if (points.size < 2) { onDone(false); return }
        val track = buildTrack(name.ifBlank { "Route" }, points, "planned", null)
        viewModelScope.launch {
            val ok = repo.save { m -> m.copy(tracks = m.tracks + track) } is de.ledgerline.app.core.Outcome.Ok
            if (ok) clearPlan()
            onDone(ok)
        }
    }

    // ---- Import (GPX/KML) ----
    fun importParsed(name: String, sourceFormat: String, points: List<TrackPoint>, onDone: (Boolean) -> Unit) {
        if (points.size < 2) { onDone(false); return }
        val track = buildTrack(name, points, sourceFormat, null)
        viewModelScope.launch {
            val ok = repo.save { m -> m.copy(tracks = m.tracks + track) } is de.ledgerline.app.core.Outcome.Ok
            onDone(ok)
        }
    }

    private fun finalize(name: String, points: List<TrackPoint>, activity: ActivityKind): ExploreTrack =
        buildTrack(name, points, "recorded", activity.name.lowercase())

    private fun buildTrack(name: String, points: List<TrackPoint>, sourceFormat: String, activity: String?): ExploreTrack {
        val hasTime = points.any { it.t > 0L }
        return ExploreTrack(
            id = UUID.randomUUID().toString(),
            name = name,
            sourceFormat = sourceFormat,
            activity = activity,
            startedAt = if (hasTime) Instant.ofEpochMilli(points.first { it.t > 0L }.t).toString() else null,
            endedAt = if (hasTime) Instant.ofEpochMilli(points.last { it.t > 0L }.t).toString() else null,
            createdAt = Instant.now().toString(),
            points = points,
            stats = TrackStatsComputer.computeStats(points),
            bbox = TrackStatsComputer.bbox(points),
        )
    }

    fun defaultName(): String = engine.ui.value.activity.name.lowercase().replaceFirstChar { it.uppercase() }
}
