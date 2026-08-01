/*
 * M3Play Component Module 
 * Signature: M3PLAY::COMPONENT
 *
 * Adapted with premium ArchiveTune animations (Bounce, Glow, Liquid Sweep, Instrumental Breaks).
 * NO BLUR on inactive lines.
 */

package com.j.m3play.ui.component

import android.content.Intent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

import com.j.m3play.LocalPlayerConnection
import com.j.m3play.R
import com.j.m3play.constants.*
import com.j.m3play.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.j.m3play.lyrics.*
import com.j.m3play.lyrics.LyricsUtils.findCurrentLineIndex
import com.j.m3play.lyrics.LyricsUtils.isChinese
import com.j.m3play.lyrics.LyricsUtils.isJapanese
import com.j.m3play.lyrics.LyricsUtils.isKorean
import com.j.m3play.lyrics.LyricsUtils.isTtml
import com.j.m3play.lyrics.LyricsUtils.parseLyrics
import com.j.m3play.lyrics.LyricsUtils.parseTtml
import com.j.m3play.lyrics.LyricsUtils.romanizeJapanese
import com.j.m3play.lyrics.LyricsUtils.romanizeKorean
import com.j.m3play.ui.component.shimmer.*
import com.j.m3play.ui.utils.smoothFadingEdge
import com.j.m3play.utils.*

private const val LRC_LEAD_MS = 300L
private const val TTML_LEAD_MS = 0L
private const val LYRIC_VISUAL_TUNING_OFFSET_MS = 150L
private const val MANUAL_SCROLL_TIMEOUT_MS = 3000L
private val HEAD_LYRICS_ENTRY = LyricsEntry(time = 0L, text = "")

private const val ACCORD_INACTIVE_ALPHA = 0.35f

private fun isRtlText(text: String): Boolean {
    for (ch in text) {
        when (Character.getDirectionality(ch)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
            Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return false
        }
    }
    return false
}

