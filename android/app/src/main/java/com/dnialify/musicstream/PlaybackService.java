package com.dnialify.musicstream;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    private static final String ACTION_PLAY = "com.dnialify.musicstream.PLAY";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ARTIST = "artist";
    private static final String EXTRA_ARTWORK = "artwork";
    private static final String ACTION_ARM = "arm";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1001;
    private ExoPlayer player;
    private MediaSession session;
    // WebView-driven state for notification (not ExoPlayer playback)
    private String webViewTitle = null;
    private String webViewArtist = null;
    private String webViewArtwork = null;
    private Bitmap webViewArtworkBitmap = null;
    private String webViewPrevLyric = null;
    private String webViewCurrentLyric = null;
    private String webViewNextLyric = null;
    private boolean webViewIsPlaying = false;
    private long webViewPositionMs = 0;
    private long webViewDurationMs = 0;

    public static void arm(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class).setAction(ACTION_ARM));
    }

    public static void play(Context context, String url, String title, String artist, String artwork) {
        Intent i = new Intent(context, PlaybackService.class).setAction(ACTION_PLAY)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_ARTWORK, artwork);
        ContextCompat.startForegroundService(context, i);
    }

    public static void pause(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class).setAction("pause"));
    }

    public static void seek(Context context, double seconds) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class).setAction("seek")
                .putExtra("seconds", seconds));
    }

    public static boolean isPlaying() { return instance != null && instance.player != null && instance.player.isPlaying(); }
    public static boolean isEnded() { return instance != null && instance.player != null && instance.player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED; }
    public static double currentTime() { return instance == null || instance.player == null ? 0 : instance.player.getCurrentPosition() / 1000d; }
    public static double duration() { return instance == null || instance.player == null ? 0 : Math.max(0, instance.player.getDuration() / 1000d); }
    public static void speed(Context context, double value) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class).setAction("speed").putExtra("value", value));
    }
    public static void volume(Context context, double value) {
        ContextCompat.startForegroundService(context, new Intent(context, PlaybackService.class).setAction("volume").putExtra("value", value));
    }
    public static void updateNotificationStatic(Context context, String title, String artist) {
        if (instance != null) { instance.updateNotification(title, artist); return; }
        Intent i = new Intent(context, PlaybackService.class).setAction("updateNotification")
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist);
        ContextCompat.startForegroundService(context, i);
    }

    // WebView -> Native bridge for notification (incremental, minimal)
    public static void updateWebViewState(Context context, String title, String artist, String artwork, boolean isPlaying, long positionMs, long durationMs) {
        if (instance != null) { instance.handleWebViewState(title, artist, artwork, isPlaying, positionMs, durationMs); return; }
        Intent i = new Intent(context, PlaybackService.class).setAction("webViewState")
                .putExtra(EXTRA_TITLE, title).putExtra(EXTRA_ARTIST, artist).putExtra(EXTRA_ARTWORK, artwork)
                .putExtra("isPlaying", isPlaying).putExtra("positionMs", positionMs).putExtra("durationMs", durationMs);
        ContextCompat.startForegroundService(context, i);
    }

    public static void updateLyricsStatic(Context context, String prev, String current, String next) {
        if (instance != null) { instance.handleLyrics(prev, current, next); return; }
        Intent i = new Intent(context, PlaybackService.class).setAction("webViewLyrics")
                .putExtra("prev", prev).putExtra("current", current).putExtra("next", next);
        ContextCompat.startForegroundService(context, i);
    }

    private static PlaybackService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();
        player = new ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                android.util.Log.e("DnialifyPlayback", "Media3 playback failed", error);
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                // For WebView-driven playback, isPlaying comes from WebView; keep ExoPlayer listener minimal
                // Do not override WebView state with idle player
                if (webViewTitle != null) return;
                try {
                    MediaItem cur = player.getCurrentMediaItem();
                    String t = null, a = null;
                    if (cur != null && cur.mediaMetadata != null) {
                        t = cur.mediaMetadata.title != null ? cur.mediaMetadata.title.toString() : null;
                        a = cur.mediaMetadata.artist != null ? cur.mediaMetadata.artist.toString() : null;
                    }
                    updateNotification(t, a);
                } catch (Exception ignored) {}
            }
        });
        session = new MediaSession.Builder(this, player).build();
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    CHANNEL_ID, "Playback", android.app.NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.dnialify.musicstream.R.mipmap.ic_launcher)
                .setContentTitle("Dnialify Music Stream")
                .setContentText("Playback ready")
                .setOngoing(false)
                .setCategory(android.app.Notification.CATEGORY_TRANSPORT)
                .build());
    }

    private void handleWebViewState(String title, String artist, String artwork, boolean isPlaying, long positionMs, long durationMs) {
        if (title != null) webViewTitle = title;
        if (artist != null) webViewArtist = artist;
        if (artwork != null) webViewArtwork = artwork;
        webViewIsPlaying = isPlaying;
        webViewPositionMs = positionMs;
        webViewDurationMs = durationMs;
        // Update MediaSession metadata for lockscreen (without putting VIDEO_URL into ExoPlayer)
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder().setTitle(webViewTitle).setArtist(webViewArtist);
            if (webViewArtwork != null && !webViewArtwork.isEmpty()) mb.setArtworkUri(android.net.Uri.parse(webViewArtwork));
            // Use player dummy item to publish metadata to MediaSession for lockscreen
            if (player.getMediaItemCount() == 0) {
                MediaItem dummy = new MediaItem.Builder().setMediaId("webview-dummy").setUri(android.net.Uri.EMPTY).setMediaMetadata(mb.build()).build();
                player.setMediaItem(dummy);
                player.prepare();
            } else {
                // Update existing item metadata
                MediaItem cur = player.getCurrentMediaItem();
                if (cur != null) {
                    MediaItem updated = cur.buildUpon().setMediaMetadata(mb.build()).build();
                    player.replaceMediaItem(0, updated);
                }
            }
            player.setPlayWhenReady(isPlaying);
            if (isPlaying) player.play(); else player.pause();
            // Seek dummy to reflect position for lockscreen progress (if duration known)
            if (durationMs > 0 && positionMs >= 0) {
                try { player.seekTo(positionMs); } catch (Exception ignored) {}
            }
        } catch (Exception e) { android.util.Log.w("DnialifyPlayback", "handleWebViewState mediaSession update fail " + e); }
        // Artwork async load
        if (webViewArtwork != null && !webViewArtwork.isEmpty()) {
            final String artUrl = webViewArtwork;
            new Thread(() -> {
                try {
                    java.net.URL url = new java.net.URL(artUrl);
                    Bitmap bmp = BitmapFactory.decodeStream(url.openConnection().getInputStream());
                    if (bmp != null) { webViewArtworkBitmap = bmp; }
                } catch (Exception ignored) {}
                // Update notification on UI thread after artwork fetch
                try { updateNotificationWithWebViewState(); } catch (Exception ignored) {}
            }).start();
        }
        updateNotificationWithWebViewState();
    }

    private void handleLyrics(String prev, String current, String next) {
        webViewPrevLyric = prev;
        webViewCurrentLyric = current;
        webViewNextLyric = next;
        updateNotificationWithWebViewState();
    }

    private void updateNotification(String title, String artist) {
        // Fallback to WebView state if available
        if (webViewTitle != null) { updateNotificationWithWebViewState(); return; }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String t = title != null && !title.isEmpty() ? title : "Dnialify Music Stream";
        String a = artist != null && !artist.isEmpty() ? artist : "Playing";
        boolean isPlaying = player != null && player.isPlaying();
        PendingIntent prevPI = PendingIntent.getService(this, 1, new Intent(this, PlaybackService.class).setAction("prev"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextPI = PendingIntent.getService(this, 2, new Intent(this, PlaybackService.class).setAction("next"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent togglePI = PendingIntent.getService(this, 3, new Intent(this, PlaybackService.class).setAction("toggle"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playTitle = isPlaying ? "Pause" : "Play";
        androidx.core.app.NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.dnialify.musicstream.R.mipmap.ic_launcher)
                .setContentTitle(t)
                .setContentText(a)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setCategory(android.app.Notification.CATEGORY_TRANSPORT)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPI)
                .addAction(playIcon, playTitle, togglePI)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPI);
        try { startForeground(NOTIFICATION_ID, nb.build()); } catch (Exception e) { manager.notify(NOTIFICATION_ID, nb.build()); }
    }

    private void updateNotificationWithWebViewState() {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String t = webViewTitle != null && !webViewTitle.isEmpty() ? webViewTitle : "Dnialify Music Stream";
        String a = webViewArtist != null && !webViewArtist.isEmpty() ? webViewArtist : "MusicStream";
        boolean isPlaying = webViewIsPlaying;
        int playIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
        String playTitle = isPlaying ? "Pause" : "Play";
        PendingIntent prevPI = PendingIntent.getService(this, 1, new Intent(this, PlaybackService.class).setAction("prev"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nextPI = PendingIntent.getService(this, 2, new Intent(this, PlaybackService.class).setAction("next"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent togglePI = PendingIntent.getService(this, 3, new Intent(this, PlaybackService.class).setAction("toggle"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // RemoteViews collapsed/expanded with 3-layer lyrics
        RemoteViews collapsed = new RemoteViews(getPackageName(), R.layout.notification_music_collapsed);
        RemoteViews expanded = new RemoteViews(getPackageName(), R.layout.notification_music_expanded);
        // Artwork
        if (webViewArtworkBitmap != null) {
            collapsed.setImageViewBitmap(R.id.notif_artwork, webViewArtworkBitmap);
            expanded.setImageViewBitmap(R.id.notif_artwork_exp, webViewArtworkBitmap);
        } else {
            collapsed.setImageViewResource(R.id.notif_artwork, R.mipmap.ic_launcher);
            expanded.setImageViewResource(R.id.notif_artwork_exp, R.mipmap.ic_launcher);
        }
        collapsed.setTextViewText(R.id.notif_title, t);
        expanded.setTextViewText(R.id.notif_title_exp, t);
        collapsed.setTextViewText(R.id.notif_artist, a);
        expanded.setTextViewText(R.id.notif_artist_exp, a);
        // Lyrics: prev/current/next — current bold, prev/next dim, gone if empty
        boolean hasLyrics = webViewCurrentLyric != null && !webViewCurrentLyric.isEmpty();
        if (hasLyrics) {
            collapsed.setViewVisibility(R.id.notif_prev_lyric, webViewPrevLyric != null && !webViewPrevLyric.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            collapsed.setViewVisibility(R.id.notif_current_lyric, android.view.View.VISIBLE);
            collapsed.setViewVisibility(R.id.notif_next_lyric, webViewNextLyric != null && !webViewNextLyric.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            collapsed.setTextViewText(R.id.notif_prev_lyric, webViewPrevLyric != null ? webViewPrevLyric : "");
            collapsed.setTextViewText(R.id.notif_current_lyric, webViewCurrentLyric);
            collapsed.setTextViewText(R.id.notif_next_lyric, webViewNextLyric != null ? webViewNextLyric : "");
            expanded.setViewVisibility(R.id.notif_prev_lyric_exp, webViewPrevLyric != null && !webViewPrevLyric.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            expanded.setViewVisibility(R.id.notif_current_lyric_exp, android.view.View.VISIBLE);
            expanded.setViewVisibility(R.id.notif_next_lyric_exp, webViewNextLyric != null && !webViewNextLyric.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
            expanded.setTextViewText(R.id.notif_prev_lyric_exp, webViewPrevLyric != null ? webViewPrevLyric : "");
            expanded.setTextViewText(R.id.notif_current_lyric_exp, webViewCurrentLyric);
            expanded.setTextViewText(R.id.notif_next_lyric_exp, webViewNextLyric != null ? webViewNextLyric : "");
        } else {
            collapsed.setViewVisibility(R.id.notif_prev_lyric, android.view.View.GONE);
            collapsed.setViewVisibility(R.id.notif_current_lyric, android.view.View.GONE);
            collapsed.setViewVisibility(R.id.notif_next_lyric, android.view.View.GONE);
            expanded.setViewVisibility(R.id.notif_prev_lyric_exp, android.view.View.GONE);
            expanded.setViewVisibility(R.id.notif_current_lyric_exp, android.view.View.GONE);
            expanded.setViewVisibility(R.id.notif_next_lyric_exp, android.view.View.GONE);
        }
        // Progress and times
        int progress = 0;
        if (webViewDurationMs > 0) progress = (int) Math.min(1000, (webViewPositionMs * 1000 / webViewDurationMs));
        String curStr = formatTime(webViewPositionMs / 1000);
        String durStr = formatTime(webViewDurationMs / 1000);
        collapsed.setTextViewText(R.id.notif_cur_time, curStr);
        collapsed.setTextViewText(R.id.notif_duration, durStr);
        collapsed.setProgressBar(R.id.notif_progress, 1000, progress, false);
        expanded.setTextViewText(R.id.notif_cur_time_exp, curStr);
        expanded.setTextViewText(R.id.notif_duration_exp, durStr);
        expanded.setProgressBar(R.id.notif_progress_exp, 1000, progress, false);
        // Controls
        collapsed.setImageViewResource(R.id.notif_play_pause, playIcon);
        expanded.setImageViewResource(R.id.notif_play_pause_exp, playIcon);
        collapsed.setOnClickPendingIntent(R.id.notif_prev, prevPI);
        collapsed.setOnClickPendingIntent(R.id.notif_play_pause, togglePI);
        collapsed.setOnClickPendingIntent(R.id.notif_next, nextPI);
        expanded.setOnClickPendingIntent(R.id.notif_prev_exp, prevPI);
        expanded.setOnClickPendingIntent(R.id.notif_play_pause_exp, togglePI);
        expanded.setOnClickPendingIntent(R.id.notif_next_exp, nextPI);
        // Build notification: custom view for shade (lyrics+progress) — lockscreen simple via MediaSession (system shows artwork/title/controls, not 3-layer lyrics)
        NotificationCompat.Builder nb = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
                .setCustomContentView(collapsed)
                .setCustomBigContentView(expanded)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .addAction(android.R.drawable.ic_media_previous, "Prev", prevPI)
                .addAction(playIcon, playTitle, togglePI)
                .addAction(android.R.drawable.ic_media_next, "Next", nextPI);
        // Lockscreen: MediaSession provides simple artwork/title/controls, custom lyrics not shown (as intended)
        try { startForeground(NOTIFICATION_ID, nb.build()); } catch (Exception e) { manager.notify(NOTIFICATION_ID, nb.build()); }
    }

    private String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if ("webViewState".equals(action)) {
                handleWebViewState(intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_ARTIST), intent.getStringExtra(EXTRA_ARTWORK),
                        intent.getBooleanExtra("isPlaying", false), intent.getLongExtra("positionMs", 0), intent.getLongExtra("durationMs", 0));
                return START_STICKY;
            } else if ("webViewLyrics".equals(action)) {
                handleLyrics(intent.getStringExtra("prev"), intent.getStringExtra("current"), intent.getStringExtra("next"));
                return START_STICKY;
            } else if (ACTION_ARM.equals(action)) {
                updateNotification(null, null);
                return START_STICKY;
            } else if ("updateNotification".equals(action)) {
                updateNotification(intent.getStringExtra(EXTRA_TITLE), intent.getStringExtra(EXTRA_ARTIST));
                return START_STICKY;
            } else if (ACTION_PLAY.equals(action)) {
                String title = intent.getStringExtra(EXTRA_TITLE);
                String artist = intent.getStringExtra(EXTRA_ARTIST);
                String artwork = intent.getStringExtra(EXTRA_ARTWORK);
                MediaMetadata.Builder metadataBuilder = new MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist);
                if (artwork != null && !artwork.isEmpty()) metadataBuilder.setArtworkUri(android.net.Uri.parse(artwork));
                MediaMetadata metadata = metadataBuilder.build();
                MediaItem item = new MediaItem.Builder()
                        .setUri(intent.getStringExtra(EXTRA_URL)).setMediaMetadata(metadata).build();
                player.setMediaItem(item);
                player.prepare();
                player.setPlayWhenReady(true);
                player.play();
                updateNotification(title, artist);
            } else if ("prev".equals(action)) {
                // WebView-driven: delegate to WebView
                if (webViewTitle != null) {
                    try { if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.prevTrack) prevTrack();", null); } catch (Exception ignored) {}
                    }); } catch (Exception ignored) {}
                    return START_STICKY;
                }
                try {
                    if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.prevTrack) prevTrack();", null); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
                try { player.seekTo(0); } catch (Exception ignored) {}
            } else if ("next".equals(action)) {
                if (webViewTitle != null) {
                    try { if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.nextTrack) nextTrack(false);", null); } catch (Exception ignored) {}
                    }); } catch (Exception ignored) {}
                    return START_STICKY;
                }
                try {
                    if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.nextTrack) nextTrack(false);", null); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            } else if ("toggle".equals(action)) {
                if (webViewTitle != null) {
                    // WebView-driven toggle
                    try { if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.togglePlay) togglePlay();", null); } catch (Exception ignored) {}
                    }); } catch (Exception ignored) {}
                    // Optimistically flip state until WebView pushes new state
                    webViewIsPlaying = !webViewIsPlaying;
                    updateNotificationWithWebViewState();
                    return START_STICKY;
                }
                if (player.isPlaying()) player.pause(); else player.play();
                try {
                    MediaItem cur = player.getCurrentMediaItem();
                    String t2 = cur != null && cur.mediaMetadata != null && cur.mediaMetadata.title != null ? cur.mediaMetadata.title.toString() : null;
                    String a2 = cur != null && cur.mediaMetadata != null && cur.mediaMetadata.artist != null ? cur.mediaMetadata.artist.toString() : null;
                    updateNotification(t2, a2);
                } catch (Exception ignored) {}
            } else if ("pause".equals(action)) {
                if (webViewTitle != null) { webViewIsPlaying = false; updateNotificationWithWebViewState(); return START_STICKY; }
                player.pause();
                try { player.getCurrentMediaItem(); } catch (Exception ignored) {}
            } else if ("seek".equals(action)) {
                if (webViewTitle != null) {
                    double secs = intent.getDoubleExtra("seconds", 0);
                    try { if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.Player && window.Player.yt) { try{Player.yt.seekTo(" + secs + ",true);}catch(e){} } else if(window.Player && window.Player.audio) Player.audio.currentTime=" + secs + ";", null); } catch (Exception ignored) {}
                    }); } catch (Exception ignored) {}
                    return START_STICKY;
                }
                player.seekTo((long) (intent.getDoubleExtra("seconds", 0) * 1000));
            } else if ("speed".equals(action)) {
                player.setPlaybackSpeed((float) intent.getDoubleExtra("value", 1));
            } else if ("volume".equals(action)) {
                player.setVolume((float) intent.getDoubleExtra("value", 1));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (session != null) session.release();
        if (player != null) player.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
}
