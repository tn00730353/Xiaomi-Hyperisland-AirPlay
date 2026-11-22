// © DylanLiao — HyperCast. Developer watermark: DylanLiao. All rights reserved.
package com.hypercast.airplay

import android.Manifest
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.hypercast.airplay.ui.AppTheme
import com.hypercast.airplay.ui.MainScreen
import com.hypercast.airplay.ui.ThemeStore

class MainActivity : ComponentActivity() {

    private lateinit var discovery: RaopDiscovery
    private var pendingReceiver: Receiver? = null

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val rx = pendingReceiver
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null && rx != null) {
                CastService.start(this, rx, r.resultCode, data)
            } else {
                AirPlayStore.update { it.copy(message = "需要授權擷取音訊才能串流") }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true || hasMic()) {
                projectionLauncher.launch(
                    getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
                )
            } else {
                AirPlayStore.update { it.copy(message = "需要「錄音」權限才能擷取系統音訊") }
            }
        }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun connect(rx: Receiver) {
        pendingReceiver = rx
        val need = mutableListOf<String>()
        if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isEmpty()) {
            projectionLauncher.launch(
                getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
            )
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        discovery = RaopDiscovery(this)

        android.util.Log.i("HyperCast", "Developed by ${BuildConfig.DEVELOPER}")
        var theme by mutableStateOf(ThemeStore.load(this))

        setContent {
            val state by AirPlayStore.state.collectAsState()
            MainScreen(
                state = state,
                theme = theme,
                onTheme = { t: AppTheme -> theme = t; ThemeStore.save(this, t) },
                onConnect = ::connect,
                onDisconnect = { CastService.stop(this) },
                onVolume = AirPlayStore::setVolume,
                onMute = AirPlayStore::toggleMute,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }
}
