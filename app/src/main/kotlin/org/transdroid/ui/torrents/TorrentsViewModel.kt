/*
 * Copyright 2010-2026 Eric Kok et al.
 *
 * Transdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Transdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Transdroid. If not, see <https://www.gnu.org/licenses/>.
 */
package org.transdroid.ui.torrents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.transdroid.AppContainer
import org.transdroid.appContainer
import org.transdroid.data.PeerSort
import org.transdroid.data.ServerProfile
import org.transdroid.errorlog.ErrorLog
import org.transdroid.protocol.DaemonException
import org.transdroid.protocol.FilePriority
import org.transdroid.protocol.Peer
import org.transdroid.protocol.Torrent
import org.transdroid.protocol.TorrentFile
import org.transdroid.protocol.TorrentStatus
import org.transdroid.protocol.Tracker

/** User-facing error kinds; mapped to localized strings in the UI layer. */
sealed class UiError {
    data class Connection(val host: String) : UiError()
    data class Authentication(val detail: String? = null) : UiError()
    data object Ssl : UiError()
    data class Unexpected(val detail: String? = null) : UiError()
}

internal fun Throwable.toUiError(host: String): UiError {
    ErrorLog.log("Daemon", "Error for $host", this)
    return when (this) {
        is DaemonException.Connection -> UiError.Connection(host)
        is DaemonException.Authentication -> UiError.Authentication(message)
        is DaemonException.UntrustedServer -> UiError.Ssl
        is DaemonException.UnexpectedResponse -> UiError.Unexpected(message)
        else -> UiError.Unexpected()
    }
}

/** Sentinel [TorrentsUiState.labelFilters] entry for the drawer's "No label" bucket; never a real torrent label. */
const val NO_LABEL = "\u0000no-label"

enum class TorrentFilter {
    ALL, ACTIVE, DOWNLOADING, SEEDING, COMPLETED, PAUSED, TRACKER_ISSUES, ERROR;

    fun matches(torrent: Torrent): Boolean = when (this) {
        ALL -> true
        // Anything moving bytes right now, in either direction. qBittorrent's stalled entries and
        // Transmission's idle seeds report 0 B/s and are deliberately excluded.
        ACTIVE -> torrent.downloadRate > 0 || torrent.uploadRate > 0
        DOWNLOADING -> torrent.status == TorrentStatus.DOWNLOADING || torrent.status == TorrentStatus.QUEUED ||
            torrent.status == TorrentStatus.CHECKING
        SEEDING -> torrent.status == TorrentStatus.SEEDING
        COMPLETED -> torrent.isFinished
        PAUSED -> torrent.status == TorrentStatus.PAUSED
        // Tracker announcements failing while the torrent keeps seeding/downloading. Transmission
        // reports these as error codes 1/2; the torrent itself is healthy, so they are not ERROR.
        TRACKER_ISSUES -> torrent.trackerIssues
        ERROR -> torrent.status == TorrentStatus.ERROR
    }
}

enum class TorrentSort {
    DATE_ADDED, NAME, DOWNLOAD_SPEED, UPLOAD_SPEED, SIZE, PROGRESS, RATIO, COMPLETED;

    /**
     * The direction a field starts in the first time it's selected - matches Transdroid 2's
     * per-field defaults (newest/fastest/highest first; name A-to-Z).
     */
    val defaultDescending: Boolean
        get() = this != NAME

    private fun ascending(): Comparator<Torrent> = when (this) {
        DATE_ADDED -> compareBy { it.addedTimestamp ?: Long.MIN_VALUE }
        NAME -> compareBy { it.name.lowercase() }
        DOWNLOAD_SPEED -> compareBy { it.downloadRate }
        UPLOAD_SPEED -> compareBy { it.uploadRate }
        SIZE -> compareBy { it.sizeBytes }
        PROGRESS -> compareBy { it.progress }
        RATIO -> compareBy { it.ratio }
        // Never-completed torrents sort as oldest, so they sink to the bottom of a descending sort
        COMPLETED -> compareBy { it.completedTimestamp ?: 0L }
    }

    fun comparator(descending: Boolean): Comparator<Torrent> =
        ascending().let { if (descending) it.reversed() else it }

