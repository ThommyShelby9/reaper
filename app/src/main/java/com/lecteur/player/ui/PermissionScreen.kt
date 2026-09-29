package com.lecteur.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.ReaperLogo
import com.lecteur.player.ui.theme.LecteurTheme

/**
 * Demande l'accès aux fichiers audio. Si l'accès a été refusé définitivement,
 * [blocked] est vrai et la touche ouvre les réglages de l'app.
 */
@Composable
fun PermissionScreen(blocked: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        ReaperLogo(
            modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally),
            dotColor = colors.ink,
        )
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.display)
                .padding(16.dp),
        ) {
            Text("Accès à la musique", style = type.displayLarge, color = colors.displayInk)
            Spacer(Modifier.height(10.dp))
            Text(
                if (blocked) {
                    "L'accès aux fichiers audio a été refusé. Autorisez-le dans les réglages de l'app, rubrique Autorisations, puis revenez ici."
                } else {
                    "Reaper lit les fichiers audio enregistrés sur ce téléphone. Autorisez l'accès à vos fichiers audio pour afficher votre bibliothèque."
                },
                style = type.body,
                color = colors.displayMuted,
            )
        }
        Spacer(Modifier.height(20.dp))
        val label = if (blocked) "Ouvrir les réglages" else "Autoriser l'accès"
        HardwareKey(
            onClick = if (blocked) onOpenSettings else onRequest,
            contentDescription = label,
            modifier = Modifier.fillMaxWidth(),
            style = KeyStyle.Accent,
        ) { Text(label, style = type.bodyStrong) }
    }
}
