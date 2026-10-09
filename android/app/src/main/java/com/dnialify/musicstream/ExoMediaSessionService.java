package com.dnialify.musicstream;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Socket 4 (FASE 2): MediaSession + notification (id 1002 via Media3
 * defaults) attached to the single shared ExoPlayer.
 *
 * One session for the whole service lifetime. Player owned by
 * ExoPlayerHolder (never released here). No ForwardingPlayer yet
 * (Socket 5), no silent WAV yet (Socket 6).
 */
public final class ExoMediaSessionService extends MediaSessionService {

    private static final String TAG = "ExoMediaSessionService";

    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();
        // Notif ID 1002 (jangan 1001 default: tabrakan legacy PlaybackService).
        setMediaNotificationProvider(
                new androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                        .setNotificationId(1002)
                        .setChannelId("exo_playback")
                        .setChannelName(R.string.app_name)
                        .build());
        ExoPlayer player = ExoPlayerHolder.getInstance(this).getPlayer();
        mediaSession = new MediaSession.Builder(this, player).build();
        android.util.Log.d(TAG, "session created, attached playerHash="
                + System.identityHashCode(player)
                + " alreadyAdded=" + isSessionAdded(mediaSession));
        if (!isSessionAdded(mediaSession)) {
            addSession(mediaSession);
            android.util.Log.d(TAG, "session explicitly added");
        }
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        android.util.Log.d(TAG, "onUpdateNotification foreground=" + startInForegroundRequired);
        super.onUpdateNotification(session, startInForegroundRequired);
    }

    @Override
    public void onUpdateNotification(MediaSession session) {
        android.util.Log.d(TAG, "onUpdateNotification");
        super.onUpdateNotification(session);
    }

    @Override
    public void onDestroy() {
        android.util.Log.d(TAG, "service destroyed, session released (player kept)");
        if (mediaSession != null) {
            try {
                mediaSession.release();
            } catch (Exception ignored) {}
            mediaSession = null;
        }
        super.onDestroy();
    }
}
