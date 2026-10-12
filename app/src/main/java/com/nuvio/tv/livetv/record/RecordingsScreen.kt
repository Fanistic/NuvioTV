@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nuvio.tv.livetv.record

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.nuvio.tv.livetv.ui.LIVE_RED
import com.nuvio.tv.livetv.ui.LiveText
import com.nuvio.tv.livetv.ui.liveCorner
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    val recorder: LiveTvRecorder,
    val scheduler: LiveTvRecordingScheduler,
    private val posterResolver: com.nuvio.tv.livetv.data.LiveTvPosterResolver
) : ViewModel() {
    val recordings = recorder.recordings
    val schedules = scheduler.schedules
    val series = scheduler.series

    private val _posters = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Recording id -> poster, looked up by title the same way the guide does. */
    val posters: StateFlow<Map<String, String>> = _posters.asStateFlow()
    private val tried = HashSet<String>()

    fun loadPosters(list: List<LiveTvRecording>) {
        val todo = list.filter { tried.add(it.id) }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            for (r in todo) {
                val poster = runCatching { posterResolver.posterFor(r.title, null, com.nuvio.tv.livetv.data.LiveTvPosterResolver.Clues(), urgent = false) }.getOrNull()
                if (poster != null) _posters.value = _posters.value + (r.id to poster)
            }
        }
    }

    fun playUrl(rec: LiveTvRecording): String = recorder.playUrl(rec)
    /** Where you left off (ms), if partly watched. */
    fun resume(rec: LiveTvRecording): Long? = RecordingResume.load(recorder.appContext, RecordingResume.contentIdFor(rec.id))?.first
    fun stop(rec: LiveTvRecording) = recorder.stop(rec.id)
    fun delete(rec: LiveTvRecording) = recorder.delete(rec.id)
    fun freeBytes(): Long = recorder.freeBytes()
}

/**
 * Recordings: what's recording now and what's been recorded, newest first. OK plays (in
 * Nuvio's player, with its seek bar), the menu button or a long press offers Stop and Delete.
 */
