package com.javaram.gallery

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.javaram.gallery.data.Album
import com.javaram.gallery.data.AlbumKeys
import com.javaram.gallery.data.ExifDetails
import com.javaram.gallery.data.Media
import com.javaram.gallery.data.MediaRepository
import com.javaram.gallery.search.FaceIndexer
import com.javaram.gallery.search.Person
import com.javaram.gallery.search.IndexEntry
import com.javaram.gallery.search.IndexProgress
import com.javaram.gallery.search.Indexer
import com.javaram.gallery.search.LatestRequestRunner
import com.javaram.gallery.search.SearchIndex
import com.javaram.gallery.update.UpdateInfo
import com.javaram.gallery.update.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

enum class MediaFilter(val label: String) {
    All("All items"), Camera("Camera only"), Photos("Photos only"), Videos("Videos only"), Favorites("Favourites")
}

/** Photos taken with the phone's camera (not screenshots, downloads or app folders). */
fun Media.isCameraShot(): Boolean =
    relativePath.startsWith("DCIM/", ignoreCase = true) && !relativePath.contains("Screenshot", ignoreCase = true)

private val musicDirs = listOf("Music/", "Audiobooks/", "Podcasts/", "Ringtones/", "Notifications/", "Alarms/", "Recordings/")
private val coverArt = Regex("""^(folder|cover|front|albumart.*|album_art.*|thumb)\.(jpe?g|png|webp)$""", RegexOption.IGNORE_CASE)

/** Album covers that music players keep next to songs — not real photos. */
fun Media.isMusicArt(): Boolean =
    !isVideo && (musicDirs.any { relativePath.startsWith(it, ignoreCase = true) } || coverArt.matches(name))

enum class AlbumSort(val label: String) {
    Recent("Most recent"), Name("Name"), Count("Number of items")
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    /** Everything MediaStore has, before hidden folders are taken out. */
    private val _raw = MutableStateFlow<List<Media>>(emptyList())
    private val _all = MutableStateFlow<List<Media>>(emptyList())
    val all: StateFlow<List<Media>> = _all.asStateFlow()

    // --- Hidden folders ---
    private val _hiddenBuckets = MutableStateFlow(prefs.getStringSet("hiddenBuckets", emptySet())!!.toSet())
    val hiddenBuckets: StateFlow<Set<String>> = _hiddenBuckets.asStateFlow()
    private val _hideMusicArt = MutableStateFlow(prefs.getBoolean("hideMusicArt", true))
    val hideMusicArt: StateFlow<Boolean> = _hideMusicArt.asStateFlow()

    private fun visible(items: List<Media>, hidden: Set<String>, music: Boolean): List<Media> =
        items.filter { it.bucketId.toString() !in hidden && !(music && it.isMusicArt()) }

    private fun applyHidden() {
        _all.value = visible(_raw.value, _hiddenBuckets.value, _hideMusicArt.value)
    }

