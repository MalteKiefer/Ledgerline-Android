package de.ledgerline.app.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.ledgerline.app.core.HealthCache
import de.ledgerline.app.core.Outcome
import de.ledgerline.app.core.Ids
import de.ledgerline.app.core.health.HealthFasting
import de.ledgerline.app.core.health.HealthMetrics
import de.ledgerline.app.data.HealthRepository
import de.ledgerline.app.domain.model.HealthEntry
import de.ledgerline.app.domain.model.HealthFast
import de.ledgerline.app.domain.model.HealthManifest
import de.ledgerline.app.domain.model.HealthProfile
import de.ledgerline.app.domain.model.HealthUnits
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * Drives the Health module: reads the decrypted `store/health` manifest from [HealthCache] and
 * issues optimistic writes through [HealthRepository]. Measurements are entered in display units
 * and converted to canonical storage units here (kg/°C/mg-dL), mirroring the web `saveEditor`.
 */
@HiltViewModel
class HealthViewModel @Inject constructor(
    private val repo: HealthRepository,
    cache: HealthCache,
) : ViewModel() {

    val manifest: StateFlow<HealthManifest> =
        cache.value.map { it?.manifest ?: HealthManifest() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HealthManifest())

    val units: StateFlow<HealthUnits> =
        cache.value.map { it?.manifest?.profile?.units ?: HealthUnits() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HealthUnits())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error.asStateFlow()

    private val _selectedMetric = MutableStateFlow(HealthMetrics.METRICS.first().key)
    val selectedMetric: StateFlow<String> = _selectedMetric.asStateFlow()

    private val _chartRange = MutableStateFlow("90d")
    val chartRange: StateFlow<String> = _chartRange.asStateFlow()

    /** Live clock (epoch ms), ticks each second while a fast is running so the timer updates. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        load()
        // Tick the clock once per second whenever an active fast exists.
        viewModelScope.launch {
            while (true) {
                if (HealthFasting.activeFast(manifest.value.fasts) != null) {
                    _now.value = System.currentTimeMillis()
                }
                delay(1000)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = repo.load() is Outcome.Err && manifest.value.entries.isEmpty()
            _loading.value = false
        }
    }

    fun select(key: String) { _selectedMetric.value = key }
    fun setRange(range: String) { _chartRange.value = range }
    fun consumeToast() { _toast.value = null }
    private fun toast(msg: String) { _toast.value = msg }

    // ---- Measurements ------------------------------------------------------

    /**
     * Add or update a measurement. [valueText]/[value2Text] are in DISPLAY units and converted to
     * canonical here. [tsIso] is the entry timestamp (ISO-8601). Returns false if the value is
     * non-numeric / non-positive (caller keeps the editor open). [editing] = the entry being
     * replaced, or null for a new one.
     */
    fun saveEntry(metric: String, valueText: String, value2Text: String, tsIso: String, note: String, editing: HealthEntry?): Boolean {
        val v = valueText.trim().replace(',', '.').toDoubleOrNull()
        if (v == null || v <= 0) return false
        val u = units.value
        var canonV = v
        if (metric == "weight" && u.weight == "lb") canonV = HealthMetrics.lbToKg(v)
        if (metric == "temp" && u.temp == "f") canonV = HealthMetrics.fToC(v)
        if (metric == "glucose" && u.glucose == "mmoll") canonV = HealthMetrics.mmollToMgdl(v)
        var canonV2: Double? = null
        if (metric == "bp") {
            val raw2 = value2Text.trim().replace(',', '.').toDoubleOrNull()
            if (raw2 != null && raw2 > 0) canonV2 = raw2
        }
        viewModelScope.launch {
            repo.save { m ->
                if (editing != null) {
                    m.copy(entries = m.entries.map {
                        if (it.id == editing.id) it.copy(v = canonV, v2 = canonV2, ts = tsIso, note = note) else it
                    })
                } else {
                    val e = HealthEntry(id = Ids.newId(), ts = tsIso, metric = metric, v = canonV, v2 = canonV2, note = note)
                    m.copy(entries = listOf(e) + m.entries)
                }
            }
        }
        return true
    }

    fun deleteEntry(entry: HealthEntry) {
        viewModelScope.launch {
            repo.save { m -> m.copy(entries = m.entries.filterNot { it.id == entry.id }) }
        }
    }

    // ---- Master data -------------------------------------------------------

    fun saveProfile(update: (HealthProfile) -> HealthProfile) {
        viewModelScope.launch { repo.save { m -> m.copy(profile = update(m.profile)) } }
    }

    fun setUnits(units: HealthUnits) = saveProfile { it.copy(units = units) }

    /** Derived age from birthdate (whole years), or null. */
    fun age(): Int? = HealthMetrics.computeAge(manifest.value.profile.birthdate, LocalDate.now())

    /** Derived BMI from the latest weight + profile height, or null. */
    fun bmi(): Double? {
        val latest = manifest.value.entries.filter { it.metric == "weight" }.maxByOrNull { it.ts } ?: return null
        return HealthMetrics.computeBmi(latest.v, manifest.value.profile.heightCm)
    }

    // ---- Intermittent fasting ---------------------------------------------

    fun activeFast(): HealthFast? = HealthFasting.activeFast(manifest.value.fasts)

    fun fastHistory(): List<HealthFast> =
        manifest.value.fasts.filter { !it.end.isNullOrEmpty() }.sortedByDescending { it.start }

    fun startFast(hours: Int) {
        viewModelScope.launch {
            // Re-check against the freshest server state — never start a second fast.
            repo.load()
            if (activeFast() != null) { toast(TOAST_ALREADY_RUNNING); return@launch }
            _now.value = System.currentTimeMillis()
            repo.save { m ->
                val f = HealthFast(id = Ids.newId(), start = Instant.now().toString(), end = null, targetHours = hours, note = "")
                m.copy(fasts = m.fasts + f)
            }
        }
    }

    fun stopFast(fast: HealthFast) {
        viewModelScope.launch {
            repo.save { m -> m.copy(fasts = m.fasts.map { if (it.id == fast.id) it.copy(end = Instant.now().toString()) else it }) }
        }
    }

    /** Edit a fast's start/end/target/note. [startIso]/[endIso] already normalized to ISO (end null = running). */
    fun saveFastEdit(fast: HealthFast, startIso: String, endIso: String?, targetHours: Int, note: String): Boolean {
        if (!HealthFasting.isValid(startIso, endIso, targetHours)) { toast(TOAST_INVALID); return false }
        // Clearing the end must not create a second active fast.
        if (endIso.isNullOrEmpty() && activeFast()?.let { it.id != fast.id } == true) { toast(TOAST_ALREADY_RUNNING); return false }
        viewModelScope.launch {
            repo.save { m ->
                m.copy(fasts = m.fasts.map {
                    if (it.id == fast.id) it.copy(start = startIso, end = endIso, targetHours = targetHours, note = note) else it
                })
            }
        }
        return true
    }

    fun deleteFast(fast: HealthFast) {
        viewModelScope.launch { repo.save { m -> m.copy(fasts = m.fasts.filterNot { it.id == fast.id }) } }
    }

    private companion object {
        // Sentinels the screen maps to localized strings.
        const val TOAST_ALREADY_RUNNING = "fast_already_running"
        const val TOAST_INVALID = "fast_invalid"
    }
}
