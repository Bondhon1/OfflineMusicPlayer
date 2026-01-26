package com.example.offlinemusicplayer.model

import android.net.Uri

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val contentUri: Uri,
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0L
)
