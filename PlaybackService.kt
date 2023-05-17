package com.library.media.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.library.media.R
import com.google.common.collect.ImmutableList
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    lateinit var player: ExoPlayer

    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        val pendingIntent =
            packageManager.getLaunchIntentForPackage(packageName).let { sessionIntent ->
                PendingIntent.getActivity(this@PlaybackService, 0, sessionIntent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
            }
        player.addListener(PlayerListener())
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
        setListener(MediaSessionServiceListener())
        setMediaNotificationProvider(
            NotificationProvider().apply {
                setSmallIcon(R.drawable.ic_notification_24)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isMediaButtonPlayEvent(intent) && player.mediaItemCount == 0) {
            val mediaItem = restoreLastRecentPlayedItem()
            player.setMediaItem(mediaItem)
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun isMediaButtonPlayEvent(intent: Intent?): Boolean {
        intent ?: return false
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (
                keyEvent!!.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                keyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
            ) {
                return true
            }
        }
        return false
    }

    private fun saveLastRecentPlayedItem(mediaItem: MediaItem?) {
        mediaItem ?: return
        val preferences = getSharedPreferences(RECENT_ITEM_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        editor.putString(RECENT_ITEM_SHARED_PREF_KEY_MEDIA_ID, mediaItem.mediaId)
        editor.putString(RECENT_ITEM_SHARED_PREF_KEY_URI, mediaItem.localConfiguration?.uri.toString())
        editor.putString(RECENT_ITEM_SHARED_PREF_KEY_TITLE, mediaItem.mediaMetadata.displayTitle.toString())
        editor.putString(
            RECENT_ITEM_SHARED_PREF_KEY_ARTWORK_URI,
            mediaItem.mediaMetadata.artworkUri.toString()
        )
        editor.apply()
    }

    private fun restoreLastRecentPlayedItem(): MediaItem {
        val preferences = getSharedPreferences(RECENT_ITEM_SHARED_PREF_NAME, Context.MODE_PRIVATE)
        val mediaUri = Uri.parse(preferences.getString(RECENT_ITEM_SHARED_PREF_KEY_URI, ""))
        val artworkUri = Uri.parse(preferences.getString(RECENT_ITEM_SHARED_PREF_KEY_ARTWORK_URI, ""))
        val mediaId =
            preferences.getString(RECENT_ITEM_SHARED_PREF_KEY_MEDIA_ID, UUID.randomUUID().toString())
        val mediaMetadata =
            MediaMetadata.Builder()
                .setTitle(preferences.getString(RECENT_ITEM_SHARED_PREF_KEY_TITLE, ""))
                .setArtworkUri(artworkUri)
                .build()
        return MediaItem.Builder()
            .setUri(mediaUri)
            .setMediaId(mediaId!!)
            .setMediaMetadata(mediaMetadata)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if(!player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        player.removeListener(PlayerListener())
        mediaSession.release()
        clearListener()
        super.onDestroy()
    }

    /*xx
     * NotificationProvider to customize Notification actions
     */
    private inner class NotificationProvider: DefaultMediaNotificationProvider(applicationContext) {

        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            customLayout: ImmutableList<CommandButton>,
            showPauseButton: Boolean
        ): ImmutableList<CommandButton> {
            val skipPreviousCommandButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setIconResId(R.drawable.ic_notification_skip_previous_24)
                .setEnabled(true)
                .build()
            val seekBack5sCommandButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setIconResId(R.drawable.ic_notification_backward_24)
                .setEnabled(true)
                .build()
            val playCommandButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                .setIconResId(if (mediaSession.player.isPlaying) R.drawable.ic_notification_pause_24 else R.drawable.ic_notification_play_24)
                .setEnabled(true)
                .build()
            val seekForward10sCommandButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setIconResId(R.drawable.ic_notification_forward_24)
                .setEnabled(true)
                .build()
            val skipNextCommandButton = CommandButton.Builder()
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setIconResId(R.drawable.ic_notification_skip_next_24)
                .setEnabled(true)
                .build()
            val commandButtons: MutableList<CommandButton> = mutableListOf(
                skipPreviousCommandButton,
                seekBack5sCommandButton,
                playCommandButton,
                seekForward10sCommandButton,
                skipNextCommandButton
            )
            return ImmutableList.copyOf(commandButtons)
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                saveLastRecentPlayedItem(player.currentMediaItem)
            }
        }
    }

    private inner class MediaSessionServiceListener : Listener {

        /**
         * This method is only required to be implemented on Android 12 or above when an attempt is made
         * by a media controller to resume playback when the {@link MediaSessionService} is in the
         * background.
         */
        override fun onForegroundServiceStartNotAllowedException() {
            val notificationManagerCompat = NotificationManagerCompat.from(this@PlaybackService)
            ensureNotificationChannel(notificationManagerCompat)
            // Build a PendingIntent that can be used to launch the UI.
            val pendingIntent =
                packageManager.getLaunchIntentForPackage(packageName).let { sessionIntent ->
                    PendingIntent.getActivity(this@PlaybackService, 0, sessionIntent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
                }
            val builder =
                NotificationCompat.Builder(this@PlaybackService, CHANNEL_ID)
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.ic_notification_24)
                    .setContentTitle(getString(R.string.notification_content_title))
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(getString(R.string.notification_content_text))
                    )
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true)
            notificationManagerCompat.notify(NOTIFICATION_ID, builder.build())
        }

        private fun ensureNotificationChannel(notificationManagerCompat: NotificationManagerCompat) {
            if (Util.SDK_INT < 26 || notificationManagerCompat.getNotificationChannel(CHANNEL_ID) != null) {
                return
            }

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            notificationManagerCompat.createNotificationChannel(channel)
        }
    }


    companion object {
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "demo_session_notification_channel_id"
        private const val RECENT_ITEM_SHARED_PREF_NAME = "RECENT_ITEM_SHARED_PREF_NAME"
        private const val RECENT_ITEM_SHARED_PREF_KEY_MEDIA_ID = "RECENT_ITEM_SHARED_PREF_KEY_MEDIA_ID"
        private const val RECENT_ITEM_SHARED_PREF_KEY_URI = "RECENT_ITEM_SHARED_PREF_KEY_URI"
        private const val RECENT_ITEM_SHARED_PREF_KEY_ARTWORK_URI = "RECENT_ITEM_SHARED_PREF_KEY_ARTWORK_URI"
        private const val RECENT_ITEM_SHARED_PREF_KEY_TITLE = "RECENT_ITEM_SHARED_PREF_KEY_TITLE"
    }
}