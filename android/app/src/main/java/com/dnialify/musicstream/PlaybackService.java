package com.dnialify.musicstream;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

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
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class PlaybackService extends MediaSessionService {
    private static final String ACTION_PLAY = "com.dnialify.musicstream.PLAY";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_ARTIST = "artist";
    private static final String EXTRA_ARTWORK = "artwork";
    private static final String ACTION_ARM = "arm";
    // Isolated experiment: dummy MediaSession state (silent local asset, not YouTube)
    private static final String ACTION_TEST_MEDIA = "testMedia";
    private static final String EXTRA_STATE = "state";
    private static final String EXTRA_DURATION_MS = "durationMs";
    private static final String EXTRA_POSITION_MS = "positionMs";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1001;
    private ExoPlayer player;
    private MediaSession session;

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

    private static PlaybackService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();
        player = new ExoPlayer.Builder(this).setAudioAttributes(attrs, true).build();
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                android.util.Log.e("DnialifyPlayback", "Media3 playback failed", error);
            }
            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                android.util.Log.d("DnialifyDiag", "PlaybackService onIsPlayingChanged isPlaying=" + isPlaying + " state=" + player.getPlaybackState());
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                android.util.Log.d("DnialifyDiag", "PlaybackService onPlaybackStateChanged state=" + state + " isPlaying=" + player.isPlaying());
            }
        });
        session = new MediaSession.Builder(this, player).build();
        // Media3 DefaultMediaNotificationProvider renders MediaStyle + token automatically
        try {
            setMediaNotificationProvider(new DefaultMediaNotificationProvider(this));
        } catch (Exception e) {
            android.util.Log.e("DnialifyPlayback", "DefaultMediaNotificationProvider failed", e);
        }
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new android.app.NotificationChannel(
                    CHANNEL_ID, "Playback", android.app.NotificationManager.IMPORTANCE_LOW));
        }
        // Keep foreground via MediaSessionService default provider; also show placeholder until testMedia
        startForeground(NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.dnialify.musicstream.R.mipmap.ic_launcher)
                .setContentTitle("Dnialify Music Stream")
                .setContentText("Playback ready — run testMedia via ADB")
                .setOngoing(false)
                .setCategory(android.app.Notification.CATEGORY_TRANSPORT)
                .build());
    }

    private void updateNotification(String title, String artist) {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String t = title != null && !title.isEmpty() ? title : "Dnialify Music Stream";
        String a = artist != null && !artist.isEmpty() ? artist : "Playing";
        boolean isPlaying = player != null && player.isPlaying();
        // PendingIntents for basic controls
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
        // update foreground notification
        try { startForeground(NOTIFICATION_ID, nb.build()); } catch (Exception e) { manager.notify(NOTIFICATION_ID, nb.build()); }
    }

    private void applyTestMedia(Intent intent) {
        String title = intent.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "Dnialify Test Track";
        String artist = intent.getStringExtra(EXTRA_ARTIST);
        if (artist == null || artist.isEmpty()) artist = "Dnialify Music Stream";
        String artwork = intent.getStringExtra(EXTRA_ARTWORK);
        // artwork fallback to mipmap ic_launcher if not provided
        android.net.Uri artworkUri = null;
        if (artwork != null && !artwork.isEmpty()) {
            try { artworkUri = android.net.Uri.parse(artwork); } catch (Exception ignored) {}
        }
        if (artworkUri == null) {
            artworkUri = android.net.Uri.parse("android.resource://" + getPackageName() + "/" + com.dnialify.musicstream.R.mipmap.ic_launcher);
        }
        int durationMs = intent.getIntExtra(EXTRA_DURATION_MS, 180000); // requested dummy, actual asset 1.5s loop
        int positionMs = intent.getIntExtra(EXTRA_POSITION_MS, 30000);
        String state = intent.getStringExtra(EXTRA_STATE);
        boolean wantPlaying = state == null || !"PAUSED".equalsIgnoreCase(state);
        android.util.Log.d("DnialifyDiag", "testMedia title=" + title + " artist=" + artist + " state=" + (wantPlaying?"PLAYING":"PAUSED") + " durationMs=" + durationMs + " positionMs=" + positionMs);

        // Build silent local asset MediaItem (experiment only, not YouTube) — 3 items so NEXT/PREV available
        android.net.Uri silentUri = android.net.Uri.parse("android.resource://" + getPackageName() + "/" + com.dnialify.musicstream.R.raw.silent);
        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setArtworkUri(artworkUri)
                .setIsPlayable(true)
                .build();
        MediaItem item1 = new MediaItem.Builder().setMediaId("testMedia-dummy-1").setUri(silentUri).setMediaMetadata(metadata).build();
        MediaItem item2 = new MediaItem.Builder().setMediaId("testMedia-dummy-2").setUri(silentUri).setMediaMetadata(metadata).build();
        MediaItem item3 = new MediaItem.Builder().setMediaId("testMedia-dummy-3").setUri(silentUri).setMediaMetadata(metadata).build();
        try {
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            java.util.List<MediaItem> list = new java.util.ArrayList<>();
            list.add(item1); list.add(item2); list.add(item3);
            player.setMediaItems(list, 1, positionMs > 0 ? positionMs : 0);
            player.prepare();
            player.setPlayWhenReady(wantPlaying);
            if (wantPlaying) player.play(); else player.pause();
            android.util.Log.d("DnialifyDiag", "testMedia prepared 3 silent items repeat ALL wantPlaying=" + wantPlaying);
        } catch (Exception e) {
            android.util.Log.e("DnialifyPlayback", "testMedia failed", e);
        }
        // DefaultMediaNotificationProvider will auto-update foreground notification with MediaStyle
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_TEST_MEDIA.equals(action)) {
                applyTestMedia(intent);
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
                // delegate to WebView queue if available
                try {
                    if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.prevTrack) prevTrack();", null); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
                // fallback: seek to 0
                try { player.seekTo(0); } catch (Exception ignored) {}
            } else if ("next".equals(action)) {
                try {
                    if (MainActivity.current != null) MainActivity.current.runOnUiThread(() -> {
                        try { MainActivity.current.getBridge().eval("if(window.nextTrack) nextTrack(false);", null); } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            } else if ("toggle".equals(action)) {
                if (player.isPlaying()) player.pause(); else player.play();
                // update icon
                try {
                    MediaItem cur = player.getCurrentMediaItem();
                    String t2 = cur != null && cur.mediaMetadata != null && cur.mediaMetadata.title != null ? cur.mediaMetadata.title.toString() : null;
                    String a2 = cur != null && cur.mediaMetadata != null && cur.mediaMetadata.artist != null ? cur.mediaMetadata.artist.toString() : null;
                    updateNotification(t2, a2);
                } catch (Exception ignored) {}
            } else if ("pause".equals(action)) {
                player.pause();
                // keep notification but update state
                try { player.getCurrentMediaItem(); } catch (Exception ignored) {}
            } else if ("seek".equals(action)) {
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
