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
import com.javaram.gallery.search.SearchIndex
import com.javaram.gallery.update.UpdateInfo
import com.javaram.gallery.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    All("All items"), Photos("Photos only"), Videos("Videos only"), Favorites("Favourites")
}

enum class AlbumSort(val label: String) {
    Recent("Most recent"), Name("Name"), Count("Number of items")
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val prefs = app.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    private val _all = MutableStateFlow<List<Media>>(emptyList())
    val all: StateFlow<List<Media>> = _all.asStateFlow()

    private val _trash = MutableStateFlow<List<Media>>(emptyList())
    val trash: StateFlow<List<Media>> = _trash.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    val filter = MutableStateFlow(MediaFilter.All)

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
            _update.value = UpdateState.Downloading(info, 0f)
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
            _all.value = items
            _loaded.value = true
            startIndexing(items)
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
        if (indexJob?.isActive == true) return
        indexJob = viewModelScope.launch {
            Indexer.run(getApplication(), items)
            FaceIndexer.run(getApplication(), items)
        }
    }

    // --- People ---
    val faceProgress: StateFlow<IndexProgress> = FaceIndexer.progress
    private var peopleWatch: Job? = null
    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people.asStateFlow()
    private val _peopleByMedia = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val peopleByMedia: StateFlow<Map<Long, Set<String>>> = _peopleByMedia.asStateFlow()
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

    /** Names a person; if someone else already has that name, folds them into this person. */
    fun nameAndMerge(id: Long, name: String, mergeFrom: Long?) = peopleOp {
        if (mergeFrom != null) it.mergePersons(from = mergeFrom, into = id)
        it.renamePerson(id, name)
    }
    fun mergePersons(from: Long, into: Long) = peopleOp { it.mergePersons(from, into) }
    fun setPersonHidden(id: Long, hidden: Boolean) = peopleOp { it.setHidden(id, hidden) }
    fun removeFromPerson(id: Long, mediaIds: Collection<Long>) = peopleOp { it.removeFromPerson(id, mediaIds) }

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
