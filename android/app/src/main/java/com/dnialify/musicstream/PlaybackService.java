package com.dnialify.musicstream;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

public class PlaybackService extends Service {
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_ARM = "arm";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ARTIST = "artist";
    private static final String EXTRA_ARTWORK = "artwork";

    private static PlaybackService instance;
    private MediaSessionCompat mediaSession;
    private String title = "Dnialify Music Stream";
    private String artist = "MusicStream";
    private String artworkUrl = "";
    private Bitmap artwork;
    private String currentLyric = "";
    private boolean playing;
    private long positionMs;
    private long durationMs;
    private boolean dismissedPaused = false;
    private long lastStopMs = 0;

    public static void arm(Context context) {
        if (instance != null) { instance.publishNotification(); return; }
        start(context, ACTION_ARM);
    }

    private static void start(Context context, String action) {
        ContextCompat.startForegroundService(context,
                new Intent(context, PlaybackService.class).setAction(action));
    }

    public static void updateNotificationStatic(Context context, String title, String artist) {
        if (instance != null) {
            if (title != null) instance.title = title;
            if (artist != null) instance.artist = artist;
            instance.publishNotification();
            return;
        }
        Intent intent = new Intent(context, PlaybackService.class).setAction("updateNotification")
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void updateWebViewState(Context context, String title, String artist,
            String artwork, boolean playing, long positionMs, long durationMs) {
        if (instance != null) {
            Intent state = new Intent().putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist)
                    .putExtra(EXTRA_ARTWORK, artwork).putExtra("isPlaying", playing)
                    .putExtra("positionMs", positionMs).putExtra("durationMs", durationMs);
            instance.handleState(state);
            return;
        }
        Intent intent = new Intent(context, PlaybackService.class).setAction("webViewState")
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist)
                .putExtra(EXTRA_ARTWORK, artwork).putExtra("isPlaying", playing)
                .putExtra("positionMs", positionMs).putExtra("durationMs", durationMs);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void updateLyricsStatic(Context context, String previous, String current, String next) {
        String cur = current == null ? "" : current;
        if (instance != null) {
            instance.currentLyric = cur;
            instance.updateMediaSession();
            instance.publishNotification();
            return;
        }
        Intent intent = new Intent(context, PlaybackService.class).setAction("webViewLyrics")
                .putExtra("current", cur);
        ContextCompat.startForegroundService(context, intent);
    }

    // Kept for existing JS bridge callers. Playback itself remains WebView-owned.
    public static void play(Context context, String url, String title, String artist, String artwork) {
        updateNotificationStatic(context, title, artist);
    }

