package com.lecteur.player.ui.recap

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lecteur.player.data.countLabel
import com.lecteur.player.history.PlayEntity
import com.lecteur.player.history.Recap
import com.lecteur.player.history.RecapPeriod
import com.lecteur.player.history.computeRecap
import com.lecteur.player.history.formatListening
import com.lecteur.player.ui.LecteurViewModel
import com.lecteur.player.ui.PreviewData
import com.lecteur.player.ui.components.DotMeter
import com.lecteur.player.ui.components.HardwareKey
import com.lecteur.player.ui.components.KeyStyle
import com.lecteur.player.ui.components.LecteurIcons
import com.lecteur.player.ui.theme.LecteurTheme
import java.time.ZoneId

@Composable
fun RecapRoute(viewModel: LecteurViewModel, onClose: () -> Unit) {
    val recap by viewModel.recap.collectAsStateWithLifecycle()
    val period by viewModel.recapPeriod.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    RecapScreen(recap, period, onPeriodChange = { viewModel.recapPeriod.value = it }, onClose = onClose)
}

@Composable
fun RecapScreen(
    recap: Recap?,
    period: RecapPeriod,
    onPeriodChange: (RecapPeriod) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column(
        modifier
            .fillMaxSize()
            .background(colors.body)
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            HardwareKey(onClick = onClose, contentDescription = "Revenir à la bibliothèque", modifier = Modifier.size(width = 48.dp, height = 40.dp)) {
                Icon(LecteurIcons.ChevronLeft, contentDescription = null)
            }
            Spacer(Modifier.width(12.dp))
            Text("Bilan", style = type.displayLarge, color = colors.ink)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecapPeriod.entries.forEach { entry ->
                val selected = entry == period
                HardwareKey(
                    onClick = { onPeriodChange(entry) },
                    contentDescription = entry.label,
                    modifier = Modifier.weight(1f).height(40.dp).semantics { this.selected = selected },
                    style = if (selected) KeyStyle.Selected else KeyStyle.Standard,
                ) { Text(entry.label, style = type.label.copy(fontSize = 11.sp), maxLines = 1) }
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            recap == null -> Text("Calcul du bilan…", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(4.dp))
            recap.isEmpty -> Column(Modifier.padding(horizontal = 4.dp)) {
                Text("Pas encore d'écoutes sur cette période", style = type.bodyStrong, color = colors.ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Le bilan se remplit à mesure que vous écoutez. Un titre compte quand vous en avez entendu au moins la moitié.",
                    style = type.body,
                    color = colors.inkMuted,
                )
            }
            else -> RecapContent(recap)
        }
    }
}

