package com.souvik.livlify

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.souvik.livlify.data.MusicRepository
import com.souvik.livlify.data.Track
import com.souvik.livlify.playback.MusicService
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private val Ink = Color(0xFF090B0E)
private val Panel = Color(0xFF15191E)
private val Lime = Color(0xFFD7FF72)
private val Muted = Color(0xFF9299A2)

class MainActivity : ComponentActivity() {
    private var permissionGranted by mutableStateOf(false)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var controller by mutableStateOf<MediaController?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) loadTracks()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 11, 14)
        window.navigationBarColor = android.graphics.Color.rgb(9, 11, 14)
        askPermission()
        val token = SessionToken(this, ComponentName(this, MusicService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Ink, surface = Panel, primary = Lime,
                onPrimary = Ink, onBackground = Color.White, onSurface = Color.White
            )) {
                LivlifyScreen(
                    tracks = tracks,
                    controller = controller,
                    permissionGranted = permissionGranted,
                    onRequestPermission = { askPermission() },
                    onRefresh = { loadTracks() }
                )
            }
        }
    }

    private fun askPermission() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true
            loadTracks()
        } else permissionLauncher.launch(permission)
    }

    private fun loadTracks() {
        Thread { val loaded = MusicRepository.load(this); runOnUiThread { tracks = loaded } }.start()
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }
}

@Composable
private fun LivlifyScreen(
    tracks: List<Track>, controller: MediaController?, permissionGranted: Boolean,
    onRequestPermission: () -> Unit, onRefresh: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("All songs") }
    var currentTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var shuffle by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var favourites by remember { mutableStateOf(setOf<Long>()) }
    var showPlayer by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val id = mediaItem?.mediaId?.toLongOrNull()
                currentTrack = tracks.find { it.id == id }
                duration = controller?.duration?.coerceAtLeast(0L) ?: 0L
            }
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(playbackState: Int) {
                duration = controller?.duration?.coerceAtLeast(0L) ?: 0L
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                position = it.currentPosition.coerceAtLeast(0L)
                duration = it.duration.takeIf { d -> d > 0 && d != androidx.media3.common.C.TIME_UNSET } ?: 0L
                isPlaying = it.isPlaying
            }
            delay(500)
        }
    }

    val visibleTracks = remember(tracks, query, selectedTab, favourites) {
        tracks.filter { track ->
            val matches = track.title.contains(query, true) || track.artist.contains(query, true) || track.album.contains(query, true)
            matches && (selectedTab != "Favourites" || favourites.contains(track.id))
        }
    }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151B20), Ink)))) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVLIFY", color = Lime, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Your music, your space.", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh library", tint = Color.White) }
                Box(Modifier.size(42.dp).clip(CircleShape).background(Panel), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.GraphicEq, null, tint = Lime)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 20.dp)) {
                Text("Made for your\nmoments.", color = Color.White, fontSize = 34.sp, lineHeight = 39.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search songs, artists...", color = Muted) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    singleLine = true, shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Lime, unfocusedBorderColor = Color(0xFF30363D),
                        focusedContainerColor = Panel, unfocusedContainerColor = Panel,
                        cursorColor = Lime, focusedTextColor = Color.White, unfocusedTextColor = Color.White
                    )
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    listOf("All songs", "Favourites").forEach { tab ->
                        FilterChip(selected = selectedTab == tab, onClick = { selectedTab = tab },
                            label = { Text(tab) }, leadingIcon = if (tab == "Favourites") ({ Icon(Icons.Default.Favorite, null, Modifier.size(16.dp)) }) else null,
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Lime, selectedLabelColor = Ink, selectedLeadingIconColor = Ink))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectedTab == "Favourites") "FAVOURITES" else "YOUR LIBRARY", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${visibleTracks.size} tracks", color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    !permissionGranted -> EmptyState("Music access needed", "Allow audio access to show songs stored on your phone.", "Grant access", onRequestPermission)
                    visibleTracks.isEmpty() -> EmptyState(if (tracks.isEmpty()) "No music found" else "Nothing here yet", if (tracks.isEmpty()) "Add MP3 or M4A files to your phone, then refresh." else "Try another search or add songs to Favourites.", "Refresh library", onRefresh)
                    else -> LazyColumn(contentPadding = PaddingValues(bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(visibleTracks, key = { _, item -> item.id }) { index, track ->
                            TrackRow(track, index + 1, currentTrack?.id == track.id, favourites.contains(track.id),
                                onClick = {
                                    val c = controller
                                    if (c != null) {
                                        c.setMediaItems(tracks.map { it.toMediaItem() }, tracks.indexOf(track), 0L)
                                        c.prepare(); c.play(); currentTrack = track; showPlayer = true
                                    }
                                }, onFavourite = { favourites = if (favourites.contains(track.id)) favourites - track.id else favourites + track.id })
                        }
                    }
                }
            }
            AnimatedVisibility(visible = currentTrack != null) {
                currentTrack?.let { track ->
                    MiniPlayer(track, isPlaying, position, duration,
                        onToggle = { if (controller?.isPlaying == true) controller?.pause() else controller?.play() },
                        onOpen = { showPlayer = true }, onNext = { controller?.seekToNextMediaItem() })
                }
            }
        }
        if (showPlayer && currentTrack != null) {
            NowPlayingScreen(
                track = currentTrack!!, isPlaying = isPlaying, position = position, duration = duration,
                shuffle = shuffle, repeat = repeat,
                onClose = { showPlayer = false },
                onPlayPause = { if (controller?.isPlaying == true) controller?.pause() else controller?.play() },
                onNext = { controller?.seekToNextMediaItem() }, onPrevious = { controller?.seekToPreviousMediaItem() },
                onSeek = { controller?.seekTo(it) }, onShuffle = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle },
                onRepeat = { repeat = !repeat; controller?.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
            )
        }
    }
}

