package com.souvik.livlify

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.souvik.livlify.data.MusicRepository
import com.souvik.livlify.data.Track
import com.souvik.livlify.playback.MusicService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.random.Random

// Design notes:
// The interface is neutral (black, white, grey). All colour comes from the album art of the song
// that is playing, so the app looks different with every track. Serif for titles, sans for UI.
private val Ink = Color.Black
private val Surface1 = Color(0xFF161618)
private val Surface2 = Color(0xFF232326)
private val Muted = Color(0xFF8E8E93)
private val Fallback = Color(0xFFB4BEFF)
private val Display = FontFamily.Serif

private const val ART_TINY = 48
private const val ART_LIST = 160
private const val ART_FULL = 768

class MainActivity : ComponentActivity() {
    private var permissionGranted by mutableStateOf(false)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var controller by mutableStateOf<MediaController?>(null)
    private var favourites by mutableStateOf<Set<Long>>(emptySet())

    private val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private val prefs: SharedPreferences
        get() = getSharedPreferences("livlify", Context.MODE_PRIVATE)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = hasPermission(audioPermission)
        permissionGranted = granted
        if (granted) loadTracks()
        else if (audioPermission in result && !shouldShowRequestPermissionRationale(audioPermission)) openAppSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        favourites = loadFavourites()
        askPermission()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Ink, surface = Surface1, primary = Fallback,
                    onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
                )
            ) {
                LivlifyScreen(
                    tracks = tracks,
                    controller = controller,
                    permissionGranted = permissionGranted,
                    favourites = favourites,
                    onToggleFavourite = { toggleFavourite(it) },
                    onRequestPermission = { askPermission() },
                    onRefresh = { loadTracks() }
                )
            }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        permissionGranted = hasPermission(audioPermission)
        if (permissionGranted) loadTracks()
        val needed = mutableListOf<String>()
        if (!permissionGranted) needed += audioPermission
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
            !prefs.getBoolean("asked_notifications", false)
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
            prefs.edit().putBoolean("asked_notifications", true).apply()
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        )
    }

    private fun loadTracks() {
        Thread { val loaded = MusicRepository.load(this); runOnUiThread { tracks = loaded } }.start()
    }

    private fun loadFavourites(): Set<Long> =
        prefs.getStringSet("favourites", emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    private fun toggleFavourite(id: Long) {
        favourites = if (id in favourites) favourites - id else favourites + id
        prefs.edit().putStringSet("favourites", favourites.map { it.toString() }.toSet()).apply()
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

// ───────────────────────────── Screen ─────────────────────────────

@Composable
private fun LivlifyScreen(
    tracks: List<Track>,
    controller: MediaController?,
    permissionGranted: Boolean,
    favourites: Set<Long>,
    onToggleFavourite: (Long) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Songs") }
    var showPlayer by remember { mutableStateOf(false) }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    // Mirror the player's state. Reading it on connect also restores the UI if the service
    // kept playing while the activity was closed.
    DisposableEffect(controller) {
        val c = controller
        if (c != null) {
            mediaId = c.currentMediaItem?.mediaId
            isPlaying = c.isPlaying
            shuffle = c.shuffleModeEnabled
            repeatMode = c.repeatMode
        }
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { mediaId = mediaItem?.mediaId }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffle = shuffleModeEnabled }
            override fun onRepeatModeChanged(mode: Int) { repeatMode = mode }
        }
        c?.addListener(listener)
        onDispose { c?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                val d = it.duration
                duration = if (d == C.TIME_UNSET || d < 0L) 0L else d
            }
            delay(250)
        }
    }

    val currentTrack = remember(tracks, mediaId) { tracks.firstOrNull { it.id.toString() == mediaId } }
    val tiny by rememberArt(currentTrack, ART_TINY)
    val accent by animateColorAsState(
        targetValue = remember(tiny) { accentOf(tiny) },
        animationSpec = tween(700),
        label = "accent"
    )

    val visible = remember(tracks, query, selectedTab, favourites) {
        tracks.filter { t ->
            val matches = query.isBlank() || t.title.contains(query, true) ||
                t.artist.contains(query, true) || t.album.contains(query, true)
            matches && (selectedTab != "Favourites" || t.id in favourites)
        }
    }

    fun startQueue(list: List<Track>, index: Int) {
        val c = controller ?: return
        c.setMediaItems(list.map { it.toMediaItem() }, index, 0L)
        c.prepare()
        c.play()
    }

    BackHandler(enabled = showPlayer) { showPlayer = false }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // A soft wash of the current song's colour behind the header.
        Box(
            Modifier.fillMaxWidth().height(380.dp)
                .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.26f), Color.Transparent)))
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Library", color = Color.White, fontFamily = Display, fontSize = 40.sp, letterSpacing = (-0.5).sp)
                    Text(
                        if (permissionGranted) "${tracks.size} songs on this phone" else "Waiting for access",
                        color = Muted, fontSize = 13.sp
                    )
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh library", tint = Muted) }
            }

            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                placeholder = { Text("Search songs, artists, albums", color = Muted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search", tint = Muted) }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Surface1, unfocusedContainerColor = Surface1,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White
                )
            )

            Row(Modifier.padding(start = 24.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                listOf("Songs", "Favourites").forEach { tab ->
                    val selected = tab == selectedTab
                    val underline by animateDpAsState(if (selected) 20.dp else 0.dp, label = "underline")
                    Column(
                        Modifier.clip(RoundedCornerShape(8.dp)).clickable { selectedTab = tab }.padding(vertical = 6.dp, horizontal = 2.dp)
                    ) {
                        Text(
                            tab, fontSize = 16.sp,
                            color = if (selected) Color.White else Muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.height(2.dp).width(underline).clip(RoundedCornerShape(1.dp)).background(accent))
                    }
                }
            }

            Row(Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        controller?.shuffleModeEnabled = false
                        startQueue(visible, 0)
                    },
                    enabled = visible.isNotEmpty() && controller != null,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White, contentColor = Color.Black,
                        disabledContainerColor = Surface1, disabledContentColor = Muted
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play", fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick = {
                        controller?.shuffleModeEnabled = true
                        startQueue(visible, Random.nextInt(visible.size))
                    },
                    enabled = visible.isNotEmpty() && controller != null,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Surface2, contentColor = Color.White,
                        disabledContainerColor = Surface1, disabledContentColor = Muted
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Shuffle, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Shuffle", fontWeight = FontWeight.SemiBold)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    !permissionGranted -> EmptyState(
                        "Allow access to your music",
                        "Livlify needs permission to read the audio files on this phone.",
                        "Allow access", onRequestPermission
                    )
                    tracks.isEmpty() -> EmptyState(
                        "No songs found",
                        "Add MP3, M4A, FLAC or other audio files to this phone, then refresh.",
                        "Refresh library", onRefresh
                    )
                    visible.isEmpty() && selectedTab == "Favourites" && query.isBlank() -> EmptyState(
                        "No favourites yet",
                        "Tap the heart on any song to keep it here.",
                        "Browse songs", { selectedTab = "Songs" }
                    )
                    visible.isEmpty() -> EmptyState(
                        "No matches",
                        "Try a different song, artist or album.",
                        "Clear search", { query = "" }
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    ) {
                        itemsIndexed(visible, key = { _, item -> item.id }) { index, track ->
                            TrackRow(
                                track = track,
                                active = currentTrack?.id == track.id,
                                playing = isPlaying,
                                favourite = track.id in favourites,
                                accent = accent,
                                onClick = { startQueue(visible, index) },
                                onFavourite = { onToggleFavourite(track.id) }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = currentTrack != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                currentTrack?.let { track ->
                    MiniPlayer(
                        track = track, accent = accent, playing = isPlaying, position = position, duration = duration,
                        onToggle = { if (controller?.isPlaying == true) controller?.pause() else controller?.play() },
                        onOpen = { showPlayer = true },
                        onNext = { controller?.seekToNextMediaItem() }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showPlayer && currentTrack != null,
            enter = slideInVertically(animationSpec = tween(320), initialOffsetY = { it }),
            exit = slideOutVertically(animationSpec = tween(260), targetOffsetY = { it })
        ) {
            currentTrack?.let { track ->
                NowPlayingScreen(
                    track = track, accent = accent, isPlaying = isPlaying, position = position, duration = duration,
                    shuffle = shuffle, repeatMode = repeatMode, favourite = track.id in favourites,
                    onClose = { showPlayer = false },
                    onPlayPause = { if (controller?.isPlaying == true) controller?.pause() else controller?.play() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onSeek = { controller?.seekTo(it) },
                    onShuffle = { controller?.shuffleModeEnabled = !shuffle },
                    onRepeat = {
                        controller?.repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                    },
                    onFavourite = { onToggleFavourite(track.id) }
                )
            }
        }
    }
}

// ───────────────────────────── Library pieces ─────────────────────────────

@Composable
private fun TrackRow(
    track: Track, active: Boolean, playing: Boolean, favourite: Boolean, accent: Color,
    onClick: () -> Unit, onFavourite: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Artwork(track, ART_LIST, Modifier.fillMaxSize(), 12.dp)
            if (active) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) { EqBars(playing, accent) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, color = if (active) accent else Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(track.artist, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatTime(track.duration), color = Muted, fontSize = 12.sp)
        IconButton(onClick = onFavourite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (favourite) "Remove from favourites" else "Add to favourites",
                tint = if (favourite) accent else Muted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EqBars(playing: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "eq")
    val a by transition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "a"
    )
    val b by transition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse), label = "b"
    )
    val c by transition.animateFloat(
        initialValue = 0.25f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse), label = "c"
    )
    Row(
        Modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(a, b, c).forEach { level ->
            Box(
                Modifier.width(3.dp).fillMaxHeight(if (playing) level else 0.3f)
                    .clip(RoundedCornerShape(2.dp)).background(color)
            )
        }
    }
}

