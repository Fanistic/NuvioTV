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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
 * Recordings: what's recording now across the top, then three columns: Scheduled, Series and
 * Recorded. OK plays (in Nuvio's player, with its seek bar); the menu button or a long press
 * offers Resume / Play from Beginning, Stop and Delete. Same background as the guide (the
 * theme's glow at the top), not plain black.
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
    // The clock, for "recording until" bars (moves on every few seconds).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5_000); nowMs = System.currentTimeMillis() }
    }
    var seriesMenu by remember { mutableStateOf<SeriesRule?>(null) }
    LaunchedEffect(recordings) { viewModel.loadPosters(recordings) }
    var menuFor by remember { mutableStateOf<LiveTvRecording?>(null) }
    var confirmDelete by remember { mutableStateOf<LiveTvRecording?>(null) }
    val first = remember { FocusRequester() }
    val colors = NuvioTheme.colors

    val now = recordings.filter { it.isRecording }
    val done = recordings.filter { !it.isRecording }
    val playNow: (LiveTvRecording) -> Unit = { rec ->
        // Nothing saved yet (it's still connecting): say so instead of an error.
        if (rec.durationSec <= 0) LiveTvNotice.show(
            "\"${rec.title}\" hasn't recorded anything yet" + (rec.problem?.let { ": $it" } ?: ". Give it a few seconds.")
        )
        else onPlay(viewModel.playUrl(rec), rec.title, posters[rec.id], rec.id, false)
    }
    // Which item gets the highlight first: the top of the first section that has anything.
    val firstNowId = now.firstOrNull()?.id
    val firstScheduleId = if (now.isEmpty()) schedules.firstOrNull()?.id else null
    val firstSeriesId = if (now.isEmpty() && schedules.isEmpty()) seriesRules.firstOrNull()?.id else null
    val firstDoneId = if (now.isEmpty() && schedules.isEmpty() && seriesRules.isEmpty()) done.firstOrNull()?.id else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.Background)
    ) {
        // The guide's background: the theme's glow behind the top of the screen.
        com.nuvio.tv.livetv.ui.ThemeGlow(Modifier.fillMaxWidth().height(260.dp))
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 56.dp, end = 48.dp, top = 32.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                LiveText("Recordings", size = 30.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
                LiveText(
                    "${sizeLabel(viewModel.freeBytes())} free",
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

            // ---- Recording now: a wide card each, across the top.
            if (now.isNotEmpty()) {
                SectionTitle("Recording Now", color = LIVE_RED)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    now.take(2).forEach { rec ->
                        RecordingNowCard(
                            rec = rec,
                            image = posters[rec.id] ?: rec.channelLogo,
                            isPoster = posters[rec.id] != null,
                            nowMs = nowMs,
                            watchModifier = if (rec.id == firstNowId) Modifier.focusRequester(first) else Modifier,
                            onWatch = { playNow(rec) },
                            onStop = { viewModel.stop(rec) },
                            onMenu = { menuFor = rec }
                        )
                    }
                    if (now.size > 2) {
                        LiveText("And ${now.size - 2} more recording now", size = 14.sp, color = colors.TextSecondary)
                    }
                }
                Spacer(Modifier.height(22.dp))
            }

            // ---- Scheduled · Series · Recorded, side by side.
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    SectionTitle("Scheduled")
                    if (schedules.isEmpty()) EmptyNote("Nothing scheduled")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(schedules, key = { it.id }) { s ->
                            InfoCard(
                                title = s.title + (s.episode?.let { "  ·  $it" } ?: ""),
                                line = s.channel.name + " · " + dateLabel(s.startMs),
                                note = if (s.useCatchup) "From catch-up after it ends" + (if (s.attempts > 0) " (trying again)" else "") else null,
                                modifier = if (s.id == firstScheduleId) Modifier.focusRequester(first) else Modifier,
                                onClick = { scheduleMenu = s }
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    SectionTitle("Series")
                    if (seriesRules.isEmpty()) EmptyNote("No series set to record")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        items(seriesRules, key = { it.id }) { r ->
                            InfoCard(
                                title = r.title,
                                line = r.channel.name + " · " + (if (r.keepLast > 0) "Keep last ${r.keepLast}" else "Keep all"),
                                note = null,
                                modifier = if (r.id == firstSeriesId) Modifier.focusRequester(first) else Modifier,
                                onClick = { seriesMenu = r }
                            )
                        }
                    }
                }
                Column(Modifier.weight(1.4f)) {
                    SectionTitle("Recorded")
                    if (done.isEmpty()) EmptyNote("Nothing recorded yet")
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 40.dp)
                    ) {
                        gridItems(done, key = { it.id }) { rec ->
                            RecordedCard(
                                rec = rec,
                                image = posters[rec.id] ?: rec.channelLogo,
                                isPoster = posters[rec.id] != null,
                                resumeFraction = resumeTick.let { viewModel.resume(rec) }?.let { ms ->
                                    if (rec.durationSec > 0) (ms / 1000.0 / rec.durationSec).toFloat().coerceIn(0f, 1f) else null
                                },
                                modifier = if (rec.id == firstDoneId) Modifier.focusRequester(first) else Modifier,
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
private fun SectionTitle(text: String, color: Color = NuvioTheme.colors.TextSecondary) {
    LiveText(
        text.uppercase(),
        size = 13.sp,
        weight = FontWeight.Bold,
        color = color,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun EmptyNote(text: String) {
    LiveText(text, size = 14.sp, color = NuvioTheme.colors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
}

/** A card's look: the guide's tile color, the theme's focus color and ring when highlighted. */
@Composable
private fun cardColors(focused: Boolean): Pair<Color, Color> {
    val colors = NuvioTheme.colors
    return (if (focused) colors.FocusBackground else com.nuvio.tv.livetv.ui.guideSurface()) to
        (if (focused) colors.FocusRing else Color.Transparent)
}

/** Show art (a poster, cropped) or the channel's logo (whole, on the tile color). */
@Composable
private fun Thumb(image: String?, isPoster: Boolean, modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {}) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(liveCorner()))
            .background(NuvioTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = if (isPoster) ContentScale.Crop else ContentScale.Fit,
                alignment = if (isPoster) Alignment.TopCenter else Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(if (isPoster) 0.dp else 14.dp)
            )
        }
        content()
    }
}

@Composable
private fun RecTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(LIVE_RED)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        LiveText("REC", size = 12.sp, weight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun ProgressLine(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** What's recording now: art, REC, title, channel and times, how far along, Watch and Stop. */
@Composable
private fun RecordingNowCard(
    rec: LiveTvRecording,
    image: String?,
    isPoster: Boolean,
    nowMs: Long,
    watchModifier: Modifier,
    onWatch: () -> Unit,
    onStop: () -> Unit,
    onMenu: () -> Unit
) {
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    var watchFocused by remember { mutableStateOf(false) }
    var stopFocused by remember { mutableStateOf(false) }
    val (bg, ring) = cardColors(watchFocused || stopFocused)
    val time = SimpleDateFormat("h:mm a", Locale.getDefault())
    val from = rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs
    val to = rec.programStopMs.takeIf { it > 0 } ?: rec.stopAtMs
    val fraction = if (rec.stopAtMs > rec.startedAtMs) ((nowMs - rec.startedAtMs).toFloat() / (rec.stopAtMs - rec.startedAtMs)) else 0f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(image, isPoster, Modifier.width(156.dp).fillMaxHeight())
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecTag()
                Spacer(Modifier.width(10.dp))
                LiveText(rec.title, size = 20.sp, weight = FontWeight.Bold, marquee = watchFocused || stopFocused, modifier = Modifier.weight(1f, fill = false))
            }
            val status = when {
                rec.durationSec <= 0 -> "Starting…" + (rec.problem?.let { " ($it)" } ?: "")
                else -> durationLabel(rec.durationSec) + " recorded" + (rec.problem?.let { " · $it" } ?: "")
            }
            LiveText(
                listOf(rec.channelName, time.format(Date(from)) + " – " + time.format(Date(to)), status).joinToString(" · "),
                size = 14.sp,
                color = colors.TextSecondary
            )
            ProgressLine(fraction, LIVE_RED, Modifier.fillMaxWidth(0.9f))
        }
        Spacer(Modifier.width(16.dp))
        PillButton("Watch", primary = true, modifier = watchModifier, onFocus = { watchFocused = it }, onClick = onWatch, onMenu = onMenu)
        Spacer(Modifier.width(10.dp))
        PillButton("Stop", primary = false, onFocus = { stopFocused = it }, onClick = onStop, onMenu = onMenu)
    }
}

@Composable
private fun PillButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(50)
    val bg = when {
        focused -> colors.TextPrimary
        primary -> colors.TextPrimary.copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val fg = if (focused || primary) colors.Background else colors.TextPrimary
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(bg)
            .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            )
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center
    ) {
        LiveText(label, size = 16.sp, weight = FontWeight.SemiBold, color = fg)
    }
}