// Extension fallback for LRC Bounce
private fun String.toLyricsWrappingUnits(): List<String> = this.split(Regex("(?<=\\s)"))

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LyricsV2(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val player = playerConnection.player
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    // ── Preferences ──
    val (lyricsClick) = rememberPreference(LyricsClickKey, defaultValue = true)
    val (lyricsTextSize) = rememberPreference(LyricsTextSizeKey, defaultValue = 34f)
    val (lyricsLineSpacing) = rememberPreference(LyricsLineSpacingKey, defaultValue = 1.3f)
    val (romanizeJapanese) = rememberPreference(LyricsRomanizeJapaneseKey, defaultValue = true)
    val (romanizeKorean) = rememberPreference(LyricsRomanizeKoreanKey, defaultValue = true)
    val (useSystemFont) = rememberPreference(UseSystemFontKey, defaultValue = false)
    
    // Default ArchiveTune styling parameters
    val bounceFactor = 1f
    val glowFactor = 1f
    val fillTransitionWidth = 8f
    val lrcBounceEnabled = true

    val lyricsFontFamily = remember(useSystemFont) { if (useSystemFont) null else FontFamily(Font(R.font.sfprodisplaybold)) }
    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, PlayerBackgroundStyle.DEFAULT)
    val textColor = if (playerBackground == PlayerBackgroundStyle.DEFAULT) MaterialTheme.colorScheme.onBackground else Color.White

    // ── Selection mode state ──
    var isSelectionModeActive by rememberSaveable { mutableStateOf(false) }
    val selectedIndices = remember { mutableStateListOf<Int>() }
    var showMaxSelectionToast by remember { mutableStateOf(false) }
    val maxSelectionLimit = 5
    var showShareDialog by remember { mutableStateOf(false) }
    var shareDialogData by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var showColorPickerDialog by remember { mutableStateOf(false) }
    var selectedGlassStyle by remember { mutableStateOf(LyricsGlassStyle.FrostedDark) }
    var paletteGlassStyle by remember { mutableStateOf<LyricsGlassStyle?>(null) }
    var showProgressDialog by remember { mutableStateOf(false) }

    LaunchedEffect(showMaxSelectionToast) {
        if (showMaxSelectionToast) {
            Toast.makeText(context, context.getString(R.string.max_selection_limit, maxSelectionLimit), Toast.LENGTH_SHORT).show()
            showMaxSelectionToast = false
        }
    }

    // ── Lyrics data ──
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    val lyrics = currentLyrics?.lyrics
    val isSynced = remember(lyrics) { lyrics != null && (lyrics.startsWith("[") || isTtml(lyrics)) }
    val isTtmlFormat = remember(lyrics) { lyrics != null && isTtml(lyrics) }

    val lyricsEntries: List<LyricsEntry> = remember(lyrics, currentLyrics?.provider) {
        if (lyrics == null || lyrics == LYRICS_NOT_FOUND) return@remember emptyList()
        val parsed = when {
            isTtml(lyrics) -> parseTtml(lyrics)
            lyrics.startsWith("[") -> parseLyrics(lyrics)
            else -> lyrics.lines().filter { it.isNotBlank() }.mapIndexed { _, line -> LyricsEntry(time = -1L, text = line.trim()) }
        }
        val providerName = currentLyrics?.provider?.uppercase() ?: "UNKNOWN"
        val providerEntry = LyricsEntry(time = 0L, text = "✨ Provided by $providerName")
        if (parsed.isNotEmpty() && parsed.first().time >= 0) listOf(HEAD_LYRICS_ENTRY, providerEntry) + parsed else listOf(HEAD_LYRICS_ENTRY, providerEntry) + parsed
    }

    val entriesWithWords: List<LyricsEntry> = remember(lyricsEntries) {
        if (lyricsEntries.isEmpty()) emptyList() else {
            lyricsEntries.mapIndexed { index, entry ->
                if (entry.words != null || entry.time < 0 || entry.text.isBlank()) entry else {
                    val nextEntryTime = if (index < lyricsEntries.lastIndex) lyricsEntries[index + 1].time else entry.time + 5000L
                    val lineDurationMs = (nextEntryTime - entry.time).coerceAtLeast(500L)
                    val lineStartSec = entry.time / 1000.0
                    val isCjkText = isJapanese(entry.text) || isChinese(entry.text) || isKorean(entry.text)
                    val tokens = if (isCjkText) {
                        val chars = mutableListOf<String>()
                        var currentWord = StringBuilder()
                        entry.text.forEach { char ->
                            if (char.isWhitespace()) {
                                if (currentWord.isNotEmpty()) { chars.add(currentWord.toString()); currentWord.clear() }
                                chars.add(char.toString())
                            } else if (isJapanese(char.toString()) || isChinese(char.toString()) || isKorean(char.toString())) {
                                if (currentWord.isNotEmpty()) { chars.add(currentWord.toString()); currentWord.clear() }
                                chars.add(char.toString())
                            } else currentWord.append(char)
                        }
                        if (currentWord.isNotEmpty()) chars.add(currentWord.toString())
                        val groupedTokens = mutableListOf<String>()
                        chars.forEachIndexed { _, c -> if (c.isBlank()) { if (groupedTokens.isNotEmpty()) groupedTokens[groupedTokens.lastIndex] = groupedTokens.last() + c } else groupedTokens.add(c) }
                        groupedTokens
                    } else entry.text.split(Regex("\\s+"))
                    
                    if (tokens.isEmpty()) entry else {
                        val totalChars = tokens.sumOf { it.length }.coerceAtLeast(1)
                        val words = mutableListOf<WordTimestamp>()
                        var currentOffsetMs = 0.0
                        tokens.forEachIndexed { wordIdx, token ->
                            val weight = token.length.toDouble() / totalChars
                            val wordDurMs = lineDurationMs * weight
                            val wordStartSec = lineStartSec + (currentOffsetMs / 1000.0)
                            val wordEndSec = wordStartSec + (wordDurMs / 1000.0)
                            val wordText = if (wordIdx < tokens.lastIndex && !isCjkText) "$token " else token
                            words.add(WordTimestamp(text = wordText, startTime = wordStartSec, endTime = wordEndSec))
                            currentOffsetMs += wordDurMs
                        }
                        entry.copy(words = words)
                    }
                }
            }
        }
    }

    LaunchedEffect(entriesWithWords, romanizeJapanese, romanizeKorean) {
        if (romanizeJapanese || romanizeKorean) {
            entriesWithWords.forEach { entry ->
                if (entry.text.isNotBlank() && entry.romanizedTextFlow.value == null) {
                    scope.launch(Dispatchers.Default) {
                        val romanized = when {
                            romanizeJapanese && isJapanese(entry.text) -> romanizeJapanese(entry.text)
                            romanizeKorean && isKorean(entry.text) -> romanizeKorean(entry.text)
                            else -> null
                        }
                        if (romanized != null) entry.romanizedTextFlow.value = romanized
                    }
                }
            }
        }
    }

    // ── Playback position tracking ──
    val leadMs = if (isTtmlFormat) TTML_LEAD_MS else LRC_LEAD_MS
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var playbackPositionMs by remember { mutableLongStateOf(0L) }
    var currentLineIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(entriesWithWords, isSynced) {
        if (!isSynced || entriesWithWords.isEmpty()) return@LaunchedEffect
        val pollIntervalMs = if (isTtmlFormat) 16L else 50L
        while (isActive) {
            val sliderPos = sliderPositionProvider()
            val pos = sliderPos ?: player.currentPosition

            playbackPositionMs = pos.coerceAtLeast(0L)
            currentPositionMs = (playbackPositionMs + leadMs + LYRIC_VISUAL_TUNING_OFFSET_MS).coerceAtLeast(0L)

            currentLineIndex = findCurrentLineIndex(entriesWithWords, currentPositionMs, 0L)
            delay(pollIntervalMs)
        }
    }

    // ── Scroll State ──
    val listState = rememberLazyListState()
    var isManualScrolling by remember { mutableStateOf(false) }
    var lastManualScrollTime by remember { mutableLongStateOf(0L) }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!isSelectionModeActive && source == NestedScrollSource.UserInput) {
                    isManualScrolling = true
                    lastManualScrollTime = System.currentTimeMillis()
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(isManualScrolling, lastManualScrollTime) {
        if (isManualScrolling) {
            delay(MANUAL_SCROLL_TIMEOUT_MS)
            isManualScrolling = false
        }
    }

    LaunchedEffect(currentLineIndex, isManualScrolling) {
        if (isManualScrolling || !isSynced) return@LaunchedEffect
        if (currentLineIndex < 0 || currentLineIndex >= entriesWithWords.size) return@LaunchedEffect

        val visibleInfo = listState.layoutInfo
        val viewportHeight = visibleInfo.viewportSize.height
        val targetOffset = (viewportHeight * 0.35f).toInt() 

        val distance = abs(currentLineIndex - (listState.firstVisibleItemIndex))
        if (distance > 15) {
            listState.scrollToItem((currentLineIndex - 2).coerceAtLeast(0), 0)
        }
        listState.animateScrollToItem(index = currentLineIndex, scrollOffset = -targetOffset)
    }

    BackHandler(enabled = isSelectionModeActive) {
        isSelectionModeActive = false
        selectedIndices.clear()
    }

    val activity = context as? android.app.Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ── Render UI ──
    BoxWithConstraints(contentAlignment = Alignment.TopCenter, modifier = modifier.fillMaxSize().padding(bottom = 12.dp)) {
        
        if (lyrics == LYRICS_NOT_FOUND || entriesWithWords.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { 
                Text(text = stringResource(R.string.lyrics_not_found), style = MaterialTheme.typography.bodyLarge, color = textColor.copy(alpha = 0.6f)) 
            }
            return@BoxWithConstraints
        }
        if (lyrics == null) {
            ShimmerHost { repeat(6) { TextPlaceholder() } }
            return@BoxWithConstraints
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .smoothFadingEdge(vertical = 80.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            itemsIndexed(
                items = entriesWithWords,
                key = { index, entry -> "${index}_${entry.time}" }
            ) { index, item ->
                if (item == HEAD_LYRICS_ENTRY) {
                    Spacer(modifier = Modifier.height(120.dp))
                    return@itemsIndexed
                }

                // ── Instrumental break icon ──
                val isInst = item.text.isBlank() && item.time > 0
                if (isInst && isSynced) {
                    val durationMs = 5000L // Fallback if duration is unknown
                    val startTimeMs = item.time
                    val endTimeMs = item.time + durationMs
                    val isActive = playbackPositionMs in startTimeMs until endTimeMs
                    val distanceFromActive = abs(index - currentLineIndex)
                    
                    val instrAlpha = when {
                        isActive -> 1f
                        isManualScrolling -> when {
                            distanceFromActive == 1 -> 0.72f
                            distanceFromActive == 2 -> 0.56f
                            distanceFromActive == 3 -> 0.40f
                            else -> 0.28f
                        }
                        distanceFromActive == 1 -> 0.52f
                        distanceFromActive == 2 -> 0.30f
                        distanceFromActive == 3 -> 0.18f
                        else -> ACCORD_INACTIVE_ALPHA
                    }
                    val animatedInstrAlpha by animateFloatAsState(targetValue = instrAlpha, animationSpec = tween(durationMillis = if (isActive) 330 else 500, easing = FastOutSlowInEasing), label = "v2InstrumentalAlpha")
                    val animatedInstrScale by animateFloatAsState(targetValue = if (isActive) 1f else 0.95f, animationSpec = tween(durationMillis = 166, easing = FastOutSlowInEasing), label = "v2InstrumentalScale")
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = 12.dp, end = 12.dp,
                                top = if (index == 0 || (index == 1 && entriesWithWords[0] == HEAD_LYRICS_ENTRY)) 0.dp else (lyricsLineSpacing * 8).dp,
                                bottom = (lyricsLineSpacing * 8).dp,
                            )
                            // REMOVED BLUR MODIFIER HERE
                            .graphicsLayer {
                                scaleX = animatedInstrScale
                                scaleY = animatedInstrScale
                                alpha = animatedInstrAlpha
                            }
                            .then(if (lyricsClick && item.time > 0) Modifier.clickable { player.seekTo(item.time) } else Modifier)
                    ) {
                        InstrumentalBreakItem(
                            durationMs = durationMs,
                            currentPositionMs = playbackPositionMs,
                            startTimeMs = startTimeMs,
                            textColor = textColor,
                            inactiveAlpha = ACCORD_INACTIVE_ALPHA,
                        )
                    }
                    return@itemsIndexed
                }

                // ── Standard Line Render ──
                val textAlign = when (item.agent?.lowercase()) { "v1", null -> TextAlign.Start; "v2" -> TextAlign.End; else -> TextAlign.Center }
                val horizontalAlignment = when (item.agent?.lowercase()) { "v1", null -> Alignment.Start; "v2" -> Alignment.End; else -> Alignment.CenterHorizontally }
                
                val isActive = isSynced && index == currentLineIndex
                val isPast = isSynced && index < currentLineIndex
                val isSelected = selectedIndices.contains(index)
                val distanceFromActive = if (isSynced) abs(index - currentLineIndex) else 0
                
                val lineAlpha = when {
                    !isSynced -> 0.92f
                    isActive -> 1f
                    isManualScrolling -> when {
                        distanceFromActive == 1 -> 0.72f
                        distanceFromActive == 2 -> 0.56f
                        distanceFromActive == 3 -> 0.40f
                        else -> 0.28f
                    }
                    distanceFromActive == 1 -> 0.52f
                    distanceFromActive == 2 -> 0.30f
                    distanceFromActive == 3 -> 0.18f
                    else -> 0.10f
                }
                
                val animatedLineScale by animateFloatAsState(targetValue = if (isActive) 1f else 0.95f, animationSpec = tween(durationMillis = 166, easing = FastOutSlowInEasing), label = "v2LineScale")
                val animatedLineAlpha by animateFloatAsState(targetValue = lineAlpha, animationSpec = tween(durationMillis = if (isActive) 330 else 500, easing = FastOutSlowInEasing), label = "v2LineAlpha")
                val lineTransformOrigin = remember(item.agent) { when (item.agent?.lowercase()) { "v2" -> TransformOrigin(1f, 0.5f); "v1", null -> TransformOrigin(0f, 0.5f); else -> TransformOrigin(0.5f, 0.5f) } }

                val isAllBackground = item.words?.all { it.isBackground || it.text.isBlank() } == true
                val baseLayoutDirection = LocalLayoutDirection.current
                val lineText = remember(item.text, item.words) { item.words?.joinToString("") { it.text }?.takeIf { it.isNotBlank() } ?: item.text }
                val lineIsRtl = remember(lineText) { isRtlText(lineText) }
                val lineLayoutDirection = remember(lineIsRtl, baseLayoutDirection) { if (lineIsRtl) LayoutDirection.Rtl else baseLayoutDirection }

                CompositionLocalProvider(LocalLayoutDirection provides lineLayoutDirection) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = if (isSelected && isSelectionModeActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(
                                start = if (isAllBackground) 24.dp else 12.dp, end = 12.dp,
                                top = if (index <= 1) 0.dp else (lyricsLineSpacing * 8).dp,
                                bottom = (lyricsLineSpacing * 8).dp
                            )
                            // REMOVED BLUR MODIFIER HERE
                            .graphicsLayer {
                                scaleX = animatedLineScale
                                scaleY = animatedLineScale
                                alpha = animatedLineAlpha
                                transformOrigin = lineTransformOrigin
                            }
                            .combinedClickable(
                                enabled = true,
                                onClick = {
                                    if (isSelectionModeActive) {
                                        if (isSelected) {
                                            selectedIndices.remove(index)
                                            if (selectedIndices.isEmpty()) isSelectionModeActive = false
                                        } else {
                                            if (selectedIndices.size < maxSelectionLimit) selectedIndices.add(index) else showMaxSelectionToast = true
                                        }
                                    } else if (lyricsClick && isSynced && item.time > 0) {
                                        player.seekTo(item.time)
                                    }
                                },
                                onLongClick = {
                                    if (!isSelectionModeActive) {
                                        isSelectionModeActive = true; selectedIndices.add(index)
                                    } else if (!isSelected && selectedIndices.size < maxSelectionLimit) {
                                        selectedIndices.add(index)
                                    } else if (!isSelected) showMaxSelectionToast = true
                                }
                            ),
                        horizontalAlignment = horizontalAlignment,
                    ) {
                        val romanizedText = item.romanizedTextFlow.collectAsState().value
                        if (romanizedText != null) {
                            Text(text = romanizedText, style = MaterialTheme.typography.bodyMedium.copy(fontSize = (lyricsTextSize * 0.55f).sp, lineHeight = (lyricsTextSize * 0.75f).sp, fontWeight = FontWeight.Normal, fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.bodyMedium.fontFamily), color = textColor.copy(alpha = if (isActive) 0.76f else 0.42f), textAlign = textAlign, modifier = Modifier.fillMaxWidth().padding(bottom = (lyricsTextSize * 0.18f).dp))
                        }

                        if (item.words != null && isSynced) {
                            LyricsLineV2(
                                words = item.words!!,
                                isActive = isActive,
                                isPast = isPast,
                                currentPositionMs = currentPositionMs,
                                textColor = textColor,
                                inactiveAlpha = ACCORD_INACTIVE_ALPHA,
                                baseFontSize = lyricsTextSize,
                                isLineAllBackground = isAllBackground,
                                textAlign = textAlign,
                                lyricsFontFamily = lyricsFontFamily,
                                isRtl = lineIsRtl,
                                bounceFactor = bounceFactor,
                                glowFactor = glowFactor,
                                fillTransitionWidth = fillTransitionWidth
                            )
                        } else if (isSynced) {
                            LyricsLineLrcBounce(
                                text = item.text,
                                isActive = isActive,
                                textColor = textColor.copy(alpha = if (isActive) 1f else 0.52f),
                                fontSize = lyricsTextSize,
                                lineSpacing = lyricsLineSpacing,
                                isAllBackground = isAllBackground,
                                lyricsFontFamily = lyricsFontFamily,
                                textAlign = textAlign,
                                bounceFactor = if (lrcBounceEnabled) bounceFactor else 0f
                            )
                        } else {
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (isAllBackground) (lyricsTextSize * 0.82f).sp else lyricsTextSize.sp, fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold, fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal, lineHeight = (lyricsTextSize * lyricsLineSpacing).sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily),
                                color = textColor.copy(alpha = if (isActive) 1f else 0.52f), textAlign = textAlign, modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(300.dp)) }
        }

        // ── Resume auto-scroll button ──
        AnimatedVisibility(
            visible = isManualScrolling && !isSelectionModeActive,
            enter = slideInVertically(animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing), initialOffsetY = { it * 2 }) + fadeIn(animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing), targetOffsetY = { it * 2 }) + fadeOut(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .background(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp))
                    .clickable { 
                        isManualScrolling = false
                        scope.launch {
                            val viewportHeight = listState.layoutInfo.viewportSize.height
                            listState.animateScrollToItem(index = currentLineIndex, scrollOffset = -(viewportHeight * 0.35f).toInt())
                        } 
                    }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(painter = painterResource(id = R.drawable.play), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text(text = stringResource(R.string.resume_autoscroll), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }

        // ── Selection Dialogs (Share Features) ──
        if (isSelectionModeActive) {
            mediaMetadata?.let { metadata ->
                Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(48.dp).background(color = Color.Black.copy(alpha = 0.3f), shape = CircleShape).clickable { isSelectionModeActive = false; selectedIndices.clear() },
                            contentAlignment = Alignment.Center
                        ) { Icon(painter = painterResource(id = R.drawable.close), contentDescription = stringResource(R.string.cancel), tint = Color.White, modifier = Modifier.size(20.dp)) }

                        Row(
                            modifier = Modifier
                                .background(color = if (selectedIndices.isNotEmpty()) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.5f), shape = RoundedCornerShape(24.dp))
                                .clickable(enabled = selectedIndices.isNotEmpty()) {
                                    if (selectedIndices.isNotEmpty()) {
                                        val selectedLyricsText = selectedIndices.sorted().mapNotNull { entriesWithWords.getOrNull(it)?.text }.joinToString("\n")
                                        shareDialogData = Triple(selectedLyricsText, metadata.title ?: "", metadata.artists.joinToString { it.name })
                                        showShareDialog = true
                                    }
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(painter = painterResource(id = R.drawable.share), contentDescription = stringResource(R.string.share_selected), tint = Color.Black, modifier = Modifier.size(20.dp))
                            Text(text = "Share (${selectedIndices.size})", color = Color.Black, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }

    if (showProgressDialog) {
        BasicAlertDialog(onDismissRequest = {  }) {
            Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)) {
                Box(modifier = Modifier.padding(32.dp)) { Text(text = stringResource(R.string.generating_image) + "\n" + stringResource(R.string.please_wait), color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }

    if (showShareDialog && shareDialogData != null) {
        val (plainLyricsText, songTitle, artists) = shareDialogData!! 
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showShareDialog = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 48.dp, top = 8.dp)) {
                Text(text = stringResource(R.string.share_lyrics), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 24.dp))

                Card(
                    onClick = {
                        val shareIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            type = "text/plain"
                            val songLink = "https://music.youtube.com/watch?v=${mediaMetadata?.id}"
                            putExtra(Intent.EXTRA_TEXT, "\"$plainLyricsText\"\n\n$songTitle - $artists\n$songLink")
                        }
                        context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_lyrics)))
                        showShareDialog = false
                        isSelectionModeActive = false
                        selectedIndices.clear()
                    },
                    shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(id = R.drawable.share), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = stringResource(R.string.share_as_text), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }

                Card(
                    onClick = { showColorPickerDialog = true; showShareDialog = false },
                    shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painter = painterResource(id = R.drawable.share), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(text = stringResource(R.string.share_as_image), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showColorPickerDialog && shareDialogData != null) {
        val (_, songTitle, artists) = shareDialogData!!
        val coverUrl = mediaMetadata?.thumbnailUrl

        var selectedAspectRatio by remember { mutableFloatStateOf(1f) } 
        var selectedTextAlign by remember { mutableStateOf(TextAlign.Center) }
        var customBlur by remember { mutableFloatStateOf(-1f) } 
        var customDarkness by remember { mutableFloatStateOf(-1f) } 
        var textScale by remember { mutableFloatStateOf(1f) }
        var fontStyle by remember { mutableIntStateOf(0) }
        var bgMode by remember { mutableIntStateOf(0) }
        var textGlow by remember { mutableStateOf(false) }
        var showWatermark by remember { mutableStateOf(true) }
        var showBarcode by remember { mutableStateOf(true) }
        var showTrackInfo by remember { mutableStateOf(true) } 
        var showRomanized by remember { mutableStateOf(false) }

        val displayLyricsText = remember(selectedIndices.toList(), showRomanized) {
            selectedIndices.sorted().mapNotNull { i ->
                val entry = entriesWithWords.getOrNull(i)
                val text = entry?.text ?: ""
                val rom = entry?.romanizedTextFlow?.value
                if (showRomanized && !rom.isNullOrBlank()) "$text\n$rom" else text
            }.joinToString("\n")
        }

        LaunchedEffect(coverUrl) {
            if (coverUrl != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val loader = ImageLoader(context)
                        val req = ImageRequest.Builder(context).data(coverUrl).allowHardware(false).build()
                        val result = loader.execute(req)
                        val bmp = result.image?.toBitmap()
                        if (bmp != null) {
                            val palette = Palette.from(bmp).generate()
                            paletteGlassStyle = LyricsGlassStyle.fromPalette(palette)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        val availableStyles = remember(paletteGlassStyle) {
            val base = LyricsGlassStyle.allPresets.toMutableList()
            paletteGlassStyle?.let { base.add(0, it) }
            base
        }

        val colorPickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showColorPickerDialog = false },
            sheetState = colorPickerSheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)
            ) {
                Text(text = "Design Options", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))

                Box(modifier = Modifier.fillMaxWidth().height(380.dp), contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.fillMaxHeight().aspectRatio(selectedAspectRatio)) {
                        LyricsImageCard(
                            lyricText = displayLyricsText, mediaMetadata = mediaMetadata ?: return@Box, glassStyle = selectedGlassStyle, aspectRatio = selectedAspectRatio, textAlign = selectedTextAlign, customBlur = if (customBlur < 0f) null else customBlur.toInt(), showWatermark = showWatermark, showTrackInfo = showTrackInfo, textScale = textScale, customDarkness = if (customDarkness < 0f) null else customDarkness, fontStyle = fontStyle, bgMode = bgMode, textGlow = textGlow, showBarcode = showBarcode
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Button(
                    onClick = {
                        showColorPickerDialog = false
                        showProgressDialog = true
                        scope.launch {
                            try {
                                val exportWidth = 1080
                                val exportHeight = (exportWidth / selectedAspectRatio).toInt()
                                val image = ComposeToImage.createLyricsImage(
                                    context = context, coverArtUrl = coverUrl, songTitle = songTitle, artistName = artists, lyrics = displayLyricsText, width = exportWidth, height = exportHeight, glassStyle = selectedGlassStyle, aspectRatio = selectedAspectRatio, textAlign = selectedTextAlign, customBlur = if (customBlur < 0f) null else customBlur.toInt(), showWatermark = showWatermark, showTrackInfo = showTrackInfo, textScale = textScale, customDarkness = if (customDarkness < 0f) null else customDarkness, fontStyle = fontStyle, bgMode = bgMode, textGlow = textGlow, showBarcode = showBarcode
                                )
                                val uri = ComposeToImage.saveBitmapAsFile(context, image, "lyrics_${System.currentTimeMillis()}")
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share Lyrics"))
                                isSelectionModeActive = false
                                selectedIndices.clear()
                            } catch (e: Exception) { Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_SHORT).show() } finally { showProgressDialog = false }
                        }
                    },
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(text = stringResource(id = R.string.share), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Line-level composable: renders words with fluid fill animation
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineV2(
    words: List<WordTimestamp>,
    isActive: Boolean,
    isPast: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    baseFontSize: Float,
    isLineAllBackground: Boolean,
    textAlign: TextAlign,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
    fillTransitionWidth: Float,
) {
    val arrangement = when (textAlign) { TextAlign.Center -> Arrangement.Center; TextAlign.End -> Arrangement.End; else -> Arrangement.Start }
    val mainWords = words.filter { !it.isBackground }
    val bgWords = words.filter { it.isBackground }

    if (mainWords.isNotEmpty()) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = arrangement) {
            mainWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(text = " ", style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (isLineAllBackground) (baseFontSize * 0.82f).sp else baseFontSize.sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily), color = Color.Transparent)
                    return@forEachIndexed
                }
                if (word.text == "\n") { Spacer(modifier = Modifier.fillMaxWidth()); return@forEachIndexed }
                AnimatedWordV2(
                    word = word, wordIndex = wordIndex, isLineActive = isActive, isLinePast = isPast, currentPositionMs = currentPositionMs, textColor = textColor, inactiveAlpha = inactiveAlpha, fontSize = if (isLineAllBackground) baseFontSize * 0.82f else baseFontSize, isBackground = isLineAllBackground, lyricsFontFamily = lyricsFontFamily, isRtl = isRtl, bounceFactor = bounceFactor, glowFactor = glowFactor, fillTransitionWidth = fillTransitionWidth,
                )
            }
        }
    }
    if (bgWords.isNotEmpty()) {
        val spacerHeight = if (mainWords.isNotEmpty()) 4.dp else 0.dp
        if (mainWords.isNotEmpty()) Spacer(modifier = Modifier.height(spacerHeight))
        FlowRow(modifier = Modifier.fillMaxWidth().alpha(0.85f), horizontalArrangement = arrangement) {
            bgWords.forEachIndexed { wordIndex, word ->
                if (word.text == " ") {
                    Text(text = " ", style = MaterialTheme.typography.headlineMedium.copy(fontSize = (baseFontSize * 0.65f).sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily), color = Color.Transparent)
                    return@forEachIndexed
                }
                AnimatedWordV2(
                    word = word, wordIndex = wordIndex + mainWords.size, isLineActive = isActive, isLinePast = isPast, currentPositionMs = currentPositionMs, textColor = textColor, inactiveAlpha = inactiveAlpha, fontSize = baseFontSize * 0.65f, isBackground = true, lyricsFontFamily = lyricsFontFamily, isRtl = isRtl, bounceFactor = bounceFactor, glowFactor = glowFactor, fillTransitionWidth = fillTransitionWidth,
                )
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// Word-level composable: liquid fill sweep + glow + bounce
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun AnimatedWordV2(
    word: WordTimestamp,
    wordIndex: Int,
    isLineActive: Boolean,
    isLinePast: Boolean,
    currentPositionMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
    fontSize: Float,
    isBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    isRtl: Boolean,
    bounceFactor: Float,
    glowFactor: Float,
    fillTransitionWidth: Float,
) {
    val wordStartMs = (word.startTime * 1000).toLong()
    val wordEndMs = (word.endTime * 1000).toLong()
    val wordDuration = (wordEndMs - wordStartMs).coerceAtLeast(1L)
    val isWordComplete = currentPositionMs >= wordEndMs
    val isWordActive = currentPositionMs in wordStartMs until wordEndMs
    val progress = when {
        isWordComplete -> 1f
        currentPositionMs <= wordStartMs -> 0f
        else -> ((currentPositionMs - wordStartMs).toFloat() / wordDuration).coerceIn(0f, 1f)
    }

    val sinProgress = kotlin.math.sin(progress * kotlin.math.PI).toFloat()
    val wordScale = 1f + (0.015f * bounceFactor * sinProgress)
    val targetFloat = if (isWordActive) -4f * bounceFactor * sinProgress else 0f
    
    val floatOffset by animateFloatAsState(targetValue = targetFloat, animationSpec = tween(durationMillis = if (isWordActive) 50 else 350, easing = FastOutSlowInEasing), label = "v2FloatOffset")

    val glowProgress = (progress * 2f).coerceAtMost(1f)
    val glowAlpha = if (isWordActive) glowProgress * 0.45f * glowFactor else 0f
    val glowRadius = if (isWordActive) glowProgress * 12f * glowFactor else 0f

    val actualFontSize = if (isBackground) fontSize * 0.85f else fontSize
    val fontWeight = if (isLineActive || isLinePast) FontWeight.ExtraBold else FontWeight.SemiBold
    val glowPadding = 10.dp

    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val glowPaddingPx = glowPadding.roundToPx()
                val looseConstraints = constraints.copy(minWidth = 0, maxWidth = constraints.maxWidth, minHeight = 0, maxHeight = Constraints.Infinity)
                val placeable = measurable.measure(looseConstraints)
                val coreWidth = (placeable.width - glowPaddingPx * 2).coerceAtLeast(0)
                val coreHeight = (placeable.height - glowPaddingPx * 2).coerceAtLeast(0)
                layout(coreWidth, coreHeight) { placeable.place(-glowPaddingPx, -glowPaddingPx) }
            }.graphicsLayer {
                clip = false
                translationY = floatOffset * density
                scaleX = wordScale
                scaleY = wordScale
            },
    ) {
        Text(
            text = word.text,
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = actualFontSize.sp, fontWeight = fontWeight, fontStyle = FontStyle.Normal, lineHeight = (actualFontSize * 1.35f).sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily),
            color = textColor.copy(alpha = if (isBackground) inactiveAlpha * 0.7f else inactiveAlpha), modifier = Modifier.padding(glowPadding),
        )

        if (isWordComplete || isWordActive || isLinePast) {
            Text(
                text = word.text,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = actualFontSize.sp, fontWeight = fontWeight, fontStyle = FontStyle.Normal, lineHeight = (actualFontSize * 1.35f).sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily,
                    shadow = if (glowAlpha > 0f) Shadow(color = textColor.copy(alpha = glowAlpha), offset = Offset.Zero, blurRadius = glowRadius.coerceAtLeast(1f)) else null
                ),
                color = textColor.copy(alpha = if (isBackground) 0.75f else 1f),
                modifier = if (isWordActive && !isWordComplete) {
                    Modifier
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val edgeWidth = fillTransitionWidth.dp.toPx()
                            val center = if (isRtl) size.width - ((size.width + edgeWidth * 2) * progress - edgeWidth) else (size.width + edgeWidth * 2) * progress - edgeWidth
                            drawRect(
                                brush = Brush.horizontalGradient(colors = if (isRtl) listOf(Color.Transparent, Color.Black) else listOf(Color.Black, Color.Transparent), startX = center - edgeWidth, endX = center + edgeWidth),
                                blendMode = BlendMode.DstIn,
                            )
                        }.padding(glowPadding)
                } else Modifier.padding(glowPadding),
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────────────
// LRC bounce: word-by-word spring bounce for line-synced lyrics
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsLineLrcBounce(
    text: String,
    isActive: Boolean,
    textColor: Color,
    fontSize: Float,
    lineSpacing: Float,
    isAllBackground: Boolean,
    lyricsFontFamily: FontFamily?,
    textAlign: TextAlign,
    bounceFactor: Float,
) {
    val words = remember(text) { text.toLyricsWrappingUnits() }
    val effectiveFontSize = if (isAllBackground) fontSize * 0.82f else fontSize
    val fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.SemiBold
    val fontStyle = if (isAllBackground) FontStyle.Italic else FontStyle.Normal
    val scaleAnimatables = remember(words.size) { List(words.size) { Animatable(1f) } }
    val floatAnimatables = remember(words.size) { List(words.size) { Animatable(0f) } }

    LaunchedEffect(isActive) {
        if (!isActive || bounceFactor == 0f) return@LaunchedEffect
        words.indices.forEach { i ->
            launch {
                delay(i * 40L)
                try {
                    scaleAnimatables[i].animateTo(targetValue = 1f + 0.045f * bounceFactor, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh))
                    scaleAnimatables[i].animateTo(targetValue = 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                } finally { withContext(NonCancellable) { scaleAnimatables[i].snapTo(1f) } }
            }
            launch {
                delay(i * 40L)
                try {
                    floatAnimatables[i].animateTo(targetValue = -5f * bounceFactor, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh))
                    floatAnimatables[i].animateTo(targetValue = 0f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow))
                } finally { withContext(NonCancellable) { floatAnimatables[i].snapTo(0f) } }
            }
        }
    }

    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = when (textAlign) { TextAlign.Center -> Arrangement.Center; TextAlign.End -> Arrangement.End; else -> Arrangement.Start }) {
        words.forEachIndexed { i, word ->
            LrcBouncingWord(text = word, scaleAnim = scaleAnimatables[i], floatAnim = floatAnimatables[i], color = textColor, fontSize = effectiveFontSize, lineSpacing = lineSpacing, fontWeight = fontWeight, fontStyle = fontStyle, lyricsFontFamily = lyricsFontFamily)
        }
    }
}

