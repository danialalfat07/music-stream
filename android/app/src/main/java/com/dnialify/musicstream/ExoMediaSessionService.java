package com.dnialify.musicstream;

import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Socket 4 (FASE 2): MediaSession + notification (id 1002) attached to the
 * single shared ExoPlayer.
 * Socket 5: session wraps ForwardingPlayer; next/prev taps route to the JS
 * queue (no super.seekToNext/Previous: Exo must not auto-manage queue).
 *
 * One session for the whole service lifetime. Player owned by
 * ExoPlayerHolder (never released here). No silent WAV yet (Socket 6).
 */
public final class ExoMediaSessionService extends MediaSessionService {

    private static final String TAG = "ExoMediaSessionService";
    private static final String SOCKET_TAG = "EXO_SOCKET5";

    private MediaSession mediaSession;
    private ForwardingPlayer forwardingPlayer;

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
        forwardingPlayer = new ForwardingPlayer(player) {
            @Override
            public void seekToNext() {
                android.util.Log.d(SOCKET_TAG, "seekToNext -> JS nextTrack");
                sendToWebView("if(window.nextTrack) nextTrack(false);");
            }

            @Override
            public void seekToPrevious() {
                android.util.Log.d(SOCKET_TAG, "seekToPrevious -> JS prevTrack");
                sendToWebView("if(window.prevTrack) prevTrack();");
            }
        };
        mediaSession = new MediaSession.Builder(this, forwardingPlayer)
                .setCallback(sessionCallback)
                .build();
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

    /**
     * Socket 5: hardware keys NEXT/PREV route ke ForwardingPlayer (bridge JS).
     * Default Media3 untuk PLAY/PAUSE/STOP tetap (return false).
     * ACTION_DOWN saja biar tak dobel.
     */
    private final MediaSession.Callback sessionCallback = new MediaSession.Callback() {
        @Override
        public boolean onMediaButtonEvent(
                MediaSession session, MediaSession.ControllerInfo controllerInfo,
                android.content.Intent intent) {
            android.view.KeyEvent event = null;
            try {
                event = intent.getParcelableExtra(
                        android.content.Intent.EXTRA_KEY_EVENT,
                        android.view.KeyEvent.class);
            } catch (Exception ignored) {}
            if (event == null) {
                try {
                    event = intent.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT);
                } catch (Exception ignored) {}
            }
            android.util.Log.d(SOCKET_TAG, "onMediaButtonEvent keyCode="
                    + (event != null ? event.getKeyCode() : "null"));
            if (event == null || event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                return false;
            }
            switch (event.getKeyCode()) {
                case android.view.KeyEvent.KEYCODE_MEDIA_NEXT:
                    if (forwardingPlayer != null) forwardingPlayer.seekToNext();
                    return true;
                case android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    if (forwardingPlayer != null) forwardingPlayer.seekToPrevious();
                    return true;
                default:
                    return false;
            }
        }
    };

    /** JS bridge (pola PlaybackService.sendToWebView): main thread, null-safe. */
    private void sendToWebView(final String script) {
        final MainActivity a = MainActivity.current;
        if (a == null) {
            android.util.Log.w(TAG, "sendToWebView no activity");
            return;
        }
        a.runOnUiThread(() -> {
            try {
                android.webkit.WebView wv = a.getBridge() != null
                        ? a.getBridge().getWebView() : null;
                if (wv != null) {
                    wv.evaluateJavascript(script, null);
                    return;
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "sendToWebView eval fail " + e);
            }
            try {
                a.getBridge().eval(script, null);
            } catch (Exception e) {
                android.util.Log.w(TAG, "sendToWebView bridge fail " + e);
            }
        });
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
