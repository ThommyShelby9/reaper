package com.lecteur.player.ui.lockscreen

import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.audioPermission
import com.lecteur.player.ui.theme.LecteurTheme

/**
 * Lecteur plein écran par-dessus l'écran de verrouillage. Le service de lecture l'ouvre quand l'écran s'éteint
 * pendant la lecture (option des réglages). Toujours en thème « Nuit » : fond noir, économe sur les écrans OLED.
 */
class LockScreenActivity : ComponentActivity() {

    private val viewModel: LecteurViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        // Plein écran : barres système masquées, un glissement depuis le bord les fait réapparaître.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        LockScreenLauncher.dismissNotification(this)
        val granted = ContextCompat.checkSelfPermission(this, audioPermission()) == PackageManager.PERMISSION_GRANTED
        viewModel.onPermissionChanged(granted)

        setContent {
            LecteurTheme(darkTheme = true) {
                LockScreen(viewModel, onUnlock = ::unlock)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        LockScreenLauncher.dismissNotification(this)
    }

    /** Demande à Android de déverrouiller (code, empreinte…) puis ferme cet écran. */
    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = finish()
            })
        } else {
            finish()
        }
    }
}
