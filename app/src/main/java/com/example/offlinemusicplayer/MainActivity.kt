package com.example.offlinemusicplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.offlinemusicplayer.data.MusicRepository
import com.example.offlinemusicplayer.model.Song
import com.example.offlinemusicplayer.ui.theme.OfflineMusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineMusicPlayerTheme(darkTheme = true, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicScreen()
                }
            }
        }
    }
}
@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MusicScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { MusicRepository(context.applicationContext) }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    var hasPermission by remember { mutableStateOf(hasAudioPermission(context, permissions)) }
    var isLoading by remember { mutableStateOf(false) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var currentIndex by remember { mutableStateOf<Int?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasLoadedTrack by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var normalCount by remember { mutableStateOf(0) }
    val recentHistory = remember { ArrayDeque<Long>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.all { it }
    }

    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(mediaPlayer) {
        onDispose {
            mediaPlayer.reset()
            mediaPlayer.release()
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            isLoading = true
            errorMessage = null
            val cachedSongs = runCatching { repository.loadCachedSongs() }.getOrDefault(emptyList())
            if (cachedSongs.isNotEmpty()) {
                songs = cachedSongs
            }
            songs = try {
                repository.scanDeviceSongs()
            } catch (e: Exception) {
                errorMessage = "Could not load songs"
                if (cachedSongs.isNotEmpty()) cachedSongs else emptyList()
            }
            isLoading = false
        }
    }

    fun registerRecent(songId: Long) {
        recentHistory.remove(songId)
        recentHistory.addLast(songId)
        while (recentHistory.size > 20) {
            recentHistory.removeFirst()
        }
    }

    fun pickNextIndex(): Int? {
        if (songs.isEmpty()) return null
        val recentSet = recentHistory.toSet()
        val candidates = songs.withIndex().filter { it.value.id !in recentSet }
        val available = if (candidates.isEmpty()) {
            recentHistory.clear()
            songs.withIndex().toList()
        } else {
            candidates
        }
        if (available.isEmpty()) return null

        val chosen = if (normalCount < 2) {
            normalCount += 1
            available.random(Random)
        } else {
            normalCount = 0
            available.maxByOrNull { it.value.playCount } ?: available.random(Random)
        }
        registerRecent(chosen.value.id)
        return chosen.index
    }

    suspend fun finalizePlayback(isUserSkip: Boolean) {
        val index = currentIndex ?: return
        val song = songs.getOrNull(index) ?: return
        val elapsed = runCatching { mediaPlayer.currentPosition.toLong() }.getOrDefault(0L)
        val quickSkip = isUserSkip && elapsed < 30_000L
        val delta = if (quickSkip) -2 else 1
        val updatedCount = (song.playCount + delta).coerceAtLeast(0)
        val updatedSong = song.copy(playCount = updatedCount, lastPlayedAt = System.currentTimeMillis())
        songs = songs.toMutableList().also { it[index] = updatedSong }
        repository.updatePlayStats(song.id, updatedCount, updatedSong.lastPlayedAt)
        if (delta > 0) {
            repository.insertHistory(song.id, System.currentTimeMillis())
        }
    }

    suspend fun playSong(index: Int, finalizeBefore: Boolean = true) {
        val song = songs.getOrNull(index) ?: return
        try {
            if (finalizeBefore && hasLoadedTrack) {
                finalizePlayback(isUserSkip = true)
            }
            withContext(Dispatchers.IO) {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, song.contentUri)
                mediaPlayer.prepare()
            }
            mediaPlayer.start()
            currentIndex = index
            isPlaying = true
            hasLoadedTrack = true
            showNowPlaying = true
            registerRecent(song.id)
            mediaPlayer.setOnCompletionListener {
                scope.launch {
                    finalizePlayback(isUserSkip = false)
                    hasLoadedTrack = false
                    val nextIndex = pickNextIndex()
                    if (nextIndex != null) {
                        playSong(nextIndex, finalizeBefore = false)
                    } else {
                        isPlaying = false
                        hasLoadedTrack = false
                        currentIndex = null
                    }
                }
            }
        } catch (e: Exception) {
            errorMessage = "Playback failed"
            isPlaying = false
            hasLoadedTrack = false
        }
    }

    fun togglePlayPause() {
        if (isPlaying && hasLoadedTrack) {
            mediaPlayer.pause()
            isPlaying = false
        } else {
            val target = currentIndex ?: pickNextIndex() ?: 0
            if (songs.isNotEmpty()) {
                try {
                    if (hasLoadedTrack && currentIndex != null) {
                        mediaPlayer.start()
                        isPlaying = true
                    } else {
                        scope.launch { playSong(target) }
                    }
                } catch (_: IllegalStateException) {
                    scope.launch { playSong(target) }
                }
            }
        }
    }

    fun playNext() {
        if (songs.isEmpty()) return
        scope.launch {
            val nextIndex = pickNextIndex() ?: ((currentIndex ?: -1) + 1).mod(songs.size)
            playSong(nextIndex)
        }
    }

    fun playPrevious() {
        if (songs.isEmpty()) return
        scope.launch {
            val previousId = recentHistory.reversed().drop(1).firstOrNull()
            val targetIndex = previousId?.let { id -> songs.indexOfFirst { it.id == id } }
            val resolvedIndex = if (targetIndex != null && targetIndex >= 0) {
                targetIndex
            } else {
                val candidate = (currentIndex ?: 0) - 1
                if (candidate >= 0) candidate else songs.lastIndex
            }
            playSong(resolvedIndex)
        }
    }

    suspend fun playSong(index: Int) {
        val song = songs.getOrNull(index) ?: return
        try {
            withContext(Dispatchers.IO) {
                mediaPlayer.reset()
                mediaPlayer.setDataSource(context, song.contentUri)
                mediaPlayer.prepare()
            }
            mediaPlayer.start()
            currentIndex = index
            isPlaying = true
            hasLoadedTrack = true
            mediaPlayer.setOnCompletionListener {
                if (songs.isEmpty()) {
                    isPlaying = false
                    currentIndex = null
                    hasLoadedTrack = false
                    return@setOnCompletionListener
                }
                val nextIndex = if (index + 1 < songs.size) index + 1 else 0
                scope.launch { playSong(nextIndex) }
            }
        } catch (e: Exception) {
            errorMessage = "Playback failed"
            isPlaying = false
            hasLoadedTrack = false
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Offline Player") },
                scrollBehavior = rememberTopAppBarState().let { state ->
                    androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior(state)
                }
            )
        },
        bottomBar = {
            val currentSong = currentIndex?.let { idx -> songs.getOrNull(idx) }
            if (currentSong != null) {
                PlayerBar(
                    song = currentSong,
                    isPlaying = isPlaying,
                    onPrevious = ::playPrevious,
                    onPlayPause = ::togglePlayPause,
                    onNext = ::playNext,
                    onExpand = { showNowPlaying = true }
                )
            }
        }
    ) { padding ->
        val gradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF090D18),
                Color(0xFF0D1527),
                Color(0xFF0A1222)
            )
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(padding)
        ) {
            when {
                !hasPermission -> PermissionRequest(
                    onGrant = { permissionLauncher.launch(permissions) }
                )
                isLoading -> LoadingState()
                songs.isEmpty() -> EmptyState()
                else -> SongList(
                    songs = songs,
                    currentIndex = currentIndex,
                    onSongSelected = { index ->
                        normalCount = 0
                        showNowPlaying = true
                        scope.launch { playSong(index) }
                    }
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.error
                )
            }

            val currentSong = currentIndex?.let { idx -> songs.getOrNull(idx) }
            AnimatedVisibility(visible = showNowPlaying && currentSong != null) {
                currentSong?.let { song ->
                    NowPlayingOverlay(
                        song = song,
                        isPlaying = isPlaying,
                        onClose = { showNowPlaying = false },
                        onPlayPause = ::togglePlayPause,
                        onNext = ::playNext,
                        onPrevious = ::playPrevious
                    )
                }
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    currentIndex: Int?,
    onSongSelected: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }
        itemsIndexed(songs) { index, song ->
            SongCard(
                song = song,
                isActive = index == currentIndex,
                onClick = { onSongSelected(index) }
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun SongCard(song: Song, isActive: Boolean, onClick: () -> Unit) {
    val activeColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = activeColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = song.artist.ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlayerBar(
    song: Song,
    isPlaying: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onExpand: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable { onExpand() },
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = song.artist.ifBlank { "Unknown artist" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(imageVector = Icons.Rounded.KeyboardArrowLeft, contentDescription = "Previous")
            }
            IconButton(onClick = onPlayPause) {
                val icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                Icon(imageVector = icon, contentDescription = if (isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = onNext) {
                Icon(imageVector = Icons.Rounded.KeyboardArrowRight, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun NowPlayingOverlay(
    song: Song,
    isPlaying: Boolean,
    onClose: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit
) {
    val gradient = Brush.verticalGradient(
        listOf(
            Color(0xFF050813),
            Color(0xFF0C1426),
            Color(0xFF080F1C)
        )
    )
    val pulse = rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    ).value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onClose() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Rounded.KeyboardArrowDown, contentDescription = "Back", tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Back to list", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x2200B8FF))
                        .padding(18.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Brush.radialGradient(listOf(Color(0xFF12223E), Color(0xFF0A1120))))
                        .padding(12.dp)
                        .scale(pulse)
                ) {
                    val bars = listOf(0, 1, 2, 3, 4)
                    val barTransition = rememberInfiniteTransition(label = "bars")
                    val transitions = bars.map { offset ->
                        barTransition.animateFloat(
                            initialValue = 0.35f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(durationMillis = 900 + (offset * 120), easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "bar-height-$offset"
                        ).value
                    }
                    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                        val barWidth = size.width / (bars.size * 2f)
                        bars.forEachIndexed { index, _ ->
                            val heightFactor = transitions[index]
                            val barHeight = size.height * 0.6f * heightFactor
                            val x = (index * barWidth * 2f) + barWidth / 2f
                            drawRoundRect(
                                color = Color(0xFF9CC8FF),
                                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - barHeight) / 2f),
                                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = song.title,
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = song.artist.ifBlank { "Unknown artist" },
                        color = Color(0xCCFFFFFF),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onPrevious) {
                    Icon(imageVector = Icons.Rounded.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White)
                }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0xFF1F3B6B))
                ) {
                    val icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                    Icon(imageVector = icon, contentDescription = if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(42.dp))
                }
                IconButton(onClick = onNext) {
                    Icon(imageVector = Icons.Rounded.KeyboardArrowRight, contentDescription = "Next", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun PermissionRequest(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Allow audio access", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "We only scan your device to list offline songs.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onGrant) {
            Text(text = "Grant permission")
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Scanning your music…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "No audio files found", style = MaterialTheme.typography.bodyLarge)
    }
}

private fun hasAudioPermission(context: Context, permissions: Array<String>): Boolean =
    permissions.all { perm ->
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun MusicScreenPreview() {
    OfflineMusicPlayerTheme(darkTheme = true, dynamicColor = false) {
        PlayerBar(
            song = Song(1, "Sample Song", "Sample Artist", 210000, Uri.EMPTY),
            isPlaying = true,
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onExpand = {}
        )
    }
}