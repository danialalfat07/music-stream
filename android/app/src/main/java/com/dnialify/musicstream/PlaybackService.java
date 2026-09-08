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
import android.view.View;
import android.widget.RemoteViews;

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
    private String previousLyric = "";
    private String currentLyric = "";
    private String nextLyric = "";
    private boolean playing;
    private long positionMs;
    private long durationMs;

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
        if (instance != null) {
            Intent lyrics = new Intent().putExtra("prev", previous)
                    .putExtra("current", current).putExtra("next", next);
            instance.handleLyrics(lyrics);
            return;
        }
        Intent intent = new Intent(context, PlaybackService.class).setAction("webViewLyrics")
                .putExtra("prev", previous).putExtra("current", current).putExtra("next", next);
        ContextCompat.startForegroundService(context, intent);
    }

    // Kept for existing JS bridge callers. Playback itself remains WebView-owned.
    public static void play(Context context, String url, String title, String artist, String artwork) {
        updateNotificationStatic(context, title, artist);
    }

    public static void pause(Context context) { dispatch(context, "pause"); }
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
        playing = intent.getBooleanExtra("isPlaying", false);
        positionMs = Math.max(0, intent.getLongExtra("positionMs", 0));
        durationMs = Math.max(0, intent.getLongExtra("durationMs", 0));
        updateMediaSession();
        publishNotification();
    }

    private void loadArtwork(String url) {
        if (url.isEmpty()) return;
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
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist);
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

    private void handleLyrics(Intent intent) {
        previousLyric = value(intent.getStringExtra("prev"));
        currentLyric = value(intent.getStringExtra("current"));
        nextLyric = value(intent.getStringExtra("next"));
        publishNotification();
    }

    private String value(String value) { return value == null ? "" : value; }

    private PendingIntent serviceAction(String action, int requestCode) {
        return PendingIntent.getService(this, requestCode,
                new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        RemoteViews collapsed = new RemoteViews(getPackageName(), R.layout.notification_music_collapsed);
        RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notification_music_expanded);
        setViews(collapsed, false);
        setViews(expanded, true);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int playIcon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playLabel = playing ? "Pause" : "Play";
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(open)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, "Prev", serviceAction("prev", 1))
                .addAction(playIcon, playLabel, serviceAction("toggle", 3))
                .addAction(android.R.drawable.ic_media_next, "Next", serviceAction("next", 2))
                .build();
    }

    private void setViews(RemoteViews views, boolean expanded) {
        int artId = expanded ? R.id.notif_artwork_exp : R.id.notif_artwork;
        int titleId = expanded ? R.id.notif_title_exp : R.id.notif_title;
        int artistId = expanded ? R.id.notif_artist_exp : R.id.notif_artist;
        int prevId = expanded ? R.id.notif_prev_lyric_exp : R.id.notif_prev_lyric;
        int currentId = expanded ? R.id.notif_current_lyric_exp : R.id.notif_current_lyric;
        int nextId = expanded ? R.id.notif_next_lyric_exp : R.id.notif_next_lyric;
        int timeId = expanded ? R.id.notif_cur_time_exp : R.id.notif_cur_time;
        int durationId = expanded ? R.id.notif_duration_exp : R.id.notif_duration;
        int progressId = expanded ? R.id.notif_progress_exp : R.id.notif_progress;
        int prevButtonId = expanded ? R.id.notif_prev_exp : R.id.notif_prev;
        int playButtonId = expanded ? R.id.notif_play_pause_exp : R.id.notif_play_pause;
        int nextButtonId = expanded ? R.id.notif_next_exp : R.id.notif_next;
        if (artwork != null) views.setImageViewBitmap(artId, artwork);
        else views.setImageViewResource(artId, R.mipmap.ic_launcher);
        views.setTextViewText(titleId, title);
        views.setTextViewText(artistId, artist);
        setLyric(views, prevId, previousLyric);
        setLyric(views, currentId, currentLyric);
        setLyric(views, nextId, nextLyric);
        views.setTextViewText(timeId, formatTime(positionMs));
        views.setTextViewText(durationId, formatTime(durationMs));
        int progress = durationMs == 0 ? 0 : (int) Math.min(1000, positionMs * 1000 / durationMs);
        views.setProgressBar(progressId, 1000, progress, false);
        views.setImageViewResource(playButtonId,
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        views.setOnClickPendingIntent(prevButtonId, serviceAction("prev", expanded ? 11 : 1));
        views.setOnClickPendingIntent(playButtonId, serviceAction("toggle", expanded ? 13 : 3));
        views.setOnClickPendingIntent(nextButtonId, serviceAction("next", expanded ? 12 : 2));
    }

    private void setLyric(RemoteViews views, int id, String text) {
        views.setTextViewText(id, text);
        views.setViewVisibility(id, text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String formatTime(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return (seconds / 60) + ":" + String.format(java.util.Locale.US, "%02d", seconds % 60);
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
        else if ("webViewLyrics".equals(action)) handleLyrics(intent);
        else if (ACTION_ARM.equals(action) || "updateNotification".equals(action)) {
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
