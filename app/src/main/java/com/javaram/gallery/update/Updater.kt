package com.javaram.gallery.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

/**
 * Self-updater: reads the latest GitHub release of the app's repo, downloads the APK
 * and installs it with PackageInstaller. After the first in-app update Android lets
 * later updates install without the extra confirmation screen.
 */
object Updater {
    private const val REPO = "javarammali-crypto/moto-gallery"

    fun installedVersionCode(context: Context): Long =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Gallery-Updater")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name")
            val code = Regex("""(\d+)$""").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext UpdateInfo(
                        versionCode = code,
                        versionName = json.optString("name").ifEmpty { tag },
                        apkUrl = a.optString("browser_download_url"),
                        sizeBytes = a.optLong("size"),
                        notes = json.optString("body"),
                    )
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * HTTP client for update downloads. GitHub hands the APK out from a separate download
     * server; on some mobile networks the IPv6 route to it hangs, which looked like the
     * download "never starting". IPv4 addresses are tried first and every route gets a short
     * connect timeout, so a bad route is skipped quickly instead of stalling.
     */
    private val http: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> =
                    okhttp3.Dns.SYSTEM.lookup(hostname).sortedBy { if (it is java.net.Inet4Address) 0 else 1 }
            })
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Downloads the APK. [onProgress] gets -1 while connecting, then 0..1. A dropped or
     * stalled connection is retried up to 4 times, carrying on from where it stopped.
     */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update-${info.versionCode}.apk")
            dir.listFiles()?.filter { it != out }?.forEach { it.delete() }
            // A finished file from an earlier try can be reused.
            if (info.sizeBytes > 0 && out.length() == info.sizeBytes) {
                onProgress(1f)
                return@withContext out
            }
            if (info.sizeBytes > 0 && out.length() > info.sizeBytes) out.delete()

            var lastError: Exception? = null
            for (attempt in 0 until 4) {
                if (attempt > 0) kotlinx.coroutines.delay(1_500L * attempt)
                onProgress(if (out.length() > 0 && info.sizeBytes > 0) out.length().toFloat() / info.sizeBytes else -1f)
                try {
                    val have = out.length()
                    val request = okhttp3.Request.Builder()
                        .url(info.apkUrl)
                        .header("User-Agent", "Gallery-Updater")
                        .apply { if (have > 0) header("Range", "bytes=$have-") }
                        .build()
                    http.newCall(request).execute().use { resp ->
                        val resumed = resp.code == 206
                        if (!resp.isSuccessful) error("Download failed (${resp.code})")
                        val body = resp.body ?: error("Download failed")
                        val start = if (resumed) have else 0L
                        val total = if (resumed) start + body.contentLength() else body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                        java.io.FileOutputStream(out, resumed).use { output ->
                            body.byteStream().use { input ->
                                val buf = ByteArray(64 * 1024)
                                var done = start
                                var lastReport = 0L
                                while (true) {
                                    val read = input.read(buf)
                                    if (read < 0) break
                                    output.write(buf, 0, read)
                                    done += read
                                    val now = System.currentTimeMillis()
                                    if (total > 0 && now - lastReport > 120) {
                                        lastReport = now
                                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                    }
                                }
                            }
                        }
                    }
                    if (info.sizeBytes > 0 && out.length() != info.sizeBytes) error("Download incomplete")
                    onProgress(1f)
                    return@withContext out
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (e.message == "Download incomplete" && out.length() > info.sizeBytes) out.delete()
                }
            }
            val e = lastError
            throw IllegalStateException(
                if (e is java.net.SocketTimeoutException || e is java.net.UnknownHostException || e is java.io.InterruptedIOException)
                    "Couldn't reach GitHub. Check your connection and try again."
                else e?.message ?: "Update failed"
            )
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 34) setRequestUpdateOwnership(true)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed"
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }
}
