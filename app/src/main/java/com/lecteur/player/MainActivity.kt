package com.lecteur.player

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import com.lecteur.player.share.ScreenshotSignal
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.jam.JamManager
import com.lecteur.player.jam.parseJamLink
import com.lecteur.player.playback.PlaybackSettings
import com.lecteur.player.playback.ThemeMode
import com.lecteur.player.ui.LecteurApp
import com.lecteur.player.ui.theme.LecteurTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        PlaybackSettings.init(this)
        if (savedInstanceState == null) handleInvite(intent)
        setContent {
            val prefs by PlaybackSettings.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (prefs.themeMode) {
                ThemeMode.Auto -> systemDark
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Les icônes de la barre d'état suivent le thème choisi, pas seulement celui du téléphone.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                // Le widget d'écran d'accueil prend le même thème que l'app.
                com.lecteur.player.widget.ReaperWidget.refresh(this@MainActivity)
            }
            LecteurTheme(darkTheme = dark) {
                LecteurApp()
            }
        }
    }

    /** Capture d'écran de Reaper → proposition de carte à partager (Android 14+, API officielle, sans autorisation à demander). */
    private var captureCallback: Any? = null

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val callback = ScreenCaptureCallback { ScreenshotSignal.notifyCaptured() }
            captureCallback = callback
            registerScreenCaptureCallback(mainExecutor, callback)
        }
    }

    override fun onStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (captureCallback as? ScreenCaptureCallback)?.let(::unregisterScreenCaptureCallback)
            captureCallback = null
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    /** Lien d'invitation (QR code, message) : le code attend que l'écran Jam l'utilise. */
    private fun handleInvite(intent: Intent?) {
        val code = intent?.dataString?.let(::parseJamLink) ?: return
        JamManager.pendingInvite.value = code
    }
}
