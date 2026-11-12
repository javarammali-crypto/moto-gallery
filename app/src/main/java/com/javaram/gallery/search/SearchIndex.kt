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
    SQLiteOpenHelper(context.applicationContext, "search.db", null, 3) {

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
        createFaceTables(db)
    }

    private fun createFaceTables(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS faces(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                media_id INTEGER NOT NULL,
                l REAL, t REAL, r REAL, b REAL,
                area REAL,
                emb BLOB,
                person INTEGER NOT NULL DEFAULT -1
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS faces_media ON faces(media_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS faces_person ON faces(person)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS persons(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                hidden INTEGER NOT NULL DEFAULT 0,
                centroid BLOB,
                n INTEGER NOT NULL DEFAULT 0,
                cover_face INTEGER,
                cover_area REAL NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS face_done(media_id INTEGER PRIMARY KEY, modified INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nomerge(a INTEGER NOT NULL, b INTEGER NOT NULL, PRIMARY KEY(a, b))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createFaceTables(db)
    }

    // ---------------- Faces & people ----------------

    fun faceDoneVersions(): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        readableDatabase.rawQuery("SELECT media_id, modified FROM face_done", null).use { c ->
            while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
        }
        return out
    }

    fun deleteMissingFaces(keep: Set<Long>) {
        val gone = faceDoneVersions().keys.filter { it !in keep }
        if (gone.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            gone.chunked(500).forEach { chunk ->
                val ids = chunk.joinToString(",")
                db.execSQL("DELETE FROM faces WHERE media_id IN ($ids)")
                db.execSQL("DELETE FROM face_done WHERE media_id IN ($ids)")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Saves the faces found in one photo, replacing any earlier result for it. */
    fun putFaces(mediaId: Long, modified: Long, faces: List<StoredFace>): List<Long> {
        val db = writableDatabase
        val ids = ArrayList<Long>()
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM faces WHERE media_id = $mediaId")
            for (f in faces) {
                val v = ContentValues().apply {
                    put("media_id", mediaId)
                    put("l", f.left); put("t", f.top); put("r", f.right); put("b", f.bottom)
                    put("area", f.area)
                    put("emb", Embeddings.toBytes(f.embedding))
                    put("person", f.person)
                }
                ids += db.insert("faces", null, v)
            }
            val done = ContentValues().apply { put("media_id", mediaId); put("modified", modified) }
            db.insertWithOnConflict("face_done", null, done, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return ids
    }

    fun loadPersonCentroids(): MutableList<PersonCentroid> {
        val out = ArrayList<PersonCentroid>()
        readableDatabase.rawQuery("SELECT id, centroid, n, cover_area, name FROM persons", null).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(1) ?: continue
                out += PersonCentroid(c.getLong(0), Embeddings.fromBytes(blob), c.getInt(2), c.getFloat(3), c.getString(4))
            }
        }
        return out
    }

    fun createPerson(centroid: FloatArray): Long {
        val v = ContentValues().apply { put("centroid", Embeddings.toBytes(centroid)); put("n", 0) }
        return writableDatabase.insert("persons", null, v)
    }

    fun savePerson(p: PersonCentroid, coverFace: Long?) {
        val v = ContentValues().apply {
            put("centroid", Embeddings.toBytes(p.centroid))
            put("n", p.count)
            put("cover_area", p.coverArea)
            if (coverFace != null) put("cover_face", coverFace)
        }
        writableDatabase.update("persons", v, "id = ?", arrayOf(p.id.toString()))
    }

    fun persons(): List<Person> {
        val out = ArrayList<Person>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.name, p.hidden, COUNT(DISTINCT f.media_id) AS c,
                      cf.media_id, cf.l, cf.t, cf.r, cf.b
               FROM persons p
               JOIN faces f ON f.person = p.id
               LEFT JOIN faces cf ON cf.id = p.cover_face
               GROUP BY p.id
               ORDER BY (p.name IS NULL), c DESC""",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += Person(
                    id = c.getLong(0),
                    name = c.getString(1),
                    hidden = c.getInt(2) == 1,
                    count = c.getInt(3),
                    coverMediaId = if (c.isNull(4)) null else c.getLong(4),
                    coverBox = if (c.isNull(5)) null else floatArrayOf(c.getFloat(5), c.getFloat(6), c.getFloat(7), c.getFloat(8)),
                )
            }
        }
        return out
    }

    fun mediaForPerson(personId: Long): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT media_id FROM faces WHERE person = ?", arrayOf(personId.toString())).use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    /** media id → names of the named people in it. */
    fun namedPeopleByMedia(): Map<Long, Set<String>> {
        val out = HashMap<Long, MutableSet<String>>()
        readableDatabase.rawQuery(
            "SELECT f.media_id, p.name FROM faces f JOIN persons p ON p.id = f.person WHERE p.name IS NOT NULL AND p.hidden = 0",
            null,
        ).use { c ->
            while (c.moveToNext()) out.getOrPut(c.getLong(0)) { HashSet() }.add(c.getString(1))
        }
        return out
    }

    fun renamePerson(id: Long, name: String?) {
        val v = ContentValues().apply { if (name.isNullOrBlank()) putNull("name") else put("name", name.trim()) }
        writableDatabase.update("persons", v, "id = ?", arrayOf(id.toString()))
    }

    fun setHidden(id: Long, hidden: Boolean) {
        val v = ContentValues().apply { put("hidden", if (hidden) 1 else 0) }
        writableDatabase.update("persons", v, "id = ?", arrayOf(id.toString()))
    }

    /** Moves every face of [from] into [into] and blends their fingerprints. */
    fun mergePersons(from: Long, into: Long) {
        if (from == into) return
        val all = loadPersonCentroids()
        val a = all.firstOrNull { it.id == from } ?: return
        val b = all.firstOrNull { it.id == into } ?: return
        val merged = Embeddings.blend(b.centroid, b.count, a.centroid, a.count)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE faces SET person = $into WHERE person = $from")
            val v = ContentValues().apply {
                put("centroid", Embeddings.toBytes(merged))
                put("n", a.count + b.count)
                if (b.name == null && a.name != null) put("name", a.name)
            }
            db.update("persons", v, "id = ?", arrayOf(into.toString()))
            if (a.coverArea > b.coverArea) {
                db.execSQL("UPDATE persons SET cover_face = (SELECT cover_face FROM persons WHERE id = $from), cover_area = ${a.coverArea} WHERE id = $into")
            }
            db.delete("persons", "id = ?", arrayOf(from.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Up to [limit] of each person's clearest face fingerprints. */
    fun exemplars(limit: Int = 24): Map<Long, List<FloatArray>> {
        val out = HashMap<Long, MutableList<FloatArray>>()
        readableDatabase.rawQuery("SELECT person, emb FROM faces WHERE person >= 0 ORDER BY person, area DESC", null).use { c ->
            while (c.moveToNext()) {
                val list = out.getOrPut(c.getLong(0)) { ArrayList() }
                if (list.size < limit) list += Embeddings.fromBytes(c.getBlob(1))
            }
        }
        return out
    }

    data class FaceRow(val id: Long, val person: Long, val emb: FloatArray, val area: Float)

    /** Every face that may be grouped (not ones the user removed), largest first. */
    fun facesForRegroup(): List<FaceRow> {
        val out = ArrayList<FaceRow>()
        readableDatabase.rawQuery("SELECT id, person, emb, area FROM faces WHERE person != -2 ORDER BY area DESC", null).use { c ->
            while (c.moveToNext()) out += FaceRow(c.getLong(0), c.getLong(1), Embeddings.fromBytes(c.getBlob(2)), c.getFloat(3))
        }
        return out
    }

    data class OldPerson(val id: Long, val name: String?, val hidden: Boolean)

    fun oldPersons(): List<OldPerson> {
        val out = ArrayList<OldPerson>()
        readableDatabase.rawQuery("SELECT id, name, hidden FROM persons", null).use { c ->
            while (c.moveToNext()) out += OldPerson(c.getLong(0), c.getString(1), c.getInt(2) == 1)
        }
        return out
    }

    data class NewPerson(
        val name: String?, val hidden: Boolean, val centroid: FloatArray, val count: Int,
        val coverFace: Long, val coverArea: Float, val faceIds: List<Long>,
    )

    /** Replaces all people with a freshly computed grouping. */
    fun rewritePeople(people: List<NewPerson>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM persons")
            db.execSQL("DELETE FROM nomerge")
            db.execSQL("UPDATE faces SET person = -1 WHERE person >= 0")
            val upd = db.compileStatement("UPDATE faces SET person = ? WHERE id = ?")
            for (p in people) {
                val v = ContentValues().apply {
                    if (p.name != null) put("name", p.name)
                    put("hidden", if (p.hidden) 1 else 0)
                    put("centroid", Embeddings.toBytes(p.centroid))
                    put("n", p.count)
                    put("cover_face", p.coverFace)
                    put("cover_area", p.coverArea)
                }
                val pid = db.insert("persons", null, v)
                for (f in p.faceIds) {
                    upd.bindLong(1, pid); upd.bindLong(2, f); upd.executeUpdateDelete(); upd.clearBindings()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun notSame(a: Long, b: Long) {
        val v = ContentValues().apply { put("a", minOf(a, b)); put("b", maxOf(a, b)) }
        writableDatabase.insertWithOnConflict("nomerge", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun notSamePairs(): Set<Pair<Long, Long>> {
        val out = HashSet<Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT a, b FROM nomerge", null).use { c ->
            while (c.moveToNext()) out += c.getLong(0) to c.getLong(1)
        }
        return out
    }

    /** Recomputes a person's average face from all their faces; removes them if none are left. */
    private fun refreshPerson(db: SQLiteDatabase, personId: Long) {
        val embs = ArrayList<FloatArray>()
        db.rawQuery("SELECT emb FROM faces WHERE person = ?", arrayOf(personId.toString())).use { c ->
            while (c.moveToNext()) embs += Embeddings.fromBytes(c.getBlob(0))
        }
        if (embs.isEmpty()) {
            db.delete("persons", "id = ?", arrayOf(personId.toString()))
            return
        }
        val sum = FloatArray(embs[0].size)
        for (e in embs) for (i in sum.indices) sum[i] += e[i]
        val v = ContentValues().apply {
            put("centroid", Embeddings.toBytes(Embeddings.normalize(sum)))
            put("n", embs.size)
        }
        db.update("persons", v, "id = ?", arrayOf(personId.toString()))
        // If the cover face no longer belongs to this person, pick their clearest face.
        val coverOk = db.rawQuery(
            "SELECT 1 FROM persons p JOIN faces f ON f.id = p.cover_face WHERE p.id = ? AND f.person = p.id",
            arrayOf(personId.toString()),
        ).use { it.moveToFirst() }
        if (!coverOk) {
            db.execSQL(
                """UPDATE persons SET
                     cover_face = (SELECT id FROM faces WHERE person = $personId ORDER BY area DESC LIMIT 1),
                     cover_area = (SELECT area FROM faces WHERE person = $personId ORDER BY area DESC LIMIT 1)
                   WHERE id = $personId"""
            )
        }
    }

    /** Moves this person's faces in the given photos to another person. */
    fun moveFaces(from: Long, mediaIds: Collection<Long>, to: Long) {
        if (mediaIds.isEmpty() || from == to) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE faces SET person = $to WHERE person = $from AND media_id IN (${mediaIds.joinToString(",")})"
            )
            refreshPerson(db, to)
            refreshPerson(db, from)
            db.execSQL("INSERT OR IGNORE INTO nomerge(a, b) VALUES (${minOf(from, to)}, ${maxOf(from, to)})")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Starts a new person from this person's faces in the given photos. Returns the new id. */
    fun moveFacesToNewPerson(from: Long, mediaIds: Collection<Long>, name: String?): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply { if (!name.isNullOrBlank()) put("name", name.trim()); put("n", 0) }
            val id = db.insert("persons", null, v)
            db.execSQL(
                "UPDATE faces SET person = $id WHERE person = $from AND media_id IN (${mediaIds.joinToString(",")})"
            )
            db.execSQL("UPDATE persons SET cover_face = NULL WHERE id = $id")
            refreshPerson(db, id)
            refreshPerson(db, from)
            db.execSQL("INSERT OR IGNORE INTO nomerge(a, b) VALUES (${minOf(from, id)}, ${maxOf(from, id)})")
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    /** Uses this person's face in the given photo as their cover, and keeps it (automatic picks won't replace it). */
    fun setCover(personId: Long, mediaId: Long): Boolean {
        val faceId = readableDatabase.rawQuery(
            "SELECT id FROM faces WHERE person = ? AND media_id = ? ORDER BY area DESC LIMIT 1",
            arrayOf(personId.toString(), mediaId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        writableDatabase.execSQL("UPDATE persons SET cover_face = $faceId, cover_area = 2.0 WHERE id = $personId")
        return true
    }

    /** "Not this person": detach the person's faces in these photos. */
    fun removeFromPerson(personId: Long, mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE faces SET person = -2 WHERE person = $personId AND media_id IN (${mediaIds.joinToString(",")})"
            )
            refreshPerson(db, personId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
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

data class StoredFace(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val area: Float,
    val embedding: FloatArray,
    val person: Long,
)

data class PersonCentroid(
    val id: Long,
    var centroid: FloatArray,
    var count: Int,
    var coverArea: Float,
    val name: String?,
)

data class Person(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val count: Int,
    val coverMediaId: Long?,
    /** Face box (left, top, right, bottom) as fractions of the photo. */
    val coverBox: FloatArray?,
)

object Embeddings {
    fun toBytes(v: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0.0
        for (x in v) s += x * x
        val n = kotlin.math.sqrt(s).toFloat().coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun blend(a: FloatArray, na: Int, b: FloatArray, nb: Int): FloatArray {
        val wa = na.coerceAtLeast(1).toFloat()
        val wb = nb.coerceAtLeast(1).toFloat()
        return normalize(FloatArray(a.size) { a[it] * wa + b[it] * wb })
    }
}
