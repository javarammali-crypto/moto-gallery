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

    /** The list the full-screen viewer pages through. */
    private val _viewerItems = MutableStateFlow<List<Media>>(emptyList())
    val viewerItems: StateFlow<List<Media>> = _viewerItems.asStateFlow()
    var viewerStartId: Long = -1L
        private set

    private var started = false
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleRefresh()
    }

    fun start() {
        _canManage.value = repo.canManageMedia()
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
            syncViewer(items)
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

    private fun syncViewer(items: List<Media>) {
        if (_viewerItems.value.isEmpty()) return
        val byId = items.associateBy { it.id }
        _viewerItems.value = _viewerItems.value.mapNotNull { byId[it.id] }
    }

    fun openViewer(list: List<Media>, startId: Long) {
        _viewerItems.value = list
        viewerStartId = startId
    }

    fun removeFromViewer(ids: Set<Long>) {
        _viewerItems.value = _viewerItems.value.filterNot { it.id in ids }
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
