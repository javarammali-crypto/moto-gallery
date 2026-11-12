package com.javaram.gallery.ui.components

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.javaram.gallery.data.Media

/**
 * Returns a function that launches a MediaStore consent request (trash, delete, favourite)
 * and reports whether the user approved it.
 */
@Composable
fun rememberMediaRequest(onResult: (Boolean) -> Unit): (PendingIntent) -> Unit {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> callback(result.resultCode == Activity.RESULT_OK) }
    return remember(launcher) {
        { pi: PendingIntent ->
            runCatching { launcher.launch(IntentSenderRequest.Builder(pi.intentSender).build()) }
                .onFailure { callback(false) }
        }
    }
}

fun shareMedia(context: Context, items: List<Media>) {
    if (items.isEmpty()) return
    val intent = if (items.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items[0].mimeType
            putExtra(Intent.EXTRA_STREAM, items[0].uri)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = when {
                items.all { it.isVideo } -> "video/*"
                items.none { it.isVideo } -> "image/*"
                else -> "*/*"
            }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(items.map { it.uri }))
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri("", items[0].uri).apply {
        items.drop(1).forEach { addItem(ClipData.Item(it.uri)) }
    }
    context.startActivity(Intent.createChooser(intent, null))
}

fun openWith(context: Context, media: Media) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(media.uri, media.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Open with")) }
}

fun setAs(context: Context, media: Media) {
    val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
        setDataAndType(media.uri, media.mimeType)
        putExtra("mimeType", media.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Set as")) }
}

fun openMaps(context: Context, lat: Double, lng: Double) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))
    runCatching { context.startActivity(intent) }
}

fun requestManageMedia(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }
        .onFailure { toast(context, "Open Settings › Apps › Special access › Media management") }
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