@Composable
private fun MiniPlayer(
    track: Track, accent: Color, playing: Boolean, position: Long, duration: Long,
    onToggle: () -> Unit, onOpen: () -> Unit, onNext: () -> Unit
) {
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(lerp(Surface2, accent, 0.16f))
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Artwork(track, ART_LIST, Modifier.size(46.dp), 12.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(track.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(28.dp)
                )
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Next song", tint = Color.White) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(accent))
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.LibraryMusic, null, tint = Muted, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, color = Color.White, fontFamily = Display, fontSize = 22.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Muted, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onAction, shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
        ) { Text(action, fontWeight = FontWeight.SemiBold) }
    }
}

// ───────────────────────────── Now playing ─────────────────────────────

@Composable
private fun NowPlayingScreen(
    track: Track, accent: Color, isPlaying: Boolean, position: Long, duration: Long,
    shuffle: Boolean, repeatMode: Int, favourite: Boolean,
    onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onFavourite: () -> Unit
) {
    var dragY by remember { mutableFloatStateOf(0f) }
    val backdrop by rememberArt(track, ART_TINY)
    // The cover breathes: it settles back a little when paused and comes forward when playing.
    val artScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.88f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessLow),
        label = "artScale"
    )

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, dragY.roundToInt()) }
            .background(Ink)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (dragY > 220f) onClose() else dragY = 0f },
                    onDragCancel = { dragY = 0f }
                ) { change, amount ->
                    change.consume()
                    dragY = (dragY + amount).coerceAtLeast(0f)
                }
            }
    ) {
        backdrop?.let { bmp ->
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = image, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(60.dp), alpha = 0.55f
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), Ink.copy(alpha = 0.78f), Ink))
            )
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player", tint = Color.White) }
                Text(
                    track.album, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(48.dp))
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Artwork(
                    track, ART_FULL,
                    Modifier.aspectRatio(1f).graphicsLayer { scaleX = artScale; scaleY = artScale },
                    28.dp
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title, color = Color.White, fontFamily = Display, fontSize = 28.sp, lineHeight = 33.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        track.artist, color = Color.White.copy(alpha = 0.65f), fontSize = 16.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onFavourite) {
                    Icon(
                        if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        if (favourite) "Remove from favourites" else "Add to favourites",
                        tint = if (favourite) accent else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            SeekBar(position, duration, onSeek)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(position), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Text(formatTime(duration), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, "Shuffle", tint = if (shuffle) accent else Color.White.copy(alpha = 0.55f))
                }
                IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Previous song", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(76.dp).clip(CircleShape).background(Color.White)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(40.dp)
                    )
                }
                IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Default.SkipNext, "Next song", tint = Color.White, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = onRepeat) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        "Repeat",
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.55f) else accent
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val currentOnSeek by rememberUpdatedState(onSeek)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var hold by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(hold) {
        if (hold != null) {
            delay(450)
            hold = null
        }
    }
    val base = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = dragFraction ?: hold ?: base
    val barHeight by animateDpAsState(if (dragFraction != null) 8.dp else 4.dp, label = "seekHeight")

    Box(
        Modifier.fillMaxWidth().height(32.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) {
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        hold = f
                        currentOnSeek((f * duration).toLong())
                    }
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragFraction?.let { f ->
                            hold = f
                            if (duration > 0) currentOnSeek((f * duration).toLong())
                        }
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null }
                ) { change, _ ->
                    change.consume()
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(barHeight).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxWidth(fraction).height(barHeight).clip(RoundedCornerShape(50)).background(Color.White))
    }
}

