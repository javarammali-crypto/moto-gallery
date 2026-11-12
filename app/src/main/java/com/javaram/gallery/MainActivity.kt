package com.javaram.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.javaram.gallery.ui.GalleryNav
import com.javaram.gallery.ui.external.ExternalViewer
import com.javaram.gallery.ui.permission.PermissionScreen
import com.javaram.gallery.ui.theme.GalleryTheme

class MainActivity : ComponentActivity() {

    private val vm: GalleryViewModel by viewModels()
    private var hasAccess by mutableStateOf(false)
    private var externalUri by mutableStateOf<Pair<Uri, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferHighRefreshRate()
        readIntent(intent)
        hasAccess = checkAccess()
        setContent {
            GalleryTheme {
                val ext = externalUri
                when {
                    ext != null -> ExternalViewer(
                        uri = ext.first,
                        mime = ext.second,
                        onClose = { finish() },
                    )
                    hasAccess -> GalleryNav(vm)
                    else -> PermissionScreen(
                        permissions = mediaPermissions(),
                        onResult = {
                            hasAccess = checkAccess()
                            if (hasAccess) vm.start()
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        hasAccess = checkAccess()
        if (hasAccess) vm.start()
        vm.refreshManagePermission()
    }

    /** Ask for the panel's fastest mode (120 Hz on the Edge 70 Fusion) instead of the 60 Hz default. */
    private fun preferHighRefreshRate() {
        runCatching {
            val d = display ?: return
            val cur = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
    }

    private fun readIntent(intent: Intent?) {
        val action = intent?.action
        externalUri = if ((action == Intent.ACTION_VIEW || action == "com.android.camera.action.REVIEW") && intent.data != null) {
            intent.data!! to intent.type
        } else null
    }

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
        }

    private fun checkAccess(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else Manifest.permission.READ_EXTERNAL_STORAGE
        val full = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED
        return full || partial
    }
}
