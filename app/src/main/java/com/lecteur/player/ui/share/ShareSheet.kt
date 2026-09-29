package com.lecteur.player.ui.share

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lecteur.player.data.Track
import com.lecteur.player.lyrics.Lyrics
import com.lecteur.player.share.LyricExcerpt
import com.lecteur.player.share.ShareActions
import com.lecteur.player.share.ShareCardRenderer
import com.lecteur.player.share.ShareTemplate
import com.lecteur.player.share.lyricExcerpt
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.theme.LecteurTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Proposé après une capture d'écran de Reaper (ou à la demande) : une carte au format story
 * avec la pochette, la pochette et quelques lignes de paroles, ou les paroles seules,
 * à envoyer en statut WhatsApp, dans une autre app ou dans la galerie. Toujours facultatif.
 */
@Composable
fun ShareSheet(
    track: Track,
    cover: ImageBitmap?,
    lyrics: Lyrics?,
    positionMs: Long,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val context = LocalContext.current
    val renderer = remember { ShareCardRenderer(context) }
    val hasLyrics = lyrics?.lines?.any { it.text.isNotBlank() } == true
    var template by remember { mutableStateOf(ShareTemplate.Cover) }
    var shift by remember { mutableIntStateOf(0) }
    var card by remember { mutableStateOf<Bitmap?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val excerpt = remember(lyrics, shift) {
        lyrics?.let { lyricExcerpt(it, positionMs, shift) } ?: LyricExcerpt(emptyList(), -1, canGoBack = false, canGoForward = false)
    }
    val progress = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f

    LaunchedEffect(template, excerpt, cover) {
        card = withContext(Dispatchers.Default) {
            renderer.render(template, track, cover?.asAndroidBitmap(), excerpt, progress)
        }
    }
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(colors.body)
                // Les touches dans le panneau ne ferment pas le partage.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text("Partager ce morceau", style = type.bodyStrong, color = colors.ink)
                    Text(
                        "${track.title}, ${track.artist}",
                        style = type.label,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HardwareKey(onClick = onDismiss, contentDescription = "Fermer le partage", modifier = Modifier.size(width = 44.dp, height = 40.dp)) {
                    Icon(LecteurIcons.Close, contentDescription = null)
                }
            }
            Spacer(Modifier.height(12.dp))

            // Aperçu de la carte, au format story.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val image = card
                Box(
                    Modifier
                        .height(300.dp)
                        .aspectRatio(ShareCardRenderer.WIDTH / ShareCardRenderer.HEIGHT.toFloat())
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                ) {
                    if (image != null) {
                        Image(image.asImageBitmap(), contentDescription = "Aperçu de la carte : ${template.label}", modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareTemplate.entries.filter { !it.needsLyrics || hasLyrics }.forEach { entry ->
                    val selected = entry == template
                    HardwareKey(
                        onClick = { template = entry },
                        contentDescription = entry.label,
                        modifier = Modifier.weight(1f).height(44.dp).semantics { this.selected = selected },
                        style = if (selected) KeyStyle.Selected else KeyStyle.Standard,
                    ) { Text(entry.label, style = type.label.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (!hasLyrics) {
                Text(
                    "Pas de paroles trouvées pour ce titre : seule la carte avec la pochette est possible.",
                    style = type.label,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(4.dp),
                )
            }
            if (template.needsLyrics) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HardwareKey(
                        onClick = { if (excerpt.canGoBack) shift-- },
                        contentDescription = "Lignes précédentes",
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) { Text("Lignes précédentes", style = type.label.copy(fontSize = 11.sp), maxLines = 1) }
                    HardwareKey(
                        onClick = { if (excerpt.canGoForward) shift++ },
                        contentDescription = "Lignes suivantes",
                        modifier = Modifier.weight(1f).height(40.dp),
                    ) { Text("Lignes suivantes", style = type.label.copy(fontSize = 11.sp), maxLines = 1) }
                }
            }
            Spacer(Modifier.height(14.dp))

            val caption = "${track.title}, ${track.artist}"
            val whatsApp = remember { ShareActions.whatsAppPackage(context) }
            fun withCard(action: (Bitmap) -> Unit) {
                card?.let(action)
            }
            if (whatsApp != null) {
                HardwareKey(
                    onClick = {
                        withCard { bitmap ->
                            val uri = ShareActions.cacheCard(context, bitmap)
                            runCatching { context.startActivity(ShareActions.shareIntent(uri, caption, whatsApp)) }
                                .onFailure { notice = "WhatsApp n'a pas pu s'ouvrir." }
                        }
                    },
                    contentDescription = "Envoyer en statut WhatsApp",
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    style = KeyStyle.Accent,
                ) { Text("Statut WhatsApp", style = type.bodyStrong) }
                Text(
                    "WhatsApp s'ouvre : choisissez « Mon statut » en haut de la liste.",
                    style = type.label,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(4.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HardwareKey(
                    onClick = {
                        withCard { bitmap ->
                            val uri = ShareActions.cacheCard(context, bitmap)
                            context.startActivity(ShareActions.chooser(uri, caption))
                        }
                    },
                    contentDescription = "Partager dans une autre app",
                    modifier = Modifier.weight(1f).height(48.dp),
                    style = if (whatsApp == null) KeyStyle.Accent else KeyStyle.Standard,
                ) { Text("Autres apps", style = type.label.copy(fontSize = 12.sp)) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    HardwareKey(
                        onClick = {
                            withCard { bitmap ->
                                notice = if (ShareActions.saveToGallery(context, bitmap)) {
                                    "Carte enregistrée dans Images › Reaper."
                                } else {
                                    "La carte n'a pas pu être enregistrée."
                                }
                            }
                        },
                        contentDescription = "Enregistrer la carte dans la galerie",
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Enregistrer", style = type.label.copy(fontSize = 12.sp)) }
                }
            }
            Text(
                notice ?: "Proposé après chaque capture d'écran. Désactivable dans Réglages › Partage.",
                style = type.label,
                color = if (notice != null) colors.ink else colors.inkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