@Composable
private fun RecapContent(recap: Recap) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type

    // Les trois chiffres clés, dans l'écran noir.
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.display)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Figure(formatListening(recap.listenedMs), "d'écoute", highlight = true)
        Figure(recap.plays.toString(), if (recap.plays > 1) "écoutes" else "écoute")
        Figure(recap.distinctTracks.toString(), if (recap.distinctTracks > 1) "titres différents" else "titre")
    }

    Section("Par heure de la journée")
    HourHistogram(recap.byHour)
    recap.peakHour?.let {
        Spacer(Modifier.height(6.dp))
        Text("Vous écoutez surtout vers $it h.", style = type.body, color = colors.inkMuted, modifier = Modifier.padding(horizontal = 4.dp))
    }

    if (recap.topArtists.isNotEmpty()) {
        Section("Artistes les plus écoutés")
        val max = recap.topArtists.first().plays.toFloat()
        recap.topArtists.forEachIndexed { index, artist ->
            RankRow(index + 1, artist.name, null, artist.plays, artist.plays / max)
        }
    }
    if (recap.topTracks.isNotEmpty()) {
        Section("Titres les plus écoutés")
        val max = recap.topTracks.first().plays.toFloat()
        recap.topTracks.forEachIndexed { index, ranked ->
            RankRow(index + 1, ranked.track.title, ranked.track.artist, ranked.plays, ranked.plays / max)
        }
    }

    Section("Régularité")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(countLabel(recap.activeDays, "jour", "jours"), "avec au moins une écoute", Modifier.weight(1f))
        StatTile(countLabel(recap.longestStreakDays, "jour", "jours"), "d'affilée, au plus long", Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Figure(value: String, label: String, highlight: Boolean = false) {
    val colors = LecteurTheme.colors
    Column {
        Text(value, style = LecteurTheme.type.displayTitle.copy(fontSize = 22.sp), color = if (highlight) colors.accent else colors.displayInk)
        Text(label, style = LecteurTheme.type.label, color = colors.displayMuted)
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = LecteurTheme.type.bodyStrong, color = LecteurTheme.colors.ink, modifier = Modifier.padding(horizontal = 4.dp))
    Spacer(Modifier.height(8.dp))
}

/** 24 colonnes de points, une par heure : plus on écoute à cette heure, plus la colonne s'allume. */
@Composable
private fun HourHistogram(byHour: LongArray) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    val max = byHour.maxOrNull()?.takeIf { it > 0 } ?: 1L
    val peak = byHour.indices.maxByOrNull { byHour[it] }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.display)
            .padding(12.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .semantics { contentDescription = "Écoute par heure, maximum vers ${peak ?: 0} h" },
        ) {
            val rows = 8
            val colWidth = size.width / 24f
            val step = size.height / rows
            val radius = minOf(colWidth, step) * 0.32f
            for (hour in 0 until 24) {
                val lit = if (byHour[hour] == 0L) 0 else ((byHour[hour].toFloat() / max) * rows).toInt().coerceIn(1, rows)
                for (r in 0 until rows) {
                    val color = when {
                        r >= lit -> colors.dotOff
                        hour == peak -> colors.accent
                        else -> colors.displayInk
                    }
                    drawCircle(color, radius, Offset((hour + 0.5f) * colWidth, size.height - (r + 0.5f) * step))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("0 h", "6 h", "12 h", "18 h").forEach {
                Text(it, style = type.label, color = colors.displayMuted, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, title: String, subtitle: String?, plays: Int, level: Float) {
    val colors = LecteurTheme.colors
    val type = LecteurTheme.type
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(rank.toString(), style = type.displayValue.copy(fontSize = 16.sp), color = colors.inkMuted, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = type.bodyStrong, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = type.label, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            DotMeter(level = level, modifier = Modifier.size(width = 64.dp, height = 10.dp), dots = 8)
            Spacer(Modifier.width(8.dp))
            Text(plays.toString(), style = type.displayValue.copy(fontSize = 14.sp), color = colors.ink, modifier = Modifier.width(32.dp))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
    }
}

@Composable
private fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val colors = LecteurTheme.colors
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.tile)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(value, style = LecteurTheme.type.displayValue.copy(fontSize = 18.sp), color = colors.ink)
        Text(label, style = LecteurTheme.type.label, color = colors.inkMuted)
    }
}

private fun previewRecap(): Recap {
    val hour = 3_600_000L
    val plays = PreviewData.tracks.flatMapIndexed { i, t ->
        List(6 - i) { n -> PlayEntity(trackId = t.id, playedAt = n * 26 * hour + (20 + i % 4) * hour, listenedMs = t.durationMs) }
    }
    return computeRecap(plays, PreviewData.tracks.associateBy { it.id }, ZoneId.of("UTC"))
}

@Preview(name = "Bilan, Appareil", widthDp = 360, heightDp = 1100)
@Composable
private fun RecapAppareilPreview() {
    LecteurTheme(darkTheme = false) { RecapScreen(previewRecap(), RecapPeriod.Year, {}, {}) }
}

@Preview(name = "Bilan, Nuit", widthDp = 360, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RecapNuitPreview() {
    LecteurTheme(darkTheme = true) { RecapScreen(previewRecap(), RecapPeriod.Year, {}, {}) }
}
