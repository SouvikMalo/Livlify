package com.souvik.livlify.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Track(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long,
    val uri: Uri,
    val albumArt: Uri?
) {
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArt)
                .build()
        ).build()
}

object MusicRepository {
    // Formats ExoPlayer can play. WMA is left out on purpose because it is not supported.
    private val playableTypes = setOf(
        "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac",
        "audio/flac", "audio/x-flac", "audio/ogg", "audio/opus",
        "audio/wav", "audio/x-wav", "audio/vnd.wave"
    )

    fun load(context: Context): List<Track> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val result = mutableListOf<Track>()
        val sort = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null, sort
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol) ?: ""
                if (mime !in playableTypes) continue
                val id = cursor.getLong(idCol)
                val albumId = cursor.getLong(albumIdCol)
                val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                result += Track(
                    id = id,
                    title = cursor.getString(titleCol) ?: "Unknown title",
                    artist = cursor.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    album = cursor.getString(albumCol)?.takeUnless { it == "<unknown>" } ?: "Unknown album",
                    duration = cursor.getLong(durationCol),
                    uri = ContentUris.withAppendedId(collection, id),
                    albumArt = art
                )
            }
        }
        return result
    }
}