    /** Folders the user hid, so they can be shown again. */
    val hiddenAlbums: StateFlow<List<Album>> =
        combine(_raw, _hiddenBuckets) { items, hidden ->
            items.filter { it.bucketId.toString() in hidden }.groupBy { it.bucketId }.map { (id, media) ->
                Album(id.toString(), media.first().bucketName, media.first(), media.size, media.first().dateTaken)
            }.sortedBy { it.name.lowercase() }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setAlbumHidden(key: String, hidden: Boolean) {
        val next = _hiddenBuckets.value.toMutableSet().apply { if (hidden) add(key) else remove(key) }
        _hiddenBuckets.value = next
        prefs.edit().putStringSet("hiddenBuckets", next).apply()
        applyHidden()
    }

    fun setHideMusicArt(hide: Boolean) {
        _hideMusicArt.value = hide
        prefs.edit().putBoolean("hideMusicArt", hide).apply()
        applyHidden()
    }

    // --- Albums: create, copy, move ---
    private val _albumBusy = MutableStateFlow<String?>(null)
    /** Progress text while copying or moving items into an album. */
    val albumBusy: StateFlow<String?> = _albumBusy.asStateFlow()

    fun writeRequest(items: Collection<Media>): PendingIntent =
        com.javaram.gallery.data.AlbumOps.writeRequest(getApplication(), items)

    /** Copies (or, once write access is granted, moves) items into the folder at [path]. */
    fun addToAlbum(items: List<Media>, path: String, move: Boolean, done: (Int) -> Unit) {
        viewModelScope.launch {
            val verb = if (move) "Moving" else "Copying"
            val n = if (move) {
                com.javaram.gallery.data.AlbumOps.move(getApplication(), items, path) { i ->
                    _albumBusy.value = "$verb… ${i + 1} of ${items.size}"
                }
            } else {
                com.javaram.gallery.data.AlbumOps.copy(getApplication(), items, path) { i ->
                    _albumBusy.value = "$verb… ${i + 1} of ${items.size}"
                }
            }
            _albumBusy.value = null
            scheduleRefresh(0)
            done(n)
        }
    }

    /** Folder path of an existing album (null for albums Android won't let apps add to). */
    fun albumPath(key: String): String? =
        _raw.value.firstOrNull { it.bucketId.toString() == key }?.relativePath
            ?.takeIf { com.javaram.gallery.data.AlbumOps.canHold(it) }

    private val _trash = MutableStateFlow<List<Media>>(emptyList())
    val trash: StateFlow<List<Media>> = _trash.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _filter = MutableStateFlow(
        runCatching { MediaFilter.valueOf(prefs.getString("filter", null) ?: "") }.getOrDefault(MediaFilter.All)
    )
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    fun setFilter(f: MediaFilter) {
        _filter.value = f
        prefs.edit().putString("filter", f.name).apply()
    }

    private val _columns = MutableStateFlow(prefs.getInt("columns", 4))
    val columns: StateFlow<Int> = _columns.asStateFlow()

    private val _albumSort = MutableStateFlow(
        runCatching { AlbumSort.valueOf(prefs.getString("albumSort", null) ?: "") }
            .getOrDefault(AlbumSort.Recent)
    )
    val albumSort: StateFlow<AlbumSort> = _albumSort.asStateFlow()

    private val _pinned = MutableStateFlow(prefs.getStringSet("pinned", emptySet())!!.toSet())

    private val _canManage = MutableStateFlow(repo.canManageMedia())
    val canManageMedia: StateFlow<Boolean> = _canManage.asStateFlow()

    private val _bannerDismissed = MutableStateFlow(prefs.getBoolean("manageBannerDismissed", false))
    val bannerDismissed: StateFlow<Boolean> = _bannerDismissed.asStateFlow()

    val albums: StateFlow<List<Album>> =
        combine(_all, _albumSort, _pinned) { items, sort, pins -> buildAlbums(items, sort, pins) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var started = false
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleRefresh()
    }

    // --- In-app updates ---
    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var checkedThisSession = false

    fun checkForUpdates(manual: Boolean) {
        if (!manual && checkedThisSession) return
        checkedThisSession = true
        viewModelScope.launch {
            if (manual) _update.value = UpdateState.Checking
            val info = runCatching { Updater.latest() }.getOrNull()
            val installed = Updater.installedVersionCode(getApplication())
            _update.value = when {
                info != null && info.versionCode > installed -> UpdateState.Available(info)
                manual && info == null -> UpdateState.Error("Couldn't reach GitHub. Check your connection.")
                manual -> UpdateState.UpToDate
                else -> UpdateState.Idle
            }
        }
    }

    fun startUpdate(info: UpdateInfo) {
        val app = getApplication<Application>()
        if (!Updater.canInstall(app)) {
            Updater.openInstallPermission(app)
            _update.value = UpdateState.NeedsPermission(info)
            return
        }
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(info, -1f)
            runCatching {
                val file = Updater.download(app, info) { p -> _update.value = UpdateState.Downloading(info, p) }
                _update.value = UpdateState.Installing(info)
                Updater.install(app, file)
            }.onFailure { _update.value = UpdateState.Error(it.message ?: "Update failed") }
        }
    }