    companion object {
        val DEFAULT = DATE_ADDED

        /** Tolerates a stored name that no longer exists, e.g. after a constant is renamed. */
        fun fromName(name: String?): TorrentSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

data class TorrentsUiState(
    /** Published build newer than this one, when the startup check found one. */
    val availableUpdateName: String? = null,
    val activeProfile: ServerProfile? = null,
    /** All configured servers, for the drawer's server switcher. */
    val allProfiles: List<ServerProfile> = emptyList(),
    /** False until the profile store has emitted, so we don't flash the welcome screen. */
    val profilesLoaded: Boolean = false,
    /** Number of configured servers; single-server flows can skip confirmation steps. */
    val profileCount: Int = 0,
    val torrents: List<Torrent> = emptyList(),
    /** True after the first successful load for the active profile. */
    val hasLoaded: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val filter: TorrentFilter = TorrentFilter.ALL,
    /** OR semantics: a torrent matches if it carries any of the selected labels. */
    val labelFilters: Set<String> = emptySet(),
    /** OR semantics: a torrent matches if any of its [Torrent.trackers] is selected. */
    val trackerFilters: Set<String> = emptySet(),
    /**
     * When set, narrows the error or tracker-issues bucket to one reason key
     * (see [errorReasonKey]); null means the whole bucket.
     */
    val errorReasonFilter: String? = null,
    /**
     * Daemon-reported announce failure wording per torrent (id -> message). qBittorrent keeps this
     * out of `errorString`, so it has to be fetched per torrent before the drawer can group by it.
     */
    val trackerReasonEntries: Map<String, List<String>> = emptyMap(),
    /** Entries behind [trackerReasonEntries]; the group header shows this once known. */
    val trackerReasonTotal: Int = 0,
    val trackerReasonsLoading: Boolean = false,
    /** Set when the last probe failed; the previous classification is kept rather than cleared. */
    val trackerReasonsFailed: Boolean = false,
    /** Torrent ids the current [trackerReasonEntries] was fetched for, to avoid repeat probes. */
    val trackerReasonsIds: Set<String> = emptySet(),
    /** Profile the classification came from; a different daemon must never reuse it. */
    val trackerReasonsProfile: String? = null,
    /** Free-text filter over torrent names, from the drawer's filter field. */
    val nameQuery: String = "",
    val sort: TorrentSort = TorrentSort.DATE_ADDED,
    val sortDescending: Boolean = TorrentSort.DATE_ADDED.defaultDescending,
    val selectedTorrentId: String? = null,
    /**
     * Multi-select mode: empty means the list behaves normally. Ids are kept across refreshes and
     * only counted while they still exist, see [selectionCount].
     */
    val selection: Set<String> = emptySet(),
    val files: Map<String, List<TorrentFile>> = emptyMap(),
    val trackers: Map<String, List<Tracker>> = emptyMap(),
    val peers: Map<String, List<Peer>> = emptyMap(),
    /** The order the details screen lists [peers] in; a preference, see [org.transdroid.data.SettingsRepository.peerSort]. */
    val peerSort: PeerSort = PeerSort.DEFAULT,
    /** Whether the active server's client exposes an alternative ("turtle") speed limits toggle. */
    val altSpeedSupported: Boolean = false,
    val altSpeedEnabled: Boolean = false,
    /** Download locations previously used on the active server, most-recent first; see [org.transdroid.data.SettingsRepository.recentDownloadLocations]. */
    val recentDownloadLocations: List<String> = emptyList(),
) {
    val availableLabels: List<String>
        get() = torrents.flatMap { it.labels }.distinct().sorted()

    /** Whether any torrent carries no label at all, i.e. whether the drawer's "No label" bucket applies. */
    val hasUnlabeledTorrents: Boolean
        get() = torrents.any { it.labels.isEmpty() }

    /** Distinct tracker hostnames across all loaded torrents; empty for a client with no bulk tracker data (rTorrent). */
    val availableTrackers: List<String>
        get() = torrents.flatMap { it.trackers }.distinct().sorted()

    /** Torrents that own at least one failing tracker, once the daemon has been probed. */
    val trackerReasonAllIds: Set<String>
        get() = if (trackerReasonEntries.isEmpty()) emptySet() else trackerReasonEntries.values.flatten().toSet()

    val visibleTorrents: List<Torrent>
        get() = torrents
            .filter {
                // Only the tracker group is backed by the probed entries; the local-error group
                // keeps using the torrent's own wording, and must not be looked up in that table
                // (its keys never appear there, which read as an empty filter).
                val trackerScoped =
                    filter == TorrentFilter.TRACKER_ISSUES && trackerReasonEntries.isNotEmpty()
                // Once probed, the group stands for "has a failing tracker", which is what its
                // numbers count; the daemon's own error flag misses the torrents that only have
                // one dead tracker among several.
                val bucketOk = if (trackerScoped) it.id in trackerReasonAllIds else filter.matches(it)
                val reasonOk = when {
                    errorReasonFilter == null -> true
                    trackerScoped -> trackerReasonEntries[errorReasonFilter]?.contains(it.id) == true
                    else -> it.errorReasonKey() == errorReasonFilter
                }
                bucketOk && reasonOk &&
                    (
                        labelFilters.isEmpty() ||
                            it.labels.any { label -> label in labelFilters } ||
                            (NO_LABEL in labelFilters && it.labels.isEmpty())
                        ) &&
                    (trackerFilters.isEmpty() || it.trackers.any { tracker -> tracker in trackerFilters }) &&
                    (nameQuery.isBlank() || it.name.contains(nameQuery, ignoreCase = true))
            }
            .sortedWith(sort.comparator(sortDescending).thenBy { it.name.lowercase() })

    val selectedTorrent: Torrent?
        get() = torrents.firstOrNull { it.id == selectedTorrentId }

    /** Selected torrents that still exist on the server; drives the selection toolbar's counter. */
    val selectionCount: Int
        get() = torrents.count { it.id in selection }

    fun countFor(filter: TorrentFilter): Int = torrents.count { filter.matches(it) }

    /**
     * Torrents inside [bucket] grouped by reason. Scoped by the bucket rather than by [filter], so
     * the drawer can expand a failure group without that group becoming the active filter.
     */
    fun reasonCountsFor(bucket: TorrentFilter): List<ErrorReasonCount> {
        if (bucket == TorrentFilter.TRACKER_ISSUES && trackerReasonEntries.isNotEmpty()) {
            // Per torrent, prefer the daemon's announce wording, but fall back to `errorString`:
            // qBittorrent only has the former, while Transmission often only fills the latter
            // (its trackerStats carry no result for HTTP-level failures).
            val byId = torrents.associateBy { it.id }
            return trackerReasonEntries
                .map { (key, ids) ->
                    // Counts follow the daemon's tracker entries (a torrent with three dead
                    // trackers counts three times, as the web UI does); sizes stay per torrent.
                    val uniq = ids.toSet()
                    ErrorReasonCount(
                        key,
                        ids.size,
                        uniq.mapNotNull { byId[it] }.sumOf { it.sizeBytes },
                    )
                }
                .sortedByDescending { it.count }
        }
        val inBucket: (org.transdroid.protocol.Torrent) -> Boolean = when (bucket) {
            TorrentFilter.ERROR -> { t -> t.status == org.transdroid.protocol.TorrentStatus.ERROR }
            TorrentFilter.TRACKER_ISSUES -> { t -> t.trackerIssues }
            else -> return emptyList()
        }
        return torrents.asSequence()
            .filter(inBucket)
            .mapNotNull { t -> t.errorReasonKey()?.let { key -> key to t } }
            .groupBy({ it.first }, { it.second })
            .map { (key, list) -> ErrorReasonCount(key, list.size, list.sumOf { it.sizeBytes }) }
            .sortedByDescending { it.count }
    }

    fun countForReason(key: String): Int =
        torrents.count { it.errorReasonKey() == key }

    fun countForLabel(label: String): Int =
        if (label == NO_LABEL) torrents.count { it.labels.isEmpty() } else torrents.count { label in it.labels }

    fun countForTracker(tracker: String): Int = torrents.count { tracker in it.trackers }

    val totalDownloadRate: Long
        get() = torrents.sumOf { it.downloadRate }

    val totalUploadRate: Long
        get() = torrents.sumOf { it.uploadRate }

    val activeCount: Int
        get() = torrents.count { it.status == TorrentStatus.DOWNLOADING }

    val sharingCount: Int
        get() = torrents.count { it.status == TorrentStatus.SEEDING }
}

class TorrentsViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(TorrentsUiState())
    val ui: StateFlow<TorrentsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.activeProfile, container.profilesRepository.profiles) { active, all ->
                active to all
            }.collect { (profile, all) ->
                _ui.update { state ->
                    val switched = profile?.id != state.activeProfile?.id
                    state.copy(
                        activeProfile = profile,
                        allProfiles = all,
                        profilesLoaded = true,
                        profileCount = all.size,
                        torrents = if (switched) emptyList() else state.torrents,
                        hasLoaded = if (switched) false else state.hasLoaded,
                        files = if (switched) emptyMap() else state.files,
                        trackers = if (switched) emptyMap() else state.trackers,
                        peers = if (switched) emptyMap() else state.peers,
                        error = if (switched) null else state.error,
                        // The classification belongs to the daemon it came from: keeping it across
                        // a switch shows one client's tracker failures under another's torrents.
                        trackerReasonEntries = if (switched) emptyMap() else state.trackerReasonEntries,
                        trackerReasonTotal = if (switched) 0 else state.trackerReasonTotal,
                        trackerReasonsIds = if (switched) emptySet() else state.trackerReasonsIds,
                        trackerReasonsProfile = if (switched) null else state.trackerReasonsProfile,
                        trackerReasonsLoading = if (switched) false else state.trackerReasonsLoading,
                        trackerReasonsFailed = if (switched) false else state.trackerReasonsFailed,
                    )
                }
                if (profile != null) refresh(showSpinner = false)
            }
        }
        viewModelScope.launch {
            container.activeProfile.flatMapLatest { profile ->
                profile?.let { container.settingsRepository.recentDownloadLocations(it.id) } ?: flowOf(emptyList())
            }.collect { locations -> _ui.update { it.copy(recentDownloadLocations = locations) } }
        }
        viewModelScope.launch {
            container.availableUpdate.collect { info ->
                _ui.update { it.copy(availableUpdateName = info?.versionName) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.peerSort.collect { sort -> _ui.update { it.copy(peerSort = sort) } }
        }
        viewModelScope.launch {
            combine(
                container.settingsRepository.torrentSort,
                container.settingsRepository.torrentSortDescending,
            ) { sort, descending -> sort to descending }
                .collect { (sort, descending) -> _ui.update { it.copy(sort = sort, sortDescending = descending) } }
        }
    }

    /** Runs while the torrents UI is started; cancellation stops the polling. */
    suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            refreshNow(showSpinner = false)
            delay(container.settingsRepository.pollIntervalSeconds.first() * 1000L)
        }
    }

    fun refresh(showSpinner: Boolean = true) {
        viewModelScope.launch { refreshNow(showSpinner) }
    }

    private suspend fun refreshNow(showSpinner: Boolean) {
        val profile = _ui.value.activeProfile ?: return
        if (showSpinner) _ui.update { it.copy(refreshing = true) }
        try {
            val adapter = container.adapterFor(profile)
            val torrents = adapter.listTorrents()
            val altSpeedEnabled = if (adapter.supportsAltSpeedLimits) {
                // A transient failure here shouldn't blank the whole list; just keep the last-known state.
                try {
                    adapter.isAltSpeedLimitsEnabled()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _ui.value.altSpeedEnabled
                }
            } else {
                false
            }
            _ui.update {
                if (it.activeProfile?.id != profile.id) it
                else it.copy(
                    torrents = torrents,
                    hasLoaded = true,
                    refreshing = false,
                    error = null,
                    altSpeedSupported = adapter.supportsAltSpeedLimits,
                    altSpeedEnabled = altSpeedEnabled,
                )
            }
            container.widgetStateRepository.update(profile.id, profile.displayName, torrents)
            // Keep an open torrent-details screen's Files/Trackers tabs current too, not just the
            // main list - they're otherwise only ever loaded once, when the screen first opens.
            _ui.value.selectedTorrentId
                ?.takeIf { id -> torrents.any { it.id == id } }
                ?.let { refreshDetails(adapter, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update {
                // A late failure from a server the user already switched away from is stale
                if (it.activeProfile?.id != profile.id) it
                else it.copy(refreshing = false, error = e.toUiError(profile.host))
            }
        }
    }

    private suspend fun refreshDetails(adapter: org.transdroid.protocol.DaemonAdapter, torrentId: String) {
        try {
            val files = adapter.listFiles(torrentId)
            _ui.update { it.copy(files = it.files + (torrentId to files)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep showing the last-known files rather than clearing them on a transient failure
        }
        try {
            val trackers = adapter.listTrackers(torrentId)
            _ui.update { it.copy(trackers = it.trackers + (torrentId to trackers)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep showing the last-known trackers rather than clearing them on a transient failure
        }
        try {
            val peers = fetchPeers(adapter, torrentId)
            _ui.update { it.copy(peers = it.peers + (torrentId to peers)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep showing the last-known peers rather than clearing them on a transient failure
        }
    }

    /** The daemon's peer list, with countries filled in from the local GeoIP database when one is downloaded. */
    private suspend fun fetchPeers(adapter: org.transdroid.protocol.DaemonAdapter, torrentId: String): List<Peer> =
        container.geoIpDatabase.withCountries(adapter.listPeers(torrentId))

    fun setFilter(filter: TorrentFilter) {
        _ui.update { it.copy(filter = filter, errorReasonFilter = null) }
    }

    /**
     * Select a whole failure bucket, or narrow it to one reason. Taking the bucket explicitly lets
     * the drawer pick a cause while the group is only expanded, never selected.
     */
    fun selectErrorReason(bucket: TorrentFilter, key: String?) {
        _ui.update { it.copy(filter = bucket, errorReasonFilter = key) }
    }


    /**
     * Pulls the daemon's per-tracker announce failures so the drawer can group them by wording.
     * Cheap on Transmission (one request for all ids) and bounded on qBittorrent, whose endpoint
     * only accepts a single hash; an oversized set simply yields no map and falls back to
     * [errorReasonKey].
     */
    fun loadTrackerFailureReasons() {
        val state = _ui.value
        if (state.trackerReasonsLoading) return
        if (state.torrents.isEmpty()) return
        // The drawer re-enters composition every time it opens, which would re-run this probe and
        // let a partial second result overwrite a good first one. Once a set has been classified,
        // leave it alone.
        val known = state.torrents.map { it.id }.toSet()
        if (
            state.trackerReasonEntries.isNotEmpty() &&
            state.trackerReasonsIds == known &&
            state.trackerReasonsProfile == state.activeProfile?.id
        ) {
            return
        }
        _ui.update { it.copy(trackerReasonsLoading = true) }
        withAdapter { adapter ->
            // Transmission answers for the whole library in one request, which is what makes the
            // numbers line up with the web UI: it counts every failing tracker, not only those on
            // torrents the daemon flagged as broken. qBittorrent needs a request per torrent, so
            // it only gets the flagged ones.
            val targets =
                if (adapter.canProbeAllTrackers || state.torrents.size <= PROBE_ALL_LIMIT) {
                    state.torrents
                } else {
                    // One request per torrent: only worth it for a small library.
                    state.torrents.filter { it.trackerIssues }
                }
            val probe = runCatching { adapter.trackerFailures(targets.map { it.id }) }
            if (probe.isFailure) {
                // Keep whatever was classified last time: an empty box reads as "nothing wrong".
                _ui.update { it.copy(trackerReasonsLoading = false, trackerReasonsFailed = true) }
                return@withAdapter
            }
            val failures = probe.getOrDefault(emptyList())
            // Each adapter already reduces a torrent to at most one entry: healthy as soon as one
            // of its trackers announced fine, otherwise the wording its dead trackers agree on.
            val entries = failures
                .filter { f -> f.message.isNotBlank() }
                .groupBy { f -> f.torrentId }
                .mapValues { (_, list) ->
                    list.firstOrNull { f -> f.message.contains("HTTP response") } ?: list.first()
                }
                .entries
                .groupBy({ e -> "$KEY_TRACKER_TEXT_PREFIX${e.value.message.trim()}" }, { e -> e.key })
                .mapValues { (_, ids) -> ids.distinct() }
            val total = entries.values.sumOf { ids -> ids.size }
            _ui.update {
                // A partial read (a chunk that came back empty) would silently understate the
                // numbers, so a much smaller result keeps the previous classification instead.
                if (total == 0 || (it.trackerReasonTotal > 0 && total * 2 < it.trackerReasonTotal)) {
                    it.copy(trackerReasonsLoading = false, trackerReasonsFailed = total == 0)
                } else {
                    it.copy(
                        // Same key shape as errorReasonKey(), so counts, labels and filtering agree.
                        trackerReasonEntries = entries,
                        trackerReasonTotal = total,
                        trackerReasonsIds = targets.map { t -> t.id }.toSet(),
                        trackerReasonsProfile = it.activeProfile?.id,
                        trackerReasonsLoading = false,
                        trackerReasonsFailed = false,
                    )
                }
            }
        }
    }

    /** Runs [block] against the active daemon without the refresh cycle that [runAction] performs. */
    private fun withAdapter(block: suspend (org.transdroid.protocol.DaemonAdapter) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                block(container.adapterFor(profile))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Nothing to surface: callers only read data that a successful call produced.
            }
        }
    }

    /**
     * Picking the already-active sort field again reverses its direction instead of no-op'ing.
     * Persisted via [org.transdroid.data.SettingsRepository.setTorrentSort] and fed back into
     * [ui] through the [init] collector above, so it survives a cold app start.
     */
    fun setSort(sort: TorrentSort) {
        val current = _ui.value
        val descending = if (current.sort == sort) !current.sortDescending else sort.defaultDescending
        viewModelScope.launch { container.settingsRepository.setTorrentSort(sort, descending) }
    }

    fun toggleLabelFilter(label: String) {
        _ui.update {
            it.copy(labelFilters = if (label in it.labelFilters) it.labelFilters - label else it.labelFilters + label)
        }
    }

    fun toggleTrackerFilter(tracker: String) {
        _ui.update {
            it.copy(trackerFilters = if (tracker in it.trackerFilters) it.trackerFilters - tracker else it.trackerFilters + tracker)
        }
    }

    fun setNameQuery(query: String) {
        _ui.update { it.copy(nameQuery = query) }
    }

    fun switchProfile(profileId: String) {
        viewModelScope.launch { container.settingsRepository.setActiveServer(profileId) }
    }

    fun setFilePriority(torrentId: String, file: TorrentFile, priority: FilePriority) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val adapter = container.adapterFor(profile)
                adapter.setFilePriority(torrentId, file.index, priority)
                val files = adapter.listFiles(torrentId)
                _ui.update { it.copy(files = it.files + (torrentId to files)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    fun select(torrentId: String?) {
        _ui.update { it.copy(selectedTorrentId = torrentId) }
    }

    fun toggleStartPause(torrent: Torrent) {
        runAction { adapter ->
            if (torrent.status.canStart) adapter.start(torrent.id) else adapter.pause(torrent.id)
        }
    }

    fun remove(torrent: Torrent, deleteData: Boolean) {
        runAction { adapter -> adapter.remove(torrent.id, deleteData) }
    }

    // --- Multi-select -------------------------------------------------------------------------

    fun toggleSelection(id: String) {
        _ui.update { state ->
            state.copy(selection = if (id in state.selection) state.selection - id else state.selection + id)
        }
    }

    fun clearSelection() {
        if (_ui.value.selection.isNotEmpty()) _ui.update { it.copy(selection = emptySet()) }
    }

    /** Selects exactly what the current filter/search shows, so "select all" respects the drawer. */
    fun selectAllVisible() {
        _ui.update { state -> state.copy(selection = state.visibleTorrents.map { it.id }.toSet()) }
    }

    fun pauseSelected() = runForSelection { adapter, id -> adapter.pause(id) }

    fun startSelected() = runForSelection { adapter, id -> adapter.start(id) }

    fun checkDataSelected() = runForSelection { adapter, id -> adapter.checkData(id) }

    fun reannounceSelected() = runForSelection { adapter, id -> adapter.reannounce(id) }

    fun removeSelected(deleteData: Boolean) = runForSelection { adapter, id -> adapter.remove(id, deleteData) }

    /**
     * Applies [action] to every selected torrent concurrently. The client APIs take one id per
     * call, and running a hundred of them in sequence would take minutes, so they go out in
     * parallel; a single failure is reported afterwards instead of aborting the whole batch.
     */
    private fun runForSelection(action: suspend (org.transdroid.protocol.DaemonAdapter, String) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        val state = _ui.value
        // Drop ids the server no longer knows about, e.g. removed from the web UI meanwhile.
        val ids = state.torrents.filter { it.id in state.selection }.map { it.id }
        if (ids.isEmpty()) {
            clearSelection()
            return
        }
        viewModelScope.launch {
            val adapter = container.adapterFor(profile)
            val failures = coroutineScope {
                ids.map { id -> async { runCatching { action(adapter, id) } } }.map { it.await() }
            }.filter { it.isFailure }
            clearSelection()
            refreshNow(showSpinner = false)
            delay(FOLLOW_UP_REFRESH_MILLIS)
            refreshNow(showSpinner = false)
            (failures.firstOrNull()?.exceptionOrNull() as? Exception)?.let { failure ->
                _ui.update { it.copy(error = failure.toUiError(profile.host)) }
            }
        }
    }

    /** [label] null or blank clears it. */
    fun setLabel(torrent: Torrent, label: String?) {
        runAction { adapter -> adapter.setLabel(torrent.id, label.orEmpty()) }
    }

    fun checkData(torrent: Torrent) {
        runAction { adapter -> adapter.checkData(torrent.id) }
    }

    /** Announces to all of this torrent's trackers right away; the API is per torrent, not per tracker. */
    fun reannounce(torrent: Torrent) {
        runAction { adapter -> adapter.reannounce(torrent.id) }
    }

    /** Also remembered as a recent location, same as [add]/[addFile], so it offers itself back as a chip next time. */
    fun setDownloadLocation(torrent: Torrent, location: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).setDownloadLocation(torrent.id, location)
                rememberDownloadLocation(profile.id, location)
                refreshNow(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    /** Flips alternative ("turtle") speed limits; optimistic, since a poll cycle would confirm it anyway. */
    fun toggleAltSpeed() {
        val profile = _ui.value.activeProfile ?: return
        val target = !_ui.value.altSpeedEnabled
        _ui.update { it.copy(altSpeedEnabled = target) }
        viewModelScope.launch {
            try {
                container.adapterFor(profile).setAltSpeedLimitsEnabled(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update {
                    if (it.activeProfile?.id == profile.id) it.copy(altSpeedEnabled = !target, error = e.toUiError(profile.host))
                    else it
                }
            }
        }
    }

    fun loadFiles(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val files = container.adapterFor(profile).listFiles(torrentId)
                _ui.update { it.copy(files = it.files + (torrentId to files)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the files section empty; the list-level error banner covers connectivity
            }
        }
    }

    fun loadTrackers(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val trackers = container.adapterFor(profile).listTrackers(torrentId)
                _ui.update { it.copy(trackers = it.trackers + (torrentId to trackers)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the trackers section empty; the list-level error banner covers connectivity
            }
        }
    }

    fun loadPeers(torrentId: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                val peers = fetchPeers(container.adapterFor(profile), torrentId)
                _ui.update { it.copy(peers = it.peers + (torrentId to peers)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave the peers section empty; the list-level error banner covers connectivity
            }
        }
    }

    /** Adds a torrent by magnet/URL; invokes [onResult] with null on success. */
    fun add(url: String, startPaused: Boolean = false, downloadLocation: String? = null, onResult: (UiError?) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByUrl(url, startPaused, downloadLocation)
                rememberDownloadLocation(profile.id, downloadLocation)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    /** Adds a torrent from the raw contents of a .torrent file. */
    fun addFile(
        fileName: String,
        contents: ByteArray,
        startPaused: Boolean = false,
        downloadLocation: String? = null,
        onResult: (UiError?) -> Unit,
    ) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                container.adapterFor(profile).addByFile(fileName, contents, startPaused, downloadLocation)
                rememberDownloadLocation(profile.id, downloadLocation)
                refreshNow(showSpinner = false)
                onResult(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onResult(e.toUiError(profile.host))
            }
        }
    }

    private suspend fun rememberDownloadLocation(profileId: String, downloadLocation: String?) {
        if (!downloadLocation.isNullOrBlank()) {
            container.settingsRepository.addRecentDownloadLocation(profileId, downloadLocation.trim())
        }
    }

    private fun runAction(action: suspend (org.transdroid.protocol.DaemonAdapter) -> Unit) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch {
            try {
                action(container.adapterFor(profile))
                refreshNow(showSpinner = false)
                // Daemons apply some actions asynchronously (qBittorrent only recalculates a
                // torrent's state on its next status update), so the immediate refresh can still
                // show the old state; a second one soon after beats waiting for the next poll
                delay(FOLLOW_UP_REFRESH_MILLIS)
                refreshNow(showSpinner = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(error = e.toUiError(profile.host)) }
            }
        }
    }

    companion object {
        private const val FOLLOW_UP_REFRESH_MILLIS = 2000L

        /**
         * Largest library a per-torrent client (qBittorrent) is asked about in full. Above this the
         * probe falls back to the torrents its own error flag already covers, which keeps a large
         * library from turning into thousands of requests.
         */
        private const val PROBE_ALL_LIMIT = 500

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { TorrentsViewModel(checkNotNull(this[APPLICATION_KEY]).appContainer) }
        }
    }
}