/** A scheduled show or a series: title, and a line under it. */
@Composable
private fun InfoCard(
    title: String,
    line: String,
    note: String?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val (bg, ring) = cardColors(focused)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bg)
            .border(2.dp, ring, shape)
            .onFocusChanged { focused = it.isFocused }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        LiveText(title, size = 17.sp, weight = FontWeight.SemiBold, marquee = focused)
        LiveText(line, size = 14.sp, color = colors.TextSecondary)
        note?.let { LiveText(it, size = 12.sp, color = colors.TextTertiary) }
    }
}

/** A finished recording: art with how much you've watched, then title, day and length. */
@Composable
private fun RecordedCard(
    rec: LiveTvRecording,
    image: String?,
    isPoster: Boolean,
    resumeFraction: Float?,
    modifier: Modifier,
    onClick: () -> Unit,
    onMenu: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val colors = NuvioTheme.colors
    val shape = RoundedCornerShape(liveCorner())
    val failed = rec.status == RecordingStatus.FAILED
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) { onMenu(); true } else false
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onMenu
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Thumb(
            image, isPoster,
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .border(2.dp, if (focused) colors.FocusRing else Color.Transparent, shape)
        ) {
            resumeFraction?.let { f ->
                ProgressLine(
                    f, colors.Secondary,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)
                )
            }
        }
        LiveText(
            rec.title + (rec.episode?.let { " · $it" } ?: ""),
            size = 15.sp,
            weight = FontWeight.SemiBold,
            color = if (focused) colors.TextPrimary else colors.TextPrimary.copy(alpha = 0.9f),
            marquee = focused
        )
        LiveText(
            if (failed) rec.problem ?: "Couldn't be recorded"
            else listOfNotNull(
                shortDayLabel(rec.programStartMs.takeIf { it > 0 } ?: rec.startedAtMs),
                durationLabel(rec.durationSec).takeIf { rec.durationSec > 0 }
            ).joinToString(" · ") + (rec.problem?.let { " · $it" } ?: ""),
            size = 13.sp,
            color = if (failed) colors.Error else colors.TextSecondary
        )
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

private fun dateLabel(ms: Long): String {
    val day = shortDayLabel(ms)
    return day + ", " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
}

/** "Today", "Tomorrow", "Yesterday", the weekday within a week, else the date. */
private fun shortDayLabel(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    fun dayIndex(t: Long): Long {
        cal.timeInMillis = t
        return cal.get(java.util.Calendar.YEAR) * 400L + cal.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val diff = dayIndex(ms) - dayIndex(System.currentTimeMillis())
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff in -6L..6L -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(ms))
    }
}

private fun durationLabel(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}