    public static void pause(Context context) { dispatch(context, "pause"); }
    public static void stop(Context context) { dispatch(context, "stop"); }
    public static void seek(Context context, double seconds) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class)
                .setAction("seek").putExtra("seconds", seconds));
    }
    public static boolean isPlaying() { return instance != null && instance.playing; }
    public static boolean isEnded() { return false; }
    public static double currentTime() { return instance == null ? 0 : instance.positionMs / 1000d; }
    public static double duration() { return instance == null ? 0 : instance.durationMs / 1000d; }
    public static void speed(Context context, double value) { }
    public static void volume(Context context, double value) { }

    private static void dispatch(Context context, String action) {
        start(context, action);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createChannel();
        mediaSession = new MediaSessionCompat(this, "MusicStreamSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { sendToWebView("if(window.togglePlay) togglePlay();"); }
            @Override public void onPause() { sendToWebView("if(window.togglePlay) togglePlay();"); }
            @Override public void onSkipToPrevious() { sendToWebView("if(window.prevTrack) prevTrack();"); }
            @Override public void onSkipToNext() { sendToWebView("if(window.nextTrack) nextTrack(false);"); }
            @Override public void onSeekTo(long position) {
                sendToWebView("if(window.Player && window.Player.yt) Player.yt.seekTo(" + (position / 1000d)
                        + ",true); else if(window.Player && window.Player.audio) Player.audio.currentTime="
                        + (position / 1000d) + ";");
            }
        });
        mediaSession.setActive(true);
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Playback",
                NotificationManager.IMPORTANCE_LOW));
    }

    private void sendToWebView(String script) {
        if (MainActivity.current == null) return;
        MainActivity.current.runOnUiThread(() -> {
            try { MainActivity.current.getBridge().eval(script, null); } catch (Exception ignored) { }
        });
    }

    private void handleState(Intent intent) {
        String nextTitle = intent.getStringExtra(EXTRA_TITLE);
        String nextArtist = intent.getStringExtra(EXTRA_ARTIST);
        String nextArtwork = intent.getStringExtra(EXTRA_ARTWORK);
        if (nextTitle != null && !nextTitle.isEmpty()) title = nextTitle;
        if (nextArtist != null && !nextArtist.isEmpty()) artist = nextArtist;
        if (nextArtwork != null && !nextArtwork.equals(artworkUrl)) {
            artworkUrl = nextArtwork;
            loadArtwork(nextArtwork);
        }
        boolean nextPlaying = intent.getBooleanExtra("isPlaying", false);
        // de-bounce: YT may report PLAYING ~500ms after stopVideo → ignore transient playing after close
        if (nextPlaying && System.currentTimeMillis() - lastStopMs < 1500) {
            // treat as paused, keep service stopped
            playing = false;
            updateMediaSession();
            return;
        }
        // swipe-dismissed while paused → don't resurrect until next play
        if (dismissedPaused && !nextPlaying) {
            playing = false;
            positionMs = Math.max(0, intent.getLongExtra("positionMs", 0));
            durationMs = Math.max(0, intent.getLongExtra("durationMs", 0));
            updateMediaSession();
            return;
        }
        if (nextPlaying) dismissedPaused = false;
        playing = nextPlaying;
        positionMs = Math.max(0, intent.getLongExtra("positionMs", 0));
        durationMs = Math.max(0, intent.getLongExtra("durationMs", 0));
        updateMediaSession();
        publishNotification();
    }

    private void loadArtwork(String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            try {
                Bitmap loaded = BitmapFactory.decodeStream(new java.net.URL(url).openStream());
                if (loaded != null) {
                    artwork = loaded;
                    updateMediaSession();
                    publishNotification();
                }
            } catch (Exception ignored) { }
        }).start();
    }

    private void updateMediaSession() {
        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, currentLyric)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
        if (artwork != null) {
            metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork);
        } else if (!artworkUrl.isEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, artworkUrl);
        }
        mediaSession.setMetadata(metadata.build());
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO;
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setActions(actions)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        positionMs, playing ? 1f : 0f).build());
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int playIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playLabel = playing ? "Pause" : "Play";
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, "Prev", serviceAction("prev", 1))
                .addAction(playIcon, playLabel, serviceAction("toggle", 3))
                .addAction(android.R.drawable.ic_media_next, "Next", serviceAction("next", 2));
        // when paused, notification dismissible → swipe deletes without re-push until next play
        if (!playing) {
            PendingIntent del = PendingIntent.getService(this, 99,
                    new Intent(this, PlaybackService.class).setAction("dismiss"),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.setDeleteIntent(del);
        }
        return b.build();
    }

    private void publishNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        Notification notification = buildNotification();
        try { startForeground(NOTIFICATION_ID, notification); }
        catch (Exception ignored) { manager.notify(NOTIFICATION_ID, notification); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if ("webViewState".equals(action)) handleState(intent);
        else if ("webViewLyrics".equals(action)) {
            String cur = intent.getStringExtra("current");
            if (cur != null) {
                currentLyric = cur;
                updateMediaSession();
                publishNotification();
            }
        }
        else if ("stop".equals(action) || "dismiss".equals(action)) {
            // true stop → remove notification and stop foreground, don't recreate until next play
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { try { stopForeground(true); } catch (Exception ignored2) {} }
            try { getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID); } catch (Exception ignored) {}
            playing = false;
            lastStopMs = System.currentTimeMillis();
            if ("dismiss".equals(action)) dismissedPaused = true;
            else dismissedPaused = true; // also for explicit stop, prevent re-push until next play
            // if explicit stop from closePlayer, also clear session state to NONE so lockscreen goes away
            if ("stop".equals(action)) {
                try { mediaSession.setPlaybackState(new PlaybackStateCompat.Builder().setState(PlaybackStateCompat.STATE_STOPPED, 0, 0f).setActions(0).build()); mediaSession.setActive(false); } catch (Exception ignored) {}
            }
            if ("dismiss".equals(action)) {
                // swipe when paused → stay stopped, don't auto-republish; JS will republish on next play when pushNativeState playing=true
                return START_NOT_STICKY;
            }
            return START_NOT_STICKY;
        } else if (ACTION_ARM.equals(action) || "updateNotification".equals(action)) {
            String nextTitle = intent.getStringExtra(EXTRA_TITLE);
            String nextArtist = intent.getStringExtra(EXTRA_ARTIST);
            if (nextTitle != null) title = nextTitle;
            if (nextArtist != null) artist = nextArtist;
            publishNotification();
        } else if ("prev".equals(action)) sendToWebView("if(window.prevTrack) prevTrack();");
        else if ("next".equals(action)) sendToWebView("if(window.nextTrack) nextTrack(false);");
        else if ("toggle".equals(action)) sendToWebView("if(window.togglePlay) togglePlay();");
        else if ("pause".equals(action)) sendToWebView("if(window.togglePlay) togglePlay();");
        else if ("seek".equals(action)) {
            double seconds = intent.getDoubleExtra("seconds", 0);
            sendToWebView("if(window.Player && window.Player.yt) Player.yt.seekTo(" + seconds
                    + ",true); else if(window.Player && window.Player.audio) Player.audio.currentTime=" + seconds + ";");
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) { super.onTaskRemoved(rootIntent); }

    @Override public void onDestroy() {
        if (mediaSession != null) { mediaSession.setActive(false); mediaSession.release(); }
        mediaSession = null;
        instance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