    fun dismissUpdate() { _update.value = UpdateState.Idle }

    fun start() {
        _canManage.value = repo.canManageMedia()
        checkForUpdates(manual = false)
        (_update.value as? UpdateState.NeedsPermission)?.let { pending ->
            if (Updater.canInstall(getApplication())) startUpdate(pending.info)
        }
        if (started) {
            scheduleRefresh(0)
            return
        }
        started = true
        getApplication<Application>().contentResolver.registerContentObserver(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
        )
        scheduleRefresh(0)
    }

    fun scheduleRefresh(delayMs: Long = 350) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(delayMs)
            val items = withContext(Dispatchers.IO) { repo.load(trashed = false) }
            _raw.value = items
            applyHidden()
            _loaded.value = true
            startIndexing(_all.value)
            if (_trash.value.isNotEmpty() || trashLoaded) loadTrash()
        }
    }

    private var trashLoaded = false
    fun loadTrash() {
        trashLoaded = true
        viewModelScope.launch {
            _trash.value = withContext(Dispatchers.IO) { repo.load(trashed = true) }
        }
    }

    // --- Search index ---
    val indexProgress: StateFlow<IndexProgress> = Indexer.progress
    private val _index = MutableStateFlow<Map<Long, IndexEntry>>(emptyMap())
    val index: StateFlow<Map<Long, IndexEntry>> = _index.asStateFlow()
    private var indexJob: Job? = null
    private var indexWatch: Job? = null

    private fun startIndexing(items: List<Media>) {
        if (indexWatch == null) {
            indexWatch = viewModelScope.launch {
                Indexer.generation.collect {
                    _index.value = withContext(Dispatchers.IO) { SearchIndex.get(getApplication()).all() }
                }
            }
        }
        if (peopleWatch == null) {
            peopleWatch = viewModelScope.launch { FaceIndexer.generation.collect { reloadPeople() } }
        }
        // Faces have their own job, so switching them off or on never waits for the other indexers.
        startFaceIndexing(items)
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            // Search analysis and quality run side by side.
            val labels = launch { Indexer.run(getApplication(), items) }
            val quality = launch { com.javaram.gallery.search.QualityIndexer.run(getApplication(), items) }
            labels.join(); quality.join()
        }
    }

    // --- People ---
    val faceProgress: StateFlow<IndexProgress> = FaceIndexer.progress

    // --- Face recognition switch ---
    // Off: no face scanning, no new faces, no regrouping. Saved data is kept. On by default.
    // Null until the saved choice has been read: the UI must not show a position for it. Unavailable when the read failed.
    private val _faceRecognition = MutableStateFlow<Boolean?>(null)
    val faceRecognitionEnabled: StateFlow<Boolean?> = _faceRecognition.asStateFlow()
    private val _faceUnavailable = MutableStateFlow(false)
    /** True when the saved choice couldn't be read. The switch stays unavailable until Gallery is reopened. */
    val faceRecognitionUnavailable: StateFlow<Boolean> = _faceUnavailable.asStateFlow()
    // Newest library snapshot only; FaceIndexer serialises scans and drops any queued before a switch.
    private val faceScans = LatestRequestRunner<List<Media>>(viewModelScope) { items ->
        FaceIndexer.run(getApplication(), items)
    }
    // Changes are applied one at a time, in request order, off the main thread: the gate can wait on a database write.
    private val faceSwitch = OrderedSwitch(
        scope = viewModelScope,
        dispatcher = Dispatchers.IO,
        initial = { FaceIndexer.isEnabled(getApplication()) },
        apply = { FaceIndexer.setEnabled(getApplication(), it) },
        onInitial = { read ->
            read.onSuccess { enabled ->
                _faceRecognition.value = enabled
                _faceUnavailable.value = false
                // A library that loaded before the read finished starts its scan here.
                // No value was known before this read, so an on read counts as a change to on.
                if (faceScanTrigger(known = null, enabled = enabled, changed = false)) startFaceIndexing(_all.value)
            }.onFailure {
                _faceUnavailable.value = true
            }
        },
        onApplied = { enabled, changed ->
            val wasKnown = _faceRecognition.value != null
            _faceRecognition.value = enabled
            _faceUnavailable.value = false
            // Turning on starts a scan; repeating the current state changes nothing.
            if (faceScanTrigger(known = wasKnown, enabled = enabled, changed = changed)) startFaceIndexing(_all.value)
        },
    )

    /**
     * Queues the change and returns at once. Switching off doesn't cancel a scan: it stops at its next
     * checkpoint, and an ML Kit call already running finishes first. The switch in the UI moves only when
     * the change has been applied.
     */
    fun setFaceRecognitionEnabled(enabled: Boolean) {
        faceSwitch.request(enabled)
    }

    private fun startFaceIndexing(items: List<Media>) {
        // An empty list would make FaceIndexer forget every face, so wait until the library has loaded.
        // Nothing starts until the saved choice is known to be on.
        if (_faceRecognition.value != true || items.isEmpty()) return
        faceScans.submit(items)
    }

    private var peopleWatch: Job? = null
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    private val _peopleByMedia = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    // Face-derived content is only exposed while recognition is on. Turning it off hides every
    // People surface (the Albums row, person search and person memories) without deleting the
    // persisted data: the raw flows above keep it, so it all reappears when recognition is back on.
    // Null (still loading) or an unavailable switch also hides faces, never shows them by default.
    val people: StateFlow<List<Person>> =
        combine(_faceRecognition, _people) { enabled, list ->
            if (facesVisible(enabled)) list else emptyList()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val peopleByMedia: StateFlow<Map<Long, Set<String>>> =
        combine(_faceRecognition, _peopleByMedia) { enabled, map ->
            if (facesVisible(enabled)) map else emptyMap()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val _peopleVersion = MutableStateFlow(0)
    val peopleVersion: StateFlow<Int> = _peopleVersion.asStateFlow()

    fun reloadPeople() {
        viewModelScope.launch {
            val db = SearchIndex.get(getApplication())
            val (list, byMedia) = withContext(Dispatchers.IO) { db.persons() to db.namedPeopleByMedia() }
            _people.value = list
            _peopleByMedia.value = byMedia
            _peopleVersion.value++
        }
    }

    suspend fun mediaForPerson(id: Long): Set<Long> =
        withContext(Dispatchers.IO) { SearchIndex.get(getApplication()).mediaForPerson(id) }

    /** Returns the other person already using this name, if any (naming them the same merges them). */
    fun personWithName(name: String, except: Long): Person? =
        _people.value.firstOrNull { it.id != except && it.name?.equals(name.trim(), ignoreCase = true) == true }

    private fun peopleOp(block: (SearchIndex) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block(SearchIndex.get(getApplication())) }
            reloadPeople()
        }
    }

    fun renamePerson(id: Long, name: String?) = peopleOp { it.renamePerson(id, name) }

    // "Same person?" suggestions
    private val _suggestions = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val suggestions: StateFlow<List<Pair<Long, Long>>> = _suggestions.asStateFlow()

    fun loadSuggestions() {
        viewModelScope.launch {
            val visible = _people.value
                .filter { (it.name != null || it.count >= 3) && !it.hidden }
                .mapTo(HashSet()) { it.id }
            _suggestions.value = FaceIndexer.suggestions(getApplication(), visible)
        }
    }

    fun answerSuggestion(a: Long, b: Long, same: Boolean) {
        _suggestions.value = _suggestions.value.filterNot { it == (a to b) }
        if (same) {
            val pa = _people.value.firstOrNull { it.id == a }
            val pb = _people.value.firstOrNull { it.id == b }
            // Keep the named (or larger) person.
            val keepA = when {
                pa?.name != null && pb?.name == null -> true
                pb?.name != null && pa?.name == null -> false
                else -> (pa?.count ?: 0) >= (pb?.count ?: 0)
            }
            val into = if (keepA) a else b
            val from = if (keepA) b else a
            _suggestions.value = _suggestions.value.filterNot { it.first == from || it.second == from }
            peopleOp { it.mergePersons(from = from, into = into) }
        } else {
            peopleOp { it.notSame(a, b) }
        }
    }

    /** Names a person; if someone else already has that name, folds them into this person. */
    fun nameAndMerge(id: Long, name: String, mergeFrom: Long?) = peopleOp {
        if (mergeFrom != null) it.mergePersons(from = mergeFrom, into = id)
        it.renamePerson(id, name)
    }
    fun mergePersons(from: Long, into: Long) = peopleOp { it.mergePersons(from, into) }
    fun setPersonHidden(id: Long, hidden: Boolean) = peopleOp { it.setHidden(id, hidden) }
    fun removeFromPerson(id: Long, mediaIds: Collection<Long>) = peopleOp { it.removeFromPerson(id, mediaIds) }
    fun moveToPerson(from: Long, mediaIds: Collection<Long>, to: Long) =
        peopleOp { it.moveFaces(from, mediaIds.toList(), to) }
    fun moveToNewPerson(from: Long, mediaIds: Collection<Long>, name: String?) =
        peopleOp { it.moveFacesToNewPerson(from, mediaIds.toList(), name) }
    fun setPersonCover(personId: Long, mediaId: Long) = peopleOp { it.setCover(personId, mediaId) }

    fun reloadIndex() {
        viewModelScope.launch { _index.value = withContext(Dispatchers.IO) { SearchIndex.get(getApplication()).all() } }
    }

    private val _recent = MutableStateFlow(
        prefs.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() }
    )
    val recentSearches: StateFlow<List<String>> = _recent.asStateFlow()

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.length < 2) return
        val next = (listOf(t) + _recent.value.filterNot { it.equals(t, true) }).take(8)
        _recent.value = next
        prefs.edit().putString("recentSearches", next.joinToString("\n")).apply()
    }

    fun clearRecentSearches() {
        _recent.value = emptyList()
        prefs.edit().remove("recentSearches").apply()
    }

    // --- Memories & slideshow ---
    val memories: StateFlow<List<com.javaram.gallery.search.Memory>> =
        combine(_all, _index, peopleByMedia) { a, i, p -> Triple(a, i, p) }
            .mapLatestDefault { (a, i, p) -> com.javaram.gallery.search.Memories.build(a, i, p) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _slideshow = MutableStateFlow<Pair<String, List<Media>>?>(null)
    val slideshow: StateFlow<Pair<String, List<Media>>?> = _slideshow.asStateFlow()
    fun playSlideshow(title: String, items: List<Media>) { _slideshow.value = title to items.filter { !it.isVideo } }

    // --- Cleanup ---
    val qualityProgress: StateFlow<IndexProgress> = com.javaram.gallery.search.QualityIndexer.progress
    private val _cleanup = MutableStateFlow(com.javaram.gallery.search.CleanupReport.Empty)
    val cleanup: StateFlow<com.javaram.gallery.search.CleanupReport> = _cleanup.asStateFlow()
    private var cleanupWatch: Job? = null

    fun watchCleanup() {
        if (cleanupWatch != null) return
        cleanupWatch = viewModelScope.launch {
            combine(_all, com.javaram.gallery.search.QualityIndexer.generation) { items, _ -> items }.collect { items ->
                val q = withContext(Dispatchers.IO) { SearchIndex.get(getApplication()).quality() }
                _cleanup.value = withContext(Dispatchers.Default) { com.javaram.gallery.search.CleanupEngine.analyse(items, q) }
            }
        }
    }

    // --- Private safe ---
    private val _safe = MutableStateFlow<List<com.javaram.gallery.vault.VaultItem>>(emptyList())
    val safe: StateFlow<List<com.javaram.gallery.vault.VaultItem>> = _safe.asStateFlow()
    private val _safeUnlocked = MutableStateFlow(false)
    val safeUnlocked: StateFlow<Boolean> = _safeUnlocked.asStateFlow()
    private val _safeBusy = MutableStateFlow<String?>(null)
    /** Progress text while moving items in or out of the safe. */
    val safeBusy: StateFlow<String?> = _safeBusy.asStateFlow()

    fun unlockSafe() { _safeUnlocked.value = true; reloadSafe() }
    fun lockSafe() {
        _safeUnlocked.value = false
        com.javaram.gallery.vault.Vault.clearPlayback(getApplication())
    }

    fun reloadSafe() {
        viewModelScope.launch { _safe.value = com.javaram.gallery.vault.Vault.list(getApplication()) }
    }

    /** Encrypts items into the safe; returns ids of the safe copies (originals still need deleting). */
    suspend fun addToSafe(items: List<Media>): List<Long> {
        val ids = ArrayList<Long>()
        items.forEachIndexed { i, m ->
            _safeBusy.value = "Moving to Private safe… ${i + 1} of ${items.size}"
            runCatching { com.javaram.gallery.vault.Vault.add(getApplication(), m) }.onSuccess { ids += it }
        }
        _safeBusy.value = null
        reloadSafe()
        return ids
    }

    fun undoAddToSafe(ids: List<Long>) {
        viewModelScope.launch { com.javaram.gallery.vault.Vault.remove(getApplication(), ids); reloadSafe() }
    }

    fun restoreFromSafe(items: List<com.javaram.gallery.vault.VaultItem>, done: (Int) -> Unit) {
        viewModelScope.launch {
            var ok = 0
            items.forEachIndexed { i, it ->
                _safeBusy.value = "Restoring… ${i + 1} of ${items.size}"
                if (com.javaram.gallery.vault.Vault.restore(getApplication(), it)) ok++
            }
            _safeBusy.value = null
            reloadSafe()
            scheduleRefresh(0)
            done(ok)
        }
    }

    fun deleteFromSafe(items: List<com.javaram.gallery.vault.VaultItem>) {
        viewModelScope.launch {
            com.javaram.gallery.vault.Vault.remove(getApplication(), items.map { it.id })
            reloadSafe()
        }
    }

    fun setColumns(value: Int) {
        val v = value.coerceIn(2, 7)
        _columns.value = v
        prefs.edit().putInt("columns", v).apply()
    }

    fun setAlbumSort(sort: AlbumSort) {
        _albumSort.value = sort
        prefs.edit().putString("albumSort", sort.name).apply()
    }

    fun togglePin(key: String) {
        val next = _pinned.value.toMutableSet().apply { if (!add(key)) remove(key) }
        _pinned.value = next
        prefs.edit().putStringSet("pinned", next).apply()
    }

    fun dismissManageBanner() {
        _bannerDismissed.value = true
        prefs.edit().putBoolean("manageBannerDismissed", true).apply()
    }

    fun refreshManagePermission() {
        _canManage.value = repo.canManageMedia()
    }

    // --- Requests that need user consent (handled with an IntentSender launcher) ---
    fun trashRequest(items: Collection<Media>): PendingIntent = repo.trashRequest(items, true)
    fun restoreRequest(items: Collection<Media>): PendingIntent = repo.trashRequest(items, false)
    fun deleteForeverRequest(items: Collection<Media>): PendingIntent = repo.deleteRequest(items)
    fun favoriteRequest(items: Collection<Media>, favorite: Boolean): PendingIntent =
        repo.favoriteRequest(items, favorite)

    suspend fun exif(media: Media): ExifDetails? = withContext(Dispatchers.IO) { repo.readExif(media) }

    fun itemsFor(key: String, items: List<Media>): List<Media> = when (key) {
        AlbumKeys.ALL -> items
        AlbumKeys.FAVORITES -> items.filter { it.isFavorite }
        AlbumKeys.VIDEOS -> items.filter { it.isVideo }
        else -> key.toLongOrNull()?.let { id -> items.filter { it.bucketId == id } } ?: emptyList()
    }

    fun titleFor(key: String): String = when (key) {
        AlbumKeys.ALL -> "All items"
        AlbumKeys.FAVORITES -> "Favourites"
        AlbumKeys.VIDEOS -> "Videos"
        else -> albums.value.firstOrNull { it.key == key }?.name
            ?: _all.value.firstOrNull { it.bucketId.toString() == key }?.bucketName
            ?: "Album"
    }

    private fun buildAlbums(items: List<Media>, sort: AlbumSort, pins: Set<String>): List<Album> {
        val groups = items.groupBy { it.bucketId }
        val list = groups.map { (id, media) ->
            Album(
                key = id.toString(),
                name = media.first().bucketName,
                cover = media.first(),
                count = media.size,
                latest = media.first().dateTaken,
                pinned = id.toString() in pins,
            )
        }
        val sorted = when (sort) {
            AlbumSort.Recent -> list.sortedByDescending { it.latest }
            AlbumSort.Name -> list.sortedBy { it.name.lowercase() }
            AlbumSort.Count -> list.sortedByDescending { it.count }
        }
        // Camera always leads, then pinned albums, then the rest.
        return sorted.sortedWith(
            compareByDescending<Album> { isCamera(it) }.thenByDescending { it.pinned }
        )
    }

    private fun isCamera(album: Album): Boolean =
        album.cover?.relativePath?.startsWith("DCIM/Camera", ignoreCase = true) == true

    override fun onCleared() {
        if (started) getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        super.onCleared()
    }
}