@Composable
fun RecordingsScreen(
    onPlay: (url: String, title: String, poster: String?, recordingId: String, fromStart: Boolean) -> Unit,
    viewModel: RecordingsViewModel = hiltViewModel()
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val seriesRules by viewModel.series.collectAsStateWithLifecycle()
    val posters by viewModel.posters.collectAsStateWithLifecycle()
    var scheduleMenu by remember { mutableStateOf<RecordingSchedule?>(null) }
    // Back from the player: read where you got to again (the progress bars).
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var seriesMenu by remember { mutableStateOf<SeriesRule?>(null) }
    LaunchedEffect(recordings) { viewModel.loadPosters(recordings) }
    var menuFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    var confirmDelete by remember { mutableStateOf<LiveTvRecording?>(null) }
    val first = remember { FocusRequester() }
    val colors = NuvioTheme.colors

    val now = recordings.filter { it.isRecording }
    val done = recordings.filter { !it.isRecording }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
            .padding(start = 56.dp, end = 48.dp, top = 36.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            LiveText("Recordings", size = 30.sp, weight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            LiveText(
                "${recordings.size} · ${sizeLabel(viewModel.freeBytes())} free",
                size = 15.sp,
                color = colors.TextSecondary
            )
        }
        Spacer(Modifier.height(20.dp))
        if (recordings.isEmpty() && schedules.isEmpty() && seriesRules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LiveText("No recordings yet", size = 20.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LiveText(
                        "In the Live TV guide, long-press a show and choose Record or Record series. While watching, open the menu and choose Record this show.",
                        size = 14.sp, color = colors.TextSecondary, maxLines = 2
                    )
                }
            }
            return@Column
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 48.dp)
        ) {
            if (now.isNotEmpty()) {
                item(key = "h_now") { SectionTitle("Recording Now") }
                items(now, key = { it.id }) { rec ->
                    RecordingRow(
                        rec, posters[rec.id],
                        modifier = if (rec == recordings.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                        onClick = { onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false) },
                        onMenu = { menuFor = rec }
                    )
                }
            }
            if (schedules.isNotEmpty()) {
                item(key = "h_sched") { SectionTitle("Scheduled") }
                items(schedules, key = { it.id }) { s ->
                    SimpleRow(
                        title = s.title + (s.episode?.let { "  ·  $it" } ?: ""),
                        line = s.channel.name + " · " + dateLabel(s.startMs),
                        note = if (s.useCatchup) "Saved from catch-up after it ends" + (if (s.attempts > 0) " (trying again)" else "")
                            else "Records live, 2 min early to 5 min late",
                        logo = s.channel.logo,
                        modifier = if (recordings.isEmpty() && s == schedules.first()) Modifier.focusRequester(first) else Modifier,
                        onClick = { scheduleMenu = s }
                    )
                }
            }
            if (seriesRules.isNotEmpty()) {
                item(key = "h_series") { SectionTitle("Series") }
                items(seriesRules, key = { it.id }) { r ->
                    SimpleRow(
                        title = r.title,
                        line = "Every airing on " + r.channel.name + (if (r.keepLast > 0) " · keeps the last ${r.keepLast}" else ""),
                        note = null,
                        logo = r.channel.logo,
                        modifier = if (recordings.isEmpty() && schedules.isEmpty() && r == seriesRules.first()) Modifier.focusRequester(first) else Modifier,
                        onClick = { seriesMenu = r }
                    )
                }
            }
            if (done.isNotEmpty()) {
                item(key = "h_done") { SectionTitle("Recorded") }
                items(done, key = { it.id }) { rec ->
                    RecordingRow(
                        rec, posters[rec.id],
                        resumeFraction = resumeTick.let { viewModel.resume(rec) }?.let { ms -> if (rec.durationSec > 0) (ms / 1000.0 / rec.durationSec).toFloat().coerceIn(0f, 1f) else null },
                        modifier = if (rec == recordings.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                        onClick = {
                            if (rec.status != RecordingStatus.FAILED) onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
                            else menuFor = rec
                        },
                        onMenu = { menuFor = rec }
                    )
                }
            }
        }
    }
    val anything = recordings.isNotEmpty() || schedules.isNotEmpty() || seriesRules.isNotEmpty()
    LaunchedEffect(anything) {
        if (anything) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
    }
    scheduleMenu?.let { s ->
        RecordingActionDialog(
            title = s.title,
            detail = s.channel.name + " · " + dateLabel(s.startMs),
            actions = listOf(
                "Cancel Recording" to { scheduleMenu = null; viewModel.scheduler.cancel(s.id) },
                "Close" to { scheduleMenu = null }
            ),
            onDismiss = { scheduleMenu = null }
        )
    }
    seriesMenu?.let { r ->
        RecordingActionDialog(
            title = r.title,
            detail = "Every airing on " + r.channel.name + ". Stopping it also cancels the airings it set that haven't started; what's recorded is kept.",
            actions = listOf(0, 1, 3, 5, 10).map { n ->
                val label = (if (n == 0) "Keep All Episodes" else "Keep the Last $n") + if (r.keepLast == n) "  ✓" else ""
                label to { seriesMenu = null; viewModel.scheduler.setKeep(r.id, n) }
            } + listOf(
                "Stop Recording Series" to { seriesMenu = null; viewModel.scheduler.removeSeries(r.id) },
                "Close" to { seriesMenu = null }
            ),
            onDismiss = { seriesMenu = null }
        )
    }

    menuFor?.let { rec ->
        val actions = buildList<Pair<String, () -> Unit>> {
            if (rec.status != RecordingStatus.FAILED) {
                val resumeAt = viewModel.resume(rec)
                add((if (resumeAt != null) "Resume" else "Play") to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false) })
                if (resumeAt != null) add("Play from Beginning" to { menuFor = null; onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, true) })
            }
            if (rec.isRecording) add("Stop Recording" to { menuFor = null; viewModel.stop(rec) })
            add("Delete" to { menuFor = null; confirmDelete = rec })
        }
        RecordingActionDialog(title = rec.title, detail = rec.problem, actions = actions, onDismiss = { menuFor = null })
    }
    confirmDelete?.let { rec ->
        RecordingActionDialog(
            title = "Delete \"${rec.title}\"?",
            detail = if (rec.isRecording) "It's still recording. This stops it and deletes what's been recorded." else "This can't be undone.",
            actions = listOf(
                "Cancel" to { confirmDelete = null },
                "Delete" to { confirmDelete = null; viewModel.delete(rec) }
            ),
            onDismiss = { confirmDelete = null }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    LiveText(
        text.uppercase(),
        size = 13.sp,
        weight = FontWeight.Bold,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
    )
}

@Composable
private fun RecordingRow(
    rec: LiveTvRecording,
    poster: String?,
    modifier: Modifier,
    /** How much has been watched (0..1), if started. */
    resumeFraction: Float? = null,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(if (focused) colors.FocusBackground else colors.BackgroundCard)
            .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 62.dp, height = 92.dp)
                .clip(RoundedCornerShape(liveCorner()))
                .background(colors.BackgroundElevated),
            contentAlignment = Alignment.Center
        ) {
            val image = poster ?: rec.channelLogo
            if (image != null) {
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = if (poster != null) ContentScale.Crop else ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(if (poster != null) 0.dp else 8.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.isRecording) {
                    LiveText("● REC", size = 13.sp, weight = FontWeight.Bold, color = LIVE_RED)
                    Spacer(Modifier.width(8.dp))
                }
                LiveText(rec.title, size = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false), marquee = focused)
                rec.episode?.let {
                    Spacer(Modifier.width(8.dp))
                    LiveText(it, size = 14.sp, color = colors.TextSecondary)
                }
            }
            LiveText(
                listOfNotNull(
                    rec.channelName,
                    dateLabel(rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs),
                    durationLabel(rec.durationSec).takeIf { rec.durationSec > 0 },
                    sizeLabel(rec.sizeBytes).takeIf { rec.sizeBytes > 0 }
                ).joinToString(" · "),
                size = 14.sp,
                color = colors.TextSecondary
            )
            val status = when {
                rec.isRecording -> "Recording until " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(rec.stopAtMs))
                rec.status == RecordingStatus.FAILED -> rec.problem ?: "Couldn't be recorded"
                else -> rec.problem
            }
            status?.let {
                LiveText(
                    it, size = 13.sp,
                    color = if (rec.status == RecordingStatus.FAILED) colors.Error else colors.TextTertiary,
                    maxLines = 1
                )
            }
            // Partly watched: how far.
            resumeFraction?.let { f ->
                Box(Modifier.padding(top = 4.dp).width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.TextTertiary.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxSize().padding(end = (220 * (1f - f)).dp).background(colors.Secondary))
                }
            }
        }
    }
}

/** A scheduled show or a series rule: channel logo, title and when. */
@Composable
private fun SimpleRow(
    title: String,
    line: String,
    note: String?,
    logo: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(shape)
            .background(if (focused) colors.FocusBackground else colors.BackgroundCard)
            .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 72.dp, height = 48.dp), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText(title, size = 17.sp, weight = FontWeight.SemiBold, marquee = focused)
            LiveText(line, size = 14.sp, color = colors.TextSecondary)
            note?.let { LiveText(it, size = 12.sp, color = colors.TextTertiary) }
        }
    }
}

@Composable
private fun RecordingActionDialog(
    title: String,
    detail: String?,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { first.requestFocus() } }
    com.nuvio.tv.ui.components.NuvioDialog(onDismiss = onDismiss, title = title, subtitle = detail, width = 480.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            actions.forEachIndexed { i, (label, action) ->
                com.nuvio.tv.ui.screens.settings.SettingsActionRow(
                    title = label,
                    subtitle = null,
                    onClick = action,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                )
            }
        }
    }
}

private fun dateLabel(ms: Long): String =
    SimpleDateFormat("EEE d MMM, h:mm a", Locale.getDefault()).format(Date(ms))

private fun durationLabel(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}