@Composable
private fun LrcBouncingWord(
    text: String, scaleAnim: Animatable<Float, AnimationVector1D>, floatAnim: Animatable<Float, AnimationVector1D>, color: Color, fontSize: Float, lineSpacing: Float, fontWeight: FontWeight, fontStyle: FontStyle, lyricsFontFamily: FontFamily?,
) {
    Text(
        text = text, style = MaterialTheme.typography.headlineMedium.copy(fontSize = fontSize.sp, fontWeight = fontWeight, fontStyle = fontStyle, lineHeight = (fontSize * lineSpacing).sp, fontFamily = lyricsFontFamily ?: MaterialTheme.typography.headlineMedium.fontFamily),
        color = color, modifier = Modifier.graphicsLayer { scaleX = scaleAnim.value; scaleY = scaleAnim.value; translationY = floatAnim.value },
    )
}

// ──────────────────────────────────────────────────────────────────────
// Instrumental break icon: music-note filled bottom-to-top over the gap
// ──────────────────────────────────────────────────────────────────────

@Composable
private fun InstrumentalBreakItem(
    durationMs: Long,
    currentPositionMs: Long,
    startTimeMs: Long,
    textColor: Color,
    inactiveAlpha: Float,
) {
    val musicNotePath = remember { androidx.compose.ui.graphics.vector.PathParser().parsePathString("M10 21q-1.65 0-2.825-1.175T6 17t1.175-2.825T10 13q.575 0 1.063.138t.937.412V4q0-.425.288-.712T13 3h4q.425 0 .713.288T18 4v2q0 .425-.288.713T17 7h-3v10q0 1.65-1.175 2.825T10 21").toPath() }
    val targetFillFraction = when {
        durationMs <= 0L -> 0f
        currentPositionMs <= startTimeMs -> 0f
        currentPositionMs >= startTimeMs + durationMs -> 1f
        else -> ((currentPositionMs - startTimeMs).toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    }
    val fillFraction by animateFloatAsState(targetValue = targetFillFraction, animationSpec = spring(stiffness = Spring.StiffnessHigh, dampingRatio = Spring.DampingRatioNoBouncy), label = "instrumentalFill")

    androidx.compose.foundation.Canvas(modifier = Modifier.size(48.dp)) {
        val scaleX = size.width / 24f
        val scaleY = size.height / 24f
        val pivot = Offset.Zero
        withTransform(transformBlock = { scale(scaleX, scaleY, pivot) }) { drawPath(path = musicNotePath, color = textColor.copy(alpha = inactiveAlpha)) }
        if (fillFraction > 0f) {
            val clipTop = size.height * (1f - fillFraction)
            clipRect(left = 0f, top = clipTop, right = size.width, bottom = size.height) {
                withTransform(transformBlock = { scale(scaleX, scaleY, pivot) }) { drawPath(path = musicNotePath, color = textColor) }
            }
        }
    }
}
