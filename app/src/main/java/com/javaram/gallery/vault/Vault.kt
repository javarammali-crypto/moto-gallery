package com.javaram.gallery.vault

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse
import com.javaram.gallery.data.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import kotlin.math.max
import kotlin.math.roundToInt

data class VaultItem(
    val id: Long,
    val name: String,
    val mime: String,
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val relativePath: String,
    val file: String,
    val thumb: String,
) {
    val isVideo: Boolean get() = mime.startsWith("video")
}

/** Thumbnail / full image of a safe item, decrypted on the fly for Coil. */
data class VaultThumb(val path: String)
data class VaultImage(val path: String)

/**
 * The Private safe: photos are encrypted with a key that lives in the phone's secure hardware
 * (Android Keystore) and stored inside Gallery's private folder, out of reach of other apps.
 */
object Vault {
    private const val KEY_ALIAS = "gallery_private_safe"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CTR)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun dir(context: Context) = File(context.filesDir, "safe").apply { mkdirs() }

    private fun encryptTo(input: InputStream, out: File) {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        out.outputStream().use { fos ->
            fos.write(cipher.iv)
            CipherOutputStream(fos, cipher).use { cos -> input.copyTo(cos, 256 * 1024) }
        }
    }

    fun decrypt(file: File): InputStream {
        val fis = file.inputStream()
        val iv = ByteArray(16)
        var read = 0
        while (read < 16) {
            val r = fis.read(iv, read, 16 - read)
            if (r < 0) error("Corrupt safe file")
            read += r
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), IvParameterSpec(iv))
        }
        return CipherInputStream(fis, cipher)
    }

    fun decryptBytes(file: File): ByteArray = decrypt(file).use { it.readBytes() }

    // ---------------- Index ----------------

    private class Db(context: Context) : SQLiteOpenHelper(context, "safe.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE items(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, mime TEXT, date_taken INTEGER,
                    size INTEGER, width INTEGER, height INTEGER, duration INTEGER, rel_path TEXT,
                    file TEXT, thumb TEXT, added INTEGER)"""
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile private var db: Db? = null
    private fun db(context: Context): Db = db ?: synchronized(this) { db ?: Db(context.applicationContext).also { db = it } }

    suspend fun list(context: Context): List<VaultItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<VaultItem>()
        db(context).readableDatabase.rawQuery(
            "SELECT id, name, mime, date_taken, size, width, height, duration, rel_path, file, thumb FROM items ORDER BY date_taken DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out += VaultItem(
                    c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3), c.getLong(4),
                    c.getInt(5), c.getInt(6), c.getLong(7), c.getString(8) ?: "", c.getString(9), c.getString(10),
                )
            }
        }
        out
    }

    /** Encrypts a photo/video into the safe. The original is NOT deleted here. */
    suspend fun add(context: Context, m: Media): Long = withContext(Dispatchers.IO) {
        val base = UUID.randomUUID().toString()
        val dataFile = File(dir(context), "$base.bin")
        val thumbFile = File(dir(context), "$base.thm")
        try {
            context.contentResolver.openInputStream(m.uri)!!.use { encryptTo(it, dataFile) }
            val thumb = runCatching { context.contentResolver.loadThumbnail(m.uri, Size(512, 512), null) }.getOrNull()
            if (thumb != null) {
                val bytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                encryptTo(bytes.inputStream(), thumbFile)
            }
            val v = ContentValues().apply {
                put("name", m.name); put("mime", m.mimeType); put("date_taken", m.dateTaken)
                put("size", m.size); put("width", m.displayWidth); put("height", m.displayHeight)
                put("duration", m.durationMs); put("rel_path", m.relativePath)
                put("file", dataFile.absolutePath); put("thumb", thumbFile.absolutePath)
                put("added", System.currentTimeMillis())
            }
            db(context).writableDatabase.insert("items", null, v)
        } catch (e: Exception) {
            dataFile.delete(); thumbFile.delete()
            throw e
        }
    }

    suspend fun remove(context: Context, ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val all = list(context).filter { it.id in ids }
        for (i in all) { File(i.file).delete(); File(i.thumb).delete() }
        if (ids.isNotEmpty()) {
            db(context).writableDatabase.execSQL("DELETE FROM items WHERE id IN (${ids.joinToString(",")})")
        }
    }

    /** Puts an item back into the phone's gallery (its original album when possible). */
    suspend fun restore(context: Context, item: VaultItem): Boolean = withContext(Dispatchers.IO) {
        val video = item.isVideo
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val fallback = if (video) "Movies/Restored/" else "Pictures/Restored/"
        fun insert(path: String): Uri? = runCatching {
            context.contentResolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, item.dateTaken)
            })
        }.getOrNull()
        val uri = (item.relativePath.takeIf { it.isNotBlank() }?.let { insert(it) }) ?: insert(fallback)
            ?: return@withContext false
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { os: OutputStream ->
                decrypt(File(item.file)).use { it.copyTo(os, 256 * 1024) }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            true
        }.getOrDefault(false)
        if (ok) remove(context, listOf(item.id)) else runCatching { context.contentResolver.delete(uri, null, null) }
        ok
    }

    /** Decrypts a video to a private temporary file so the player can read it. */
    suspend fun playable(context: Context, item: VaultItem): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "safe-play").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val ext = item.name.substringAfterLast('.', "mp4")
        val out = File(dir, "play.$ext")
        out.outputStream().use { os -> decrypt(File(item.file)).use { it.copyTo(os, 256 * 1024) } }
        out
    }

    fun clearPlayback(context: Context) {
        File(context.cacheDir, "safe-play").listFiles()?.forEach { it.delete() }
    }
}

class VaultThumbFetcher(private val data: VaultThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bytes = Vault.decryptBytes(File(data.path))
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
        DrawableResult(BitmapDrawable(options.context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<VaultThumb> {
        override fun create(data: VaultThumb, options: Options, imageLoader: ImageLoader): Fetcher = VaultThumbFetcher(data, options)
    }
}

class VaultImageFetcher(private val data: VaultImage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val bytes = Vault.decryptBytes(File(data.path))
        val maxSide = max(options.size.width.pxOrElse { 2560 }, options.size.height.pxOrElse { 2560 }).coerceIn(1080, 3200)
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val long = max(info.size.width, info.size.height)
            if (long > maxSide) {
                val s = maxSide.toFloat() / long
                decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
            }
        }
        DrawableResult(BitmapDrawable(options.context.resources, bmp), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<VaultImage> {
        override fun create(data: VaultImage, options: Options, imageLoader: ImageLoader): Fetcher = VaultImageFetcher(data, options)
    }
}

class VaultThumbKeyer : Keyer<VaultThumb> {
    override fun key(data: VaultThumb, options: Options) = "vthumb:${data.path}"
}

class VaultImageKeyer : Keyer<VaultImage> {
    override fun key(data: VaultImage, options: Options) = "vimg:${data.path}"
}
