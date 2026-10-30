package com.morgan.minimallauncher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Lets Android share what's playing (Spotify, YouTube Music…) with the
 * launcher. Android only allows that for apps given "Notification access",
 * so this listener does nothing else.
 */
class MediaListener : NotificationListenerService()

object NowPlaying {

    data class Info(
        val title: String,
        val artist: String,
        val app: String,
        val playing: Boolean,
        val art: Bitmap?,
        val controller: MediaController,
        /** Playback position and length in ms (0 when the app doesn't say). */
        val position: Long = 0L,
        val duration: Long = 0L,
    ) {
        val progress: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    fun hasAccess(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /** What a particular app is playing or has paused, if it shares it (needs Notification Access). */
    fun forPackage(c: Context, pkg: String): Info? {
        if (!hasAccess(c)) return null
        return try {
            val msm = c.getSystemService(MediaSessionManager::class.java)
            val ctrl = msm.getActiveSessions(ComponentName(c, MediaListener::class.java)).firstOrNull { it.packageName == pkg } ?: return null
            toInfo(c, ctrl)
        } catch (e: Exception) { null }
    }

    private fun toInfo(c: Context, ctrl: MediaController): Info? {
        val md = ctrl.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val app = try {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(ctrl.packageName, 0)).toString()
        } catch (e: Exception) { ctrl.packageName }
        val state = ctrl.playbackState
        return Info(title, artist, app, state?.state == PlaybackState.STATE_PLAYING, art, ctrl,
            state?.position?.coerceAtLeast(0L) ?: 0L, md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L))
    }

    /** The playing (or most recent) media session, or null. */
    fun current(c: Context): Info? {
        if (!hasAccess(c)) return null
        return try {
            val msm = c.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(c, MediaListener::class.java))
            val ctrl = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull { it.metadata != null } ?: return null
            val md = ctrl.metadata ?: return null
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return null
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            val app = try {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(ctrl.packageName, 0)).toString()
            } catch (e: Exception) { ctrl.packageName }
            Info(title, artist, app, ctrl.playbackState?.state == PlaybackState.STATE_PLAYING, art, ctrl)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun togglePlay(info: Info) {
        val t = info.controller.transportControls
        if (info.playing) t.pause() else t.play()
    }
}
