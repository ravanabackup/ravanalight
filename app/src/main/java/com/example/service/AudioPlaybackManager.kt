package com.example.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import java.io.File
import java.io.FileOutputStream

class AudioPlaybackManager(private val context: Context) {
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private val silentFile = File(context.cacheDir, "silent.wav")

    init {
        ensureSilentWavExists()
        try {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ALL
                playWhenReady = false
                val mediaItem = MediaItem.Builder()
                    .setUri(Uri.fromFile(silentFile))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle("Ravana Sound Suite")
                            .setArtist("Active Sync Running")
                            .build()
                    )
                    .build()
                setMediaItem(mediaItem)
                prepare()
            }
            exoPlayer?.let { player ->
                initializeMediaSession(player)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initializeMediaSession(player: ExoPlayer) {
        try {
            // Build the Media3 media session. The OS listens to this session lifecycle.
            mediaSession = MediaSession.Builder(context, player)
                .setId("AppAudioSyncSession")
                .build()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playAudio(mediaItem: MediaItem) {
        try {
            exoPlayer?.let { player ->
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun play() {
        try {
            exoPlayer?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun pause() {
        try {
            exoPlayer?.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    fun release() {
        try {
            mediaSession?.release()
            mediaSession = null
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureSilentWavExists() {
        if (!silentFile.exists()) {
            try {
                writeSilentWav(silentFile)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun writeSilentWav(file: File) {
        val sampleRate = 8000
        val durationSeconds = 3
        val numChannels = 1
        val bitsPerSample = 8
        val dataSize = sampleRate * durationSeconds * numChannels * (bitsPerSample / 8)
        val totalSize = 36 + dataSize
        
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte() // RIFF
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        
        header[4] = (totalSize and 0xff).toByte()
        header[5] = ((totalSize shr 8) and 0xff).toByte()
        header[6] = ((totalSize shr 16) and 0xff).toByte()
        header[7] = ((totalSize shr 24) and 0xff).toByte()
        
        header[8] = 'W'.code.toByte() // WAVE
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        
        header[12] = 'f'.code.toByte() // fmt
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        
        header[16] = 16 
        header[17] = 0
        header[18] = 0
        header[19] = 0
        
        header[20] = 1 
        header[21] = 0
        
        header[22] = numChannels.toByte()
        header[23] = 0
        
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        
        val byteRate = sampleRate * numChannels * bitsPerSample / 8
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        
        header[32] = 1 
        header[33] = 0
        
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        
        header[36] = 'd'.code.toByte() // data
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()
        
        FileOutputStream(file).use { outputStream ->
            outputStream.write(header)
            val data = ByteArray(dataSize) { 128.toByte() }
            outputStream.write(data)
        }
    }
}