// ───────────────────────────── Artwork ─────────────────────────────

@Composable
private fun rememberArt(track: Track?, sizePx: Int): State<Bitmap?> {
    val context = LocalContext.current.applicationContext
    return produceState<Bitmap?>(track?.let { ArtCache.peek(it, sizePx) }, track?.id, sizePx) {
        value = if (track == null) null else ArtCache.peek(track, sizePx)
        if (track != null && value == null) {
            value = withContext(Dispatchers.IO) { ArtCache.load(context, track, sizePx) }
        }
    }
}

@Composable
private fun Artwork(track: Track?, sizePx: Int, modifier: Modifier, corner: Dp) {
    val art by rememberArt(track, sizePx)
    // Songs without cover art get their own quiet gradient, picked from the song id.
    val hue = (((track?.id ?: 0L) * 47L) % 360L).toFloat()
    val fallback = remember(hue) {
        Brush.linearGradient(listOf(Color.hsv(hue, 0.30f, 0.42f), Color.hsv((hue + 40f) % 360f, 0.35f, 0.22f)))
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(fallback), contentAlignment = Alignment.Center) {
        val bmp = art
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.35f))
        }
    }
}

private object ArtCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()

    private fun key(track: Track, size: Int) = "${track.id}:$size"

    fun peek(track: Track, size: Int): Bitmap? = cache.get(key(track, size))

    fun load(context: Context, track: Track, size: Int): Bitmap? {
        val k = key(track, size)
        cache.get(k)?.let { return it }
        if (synchronized(missing) { k in missing }) return null
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(track.uri, Size(size, size), null)
            } else {
                embeddedArt(context, track, size)
            }
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) cache.put(k, bitmap) else synchronized(missing) { missing.add(k) }
        return bitmap
    }

    private fun embeddedArt(context: Context, track: Track, size: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track.uri)
            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { Bitmap.createScaledBitmap(it, size, size, true) }
            }
        } finally {
            retriever.release()
        }
    }
}

// Averages a tiny version of the cover and turns it into a bright, readable accent.
private fun accentOf(bmp: Bitmap?): Color {
    if (bmp == null) return Fallback
    return try {
        val soft = if (Build.VERSION.SDK_INT >= 26 && bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else bmp
        val small = Bitmap.createScaledBitmap(soft, 8, 8, true)
        var r = 0
        var g = 0
        var b = 0
        for (x in 0 until 8) for (y in 0 until 8) {
            val p = small.getPixel(x, y)
            r += android.graphics.Color.red(p)
            g += android.graphics.Color.green(p)
            b += android.graphics.Color.blue(p)
        }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(r / 64, g / 64, b / 64, hsv)
        hsv[1] = if (hsv[1] < 0.12f) hsv[1] else hsv[1].coerceIn(0.4f, 0.8f)
        hsv[2] = hsv[2].coerceIn(0.78f, 1f)
        Color(android.graphics.Color.HSVToColor(hsv))
    } catch (_: Exception) {
        Fallback
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
