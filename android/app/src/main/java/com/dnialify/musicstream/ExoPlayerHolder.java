package com.dnialify.musicstream;

import android.content.Context;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

/**
 * Socket 2 (FASE 2): single shared ExoPlayer instance.
 *
 * Unused for now: nobody calls getInstance() yet, no MediaSession, no
 * service, no notification. Socket 3 wires VisionOS playback to it.
 */
public final class ExoPlayerHolder {

    private static final String TAG = "ExoPlayerHolder";

    /** Sama dengan resolver/SongCache/MediaPlayer legacy: googlevideo 403
     *  tanpa UA ini (socket-3-fix). */
    private static final String VISIONOS_UA =
            "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)";

    private static ExoPlayerHolder instance;

    private final ExoPlayer player;
    private final Context appContext;

    private ExoPlayerHolder(Context context) {
        appContext = context.getApplicationContext();
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.CONTENT_TYPE_MUSIC)
                .build();
        player = new ExoPlayer.Builder(appContext)
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(
                        new DefaultHttpDataSource.Factory().setUserAgent(VISIONOS_UA)))
                .build();
        android.util.Log.d(TAG, "holder created, single ExoPlayer owned");
    }

    public static synchronized ExoPlayerHolder getInstance(Context context) {
        if (instance == null) {
            instance = new ExoPlayerHolder(context);
        }
        return instance;
    }

    /** The single shared player. Do not release it; use release() instead. */
    public ExoPlayer getPlayer() {
        return player;
    }

    /** Drop the singleton (player released). Next getInstance() rebuilds. */
    public static synchronized void release() {
        if (instance != null) {
            try {
                instance.player.release();
            } catch (Exception ignored) {}
            instance = null;
            android.util.Log.d(TAG, "holder released");
        }
    }
}
