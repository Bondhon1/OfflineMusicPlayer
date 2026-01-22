package com.example.offlinemusicplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.offlinemusicplayer.ui.theme.OfflineMusicPlayerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OfflineMusicPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MusicScreen()
                }
            }
        }
    }
}

private data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val contentUri: Uri
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun MusicScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
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
            songs = try {
                loadSongs(context)
            } catch (e: Exception) {
                errorMessage = "Could not load songs"
                emptyList()
            }
            isLoading = false
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

    fun togglePlayPause() {
        if (isPlaying && hasLoadedTrack) {
            mediaPlayer.pause()
            isPlaying = false
        } else {
            val target = currentIndex ?: 0
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
        val nextIndex = ((currentIndex ?: -1) + 1).mod(songs.size)
        scope.launch { playSong(nextIndex) }
    }

    fun playPrevious() {
        if (songs.isEmpty()) return
        val previousIndex = if ((currentIndex ?: 0) - 1 >= 0) {
            (currentIndex ?: 0) - 1
        } else {
            songs.lastIndex
        }
        scope.launch { playSong(previousIndex) }
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
                    onNext = ::playNext
                )
            }
        }
    ) { padding ->
        val gradient = Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.surfaceVariant
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
                    onSongSelected = { index -> scope.launch { playSong(index) } }
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
    onNext: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
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

private suspend fun loadSongs(context: Context): List<Song> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION
    )

    val selection = MediaStore.Audio.Media.IS_MUSIC + "!= 0"
    val sortOrder = MediaStore.Audio.Media.TITLE + " ASC"
    val songs = mutableListOf<Song>()

    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn)
            val artist = cursor.getString(artistColumn)
            val duration = cursor.getLong(durationColumn)
            val contentUri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
            songs.add(Song(id, title.orEmpty(), artist.orEmpty(), duration, contentUri))
        }
    }
    songs
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
    OfflineMusicPlayerTheme {
        PlayerBar(
            song = Song(1, "Sample Song", "Sample Artist", 210000, Uri.EMPTY),
            isPlaying = true,
            onPrevious = {},
            onPlayPause = {},
            onNext = {}
        )
    }
}