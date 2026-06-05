package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import com.example.MainActivity
import com.example.state.MediaStateManager
import com.example.state.PreferencesManager



class MediaListenerService : NotificationListenerService() {

    private var isTrackingSetup = false
    private lateinit var mediaSessionManager: MediaSessionManager
    private val registeredControllers = mutableMapOf<String, MediaController>()
    var audioPlaybackManager: AudioPlaybackManager? = null

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        updateControllers(controllers ?: emptyList())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateActiveSession()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateActiveSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        try {
            audioPlaybackManager = AudioPlaybackManager(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }



    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        MediaStateManager.updateServiceConnected(true)

        val prefs = PreferencesManager(this)
        if (prefs.isListenerEnabled) {
            setupMediaSessionTracking()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        cleanupMediaSessionTracking()
        MediaStateManager.updateServiceConnected(false)
        if (instance == this) {
            instance = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupMediaSessionTracking()
        try {
            audioPlaybackManager?.release()
            audioPlaybackManager = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        MediaStateManager.updateServiceConnected(false)
        if (instance == this) {
            instance = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val action = intent?.action
        if (action != null) {
            val prefs = PreferencesManager(this)
            when (action) {
                ACTION_TOGGLE_LISTENER -> {
                    if (prefs.isListenerEnabled) {
                        setupMediaSessionTracking()
                        updateActiveSession()
                    } else {
                        cleanupMediaSessionTracking()
                        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        manager.cancel(NOTIFICATION_ID)
                    }
                }
                ACTION_TOGGLE_FROM_NOTIFICATION -> {
                    toggleListenerState(this, false)
                }
                ACTION_PLAY_PAUSE -> triggerPlayPause()
                ACTION_NEXT -> triggerNext()
                ACTION_PREV -> triggerPrevious()
            }
        }
        return START_STICKY
    }

    private fun setupMediaSessionTracking() {
        if (isTrackingSetup) return
        try {
            mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(this, MediaListenerService::class.java)

            // Register session change listener
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionListener, componentName)

            // Query initial list
            val initialControllers = mediaSessionManager.getActiveSessions(componentName)
            updateControllers(initialControllers ?: emptyList())

            isTrackingSetup = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun cleanupMediaSessionTracking() {
        if (!isTrackingSetup) return
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (e: Exception) {
            // ignore
        }

        // Unregister callbacks
        registeredControllers.forEach { (_, controller) ->
            try {
                controller.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
        registeredControllers.clear()
        isTrackingSetup = false
    }

    private fun updateControllers(controllers: List<MediaController>) {
        // Unregister from old
        registeredControllers.forEach { (_, controller) ->
            try {
                controller.unregisterCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }
        registeredControllers.clear()

        // Register new
        controllers.forEach { controller ->
            val pkg = controller.packageName ?: "unknown"
            registeredControllers[pkg] = controller
            try {
                controller.registerCallback(controllerCallback)
            } catch (e: Exception) {
                // ignore
            }
        }

        updateActiveSession()
    }

    private fun getActiveController(): MediaController? {
        // Prefer the one that is actively playing system-wide
        val playing = registeredControllers.values.find {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        if (playing != null) return playing

        // Or fallback to first controller
        return registeredControllers.values.firstOrNull()
    }

    private fun updateActiveSession() {
        val controller = getActiveController()
        if (controller == null) {
            val localPlaying = audioPlaybackManager?.isPlaying() ?: false
            if (localPlaying) {
                MediaStateManager.updateMediaInfo(
                    MediaStateManager.MediaInfo(
                        title = "Ravana Sound Suite",
                        artist = "Active Sync Running",
                        isPlaying = true,
                        albumArt = null,
                        packageName = packageName
                    )
                )
                val prefs = PreferencesManager(this)
                if (prefs.isListenerEnabled) {
                    showServiceNotification("Ravana Sound Suite", "Active Sync Running", true, null)
                }
                return
            }

            MediaStateManager.updateMediaInfo(
                MediaStateManager.MediaInfo(
                    title = "No active media",
                    artist = "Waiting for playback...",
                    isPlaying = false,
                    albumArt = null,
                    packageName = ""
                )
            )
            val prefs = PreferencesManager(this)
            if (prefs.isListenerEnabled) {
                showServiceNotification("No active media", "Waiting for playback...", false, null)
            }
            return
        }

        val metadata = controller.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: "Unknown Title"

        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: "Unknown Artist"

        var artBitmap: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (artBitmap == null) {
            artBitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }

        val state = controller.playbackState
        val isPlaying = state != null && state.state == PlaybackState.STATE_PLAYING

        // Sync state flow
        MediaStateManager.updateMediaInfo(
            MediaStateManager.MediaInfo(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                albumArt = artBitmap,
                packageName = controller.packageName ?: ""
            )
        )

        // Show/Update Notification
        showServiceNotification(title, artist, isPlaying, artBitmap)
    }

    private fun showServiceNotification(title: String, artist: String, isPlaying: Boolean, albumArt: Bitmap?) {
        val prefs = PreferencesManager(this)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!prefs.isListenerEnabled) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            manager.cancel(NOTIFICATION_ID)
            return
        }

        val prevIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaListenerService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, MediaListenerService::class.java).apply { action = ACTION_PLAY_PAUSE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, MediaListenerService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, MediaListenerService::class.java).apply { action = ACTION_TOGGLE_FROM_NOTIFICATION },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_previous, "Previous", prevIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                playPauseIcon, "Play/Pause", playPauseIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_media_next, "Next", nextIntent
            ).build()
        )
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "Turn Off", toggleIntent
            ).build()
        )

        val mediaStyle = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1, 2)

        val token = getActiveController()?.sessionToken
        if (token != null) {
            mediaStyle.setMediaSession(token)
        }

        builder.setStyle(mediaStyle)

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Media Control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows the system playback status and media controls."
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun triggerPlayPause() {
        val controller = getActiveController()
        if (controller == null) {
            try {
                audioPlaybackManager?.let { pm ->
                    if (pm.isPlaying()) {
                        pm.pause()
                    } else {
                        pm.play()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            updateActiveSession()
            return
        }

        try {
            if (controller.packageName == packageName) {
                audioPlaybackManager?.let { pm ->
                    if (pm.isPlaying()) {
                        pm.pause()
                    } else {
                        pm.play()
                    }
                }
                updateActiveSession()
                return
            }

            val state = controller.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                controller.transportControls?.pause()
            } else {
                controller.transportControls?.play()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerNext() {
        val controller = getActiveController() ?: return
        try {
            controller.transportControls?.skipToNext()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun triggerPrevious() {
        val controller = getActiveController() ?: return
        try {
            controller.transportControls?.skipToPrevious()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        const val CHANNEL_ID = "ravana_light_media_channel"
        const val NOTIFICATION_ID = 4056

        const val ACTION_PLAY_PAUSE = "com.amazon.mp3.ACTION_PLAY_PAUSE"
        const val ACTION_PREV = "com.amazon.mp3.ACTION_PREV"
        const val ACTION_NEXT = "com.amazon.mp3.ACTION_NEXT"
        const val ACTION_TOGGLE_LISTENER = "com.amazon.mp3.ACTION_TOGGLE_LISTENER"
        const val ACTION_TOGGLE_FROM_NOTIFICATION = "com.amazon.mp3.ACTION_TOGGLE_FROM_NOTIFICATION"

        private var instance: MediaListenerService? = null

        fun toggleListenerState(context: Context, enabled: Boolean) {
            val prefs = PreferencesManager(context)
            prefs.isListenerEnabled = enabled
            
            val componentName = ComponentName(context, MediaListenerService::class.java)

            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    requestRebind(componentName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            val srv = instance
            if (srv != null) {
                if (enabled) {
                    srv.setupMediaSessionTracking()
                    srv.updateActiveSession()
                } else {
                    srv.cleanupMediaSessionTracking()
                    val manager = srv.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(NOTIFICATION_ID)
                    
                    MediaStateManager.updateMediaInfo(
                        MediaStateManager.MediaInfo(
                            title = "No active media",
                            artist = "Waiting for playback...",
                            isPlaying = false,
                            albumArt = null,
                            packageName = ""
                        )
                    )
                }
            }
            
            try {
                com.example.ToggleWidgetProvider.updateAllWidgets(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun playPauseActiveSession() {
            instance?.triggerPlayPause()
        }

        fun skipToNextActiveSession() {
            instance?.triggerNext()
        }

        fun skipToPreviousActiveSession() {
            instance?.triggerPrevious()
        }
    }
}