@Composable
private fun TrackRow(track: Track, number: Int, active: Boolean, favourite: Boolean, onClick: () -> Unit, onFavourite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(if (active) Color(0xFF202A22) else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF34443B), Color(0xFF1C2522)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MusicNote, null, tint = if (active) Lime else Color(0xFFB8C7B9))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (active) Lime else Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            Text(track.artist, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        Text(formatTime(track.duration), color = Muted, fontSize = 11.sp)
        IconButton(onClick = onFavourite) { Icon(if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (favourite) Lime else Muted, modifier = Modifier.size(19.dp)) }
    }
}

@Composable
private fun MiniPlayer(track: Track, playing: Boolean, position: Long, duration: Long, onToggle: () -> Unit, onOpen: () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF20262B)).clickable(onClick = onOpen)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFF34443B)), contentAlignment = Alignment.Center) { Icon(Icons.Default.Album, null, tint = Lime) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { Text(track.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(track.artist, color = Muted, fontSize = 11.sp) }
            IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Lime) }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Color.White) }
        }
        LinearProgressIndicator(progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Lime, trackColor = Color.Transparent)
    }
}

@Composable
private fun NowPlayingScreen(track: Track, isPlaying: Boolean, position: Long, duration: Long, shuffle: Boolean, repeat: Boolean,
    onClose: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF25352D), Ink))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.KeyboardArrowDown, "Close player", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            Text("NOW PLAYING", color = Muted, fontSize = 11.sp, letterSpacing = 3.sp)
            Spacer(Modifier.weight(1f)); Spacer(Modifier.width(48.dp))
        }
        Spacer(Modifier.weight(0.7f))
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF526B5B), Color(0xFF17221C)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.GraphicEq, null, tint = Color(0xFFD7FF72), modifier = Modifier.size(112.dp))
        }
        Spacer(Modifier.weight(0.8f))
        Text(track.title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(track.artist, color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(24.dp))
        Slider(value = if (duration > 0) position.toFloat().coerceIn(0f, duration.toFloat()) else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()), colors = SliderDefaults.colors(thumbColor = Lime, activeTrackColor = Lime, inactiveTrackColor = Color(0xFF424A46)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position), color = Muted, fontSize = 11.sp); Text(formatTime(duration), color = Muted, fontSize = 11.sp) }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, null, tint = if (shuffle) Lime else Muted) }
            IconButton(onClick = onPrevious, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp).clip(CircleShape).background(Lime)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Ink, modifier = Modifier.size(38.dp)) }
            IconButton(onClick = onNext, modifier = Modifier.size(54.dp)) { Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            IconButton(onClick = onRepeat) { Icon(Icons.Default.RepeatOne, null, tint = if (repeat) Lime else Muted) }
        }
        Spacer(Modifier.weight(0.7f))
    }
}

@Composable
private fun EmptyState(title: String, message: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.LibraryMusic, null, tint = Color(0xFF65736A), modifier = Modifier.size(46.dp))
        Spacer(Modifier.height(14.dp)); Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(6.dp)); Text(message, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(16.dp)); Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink)) { Text(action) }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