/**
 * Applies switch changes one at a time, in the order they were requested, on [dispatcher]. [request] only
 * queues, so it never blocks the caller. [apply] may block. [onApplied] gets the new state and whether it
 * changed. A change that fails to apply leaves the last applied state in place.
 */
internal class OrderedSwitch(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    initial: () -> Boolean,
    private val apply: (Boolean) -> Boolean,
    private val onInitial: (Result<Boolean>) -> Unit,
    private val onApplied: (enabled: Boolean, changed: Boolean) -> Unit,
) {
    private val requests = Channel<Boolean>(Channel.UNLIMITED)

    init {
        scope.launch(dispatcher) {
            // The saved choice is read before any queued change, so a change never lands ahead of its read.
            val read = try {
                Result.success(initial())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            try {
                onInitial(read)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Publishing the read failed: the state stays unknown.
            }
            for (value in requests) {
                try {
                    onApplied(value, apply(value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Keep going: later requests still apply, and the last applied state stays published.
                }
            }
        }
    }

    fun request(value: Boolean) {
        requests.trySend(value)
    }
}

/**
 * Whether a face scan should start after the switch was read or changed. Only a switch that is on and
 * just became on (or was never read before) starts one; a read that failed or came back off never does.
 */
internal fun faceScanTrigger(known: Boolean?, enabled: Boolean, changed: Boolean): Boolean =
    enabled && (changed || known == null)

/**
 * Whether face-derived content may be shown. Only an explicitly verified "on" shows faces:
 * off, still loading (null) and unavailable all hide them.
 */
internal fun facesVisible(enabled: Boolean?): Boolean = enabled == true

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun <T, R> kotlinx.coroutines.flow.Flow<T>.mapLatestDefault(block: suspend (T) -> R): kotlinx.coroutines.flow.Flow<R> =
    this.mapLatest { withContext(Dispatchers.Default) { block(it) } }
