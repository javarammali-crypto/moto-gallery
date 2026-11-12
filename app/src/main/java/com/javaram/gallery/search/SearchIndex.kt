package com.javaram.gallery.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class GeoPlace(
    val area: String?,
    val city: String?,
    val state: String?,
    val country: String?,
)

data class IndexEntry(
    val id: Long,
    val modified: Long,
    val labels: List<String>,
    val text: String,
    val lat: Double?,
    val lng: Double?,
    val place: GeoPlace?,
)

/** Small SQLite store holding what the indexer learned about each photo. Everything stays on the phone. */
class SearchIndex private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "search.db", null, 1) {

    companion object {
        @Volatile private var instance: SearchIndex? = null
        fun get(context: Context): SearchIndex =
            instance ?: synchronized(this) { instance ?: SearchIndex(context).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE idx(
                id INTEGER PRIMARY KEY,
                modified INTEGER NOT NULL,
                labels TEXT,
                txt TEXT,
                lat REAL,
                lng REAL,
                area TEXT,
                city TEXT,
                state TEXT,
                country TEXT
            )"""
        )
        db.execSQL("CREATE TABLE geo(k TEXT PRIMARY KEY, area TEXT, city TEXT, state TEXT, country TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS idx")
        db.execSQL("DROP TABLE IF EXISTS geo")
        onCreate(db)
    }

    fun versions(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT id, modified FROM idx", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
        }
        return out
    }

    fun put(e: IndexEntry) {
        val v = ContentValues().apply {
            put("id", e.id)
            put("modified", e.modified)
            put("labels", e.labels.joinToString("|"))
            put("txt", e.text)
            if (e.lat != null) put("lat", e.lat) else putNull("lat")
            if (e.lng != null) put("lng", e.lng) else putNull("lng")
            put("area", e.place?.area)
            put("city", e.place?.city)
            put("state", e.place?.state)
            put("country", e.place?.country)
        }
        writableDatabase.insertWithOnConflict("idx", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteMissing(keep: Set<Long>) {
        val gone = versions().keys.filter { it !in keep }
        if (gone.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            gone.chunked(500).forEach { chunk ->
                db.execSQL("DELETE FROM idx WHERE id IN (${chunk.joinToString(",")})")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun all(): Map<Long, IndexEntry> {
        val out = HashMap<Long, IndexEntry>()
        readableDatabase.rawQuery(
            "SELECT id, modified, labels, txt, lat, lng, area, city, state, country FROM idx", null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val labels = c.getString(2)?.split('|')?.filter { it.isNotBlank() } ?: emptyList()
                val hasPlace = !c.isNull(7) || !c.isNull(6) || !c.isNull(9)
                out[id] = IndexEntry(
                    id = id,
                    modified = c.getLong(1),
                    labels = labels,
                    text = c.getString(3) ?: "",
                    lat = if (c.isNull(4)) null else c.getDouble(4),
                    lng = if (c.isNull(5)) null else c.getDouble(5),
                    place = if (hasPlace) GeoPlace(c.getString(6), c.getString(7), c.getString(8), c.getString(9)) else null,
                )
            }
        }
        return out
    }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM idx", null).use { c ->
        if (c.moveToFirst()) c.getInt(0) else 0
    }

    fun geo(key: String): GeoPlace? =
        readableDatabase.rawQuery("SELECT area, city, state, country FROM geo WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) GeoPlace(c.getString(0), c.getString(1), c.getString(2), c.getString(3)) else null
        }

    fun putGeo(key: String, p: GeoPlace) {
        val v = ContentValues().apply {
            put("k", key); put("area", p.area); put("city", p.city); put("state", p.state); put("country", p.country)
        }
        writableDatabase.insertWithOnConflict("geo", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
