package com.dnialify.musicstream;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Phase 1: SQLite helper for offline.db
 * Additive, no logic yet - just schema ready.
 * DB name isolated per channel: offline-beta.db for beta, offline.db for stable (but Phase 1 always beta).
 */
public class OfflineDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME_BETA = "offline-beta.db";
    private static final String DB_NAME_STABLE = "offline.db";
    private static final int DB_VERSION = 1;

    private static OfflineDatabase instanceBeta;
    private static OfflineDatabase instanceStable;

    public static synchronized OfflineDatabase getInstance(Context ctx, boolean isBeta) {
        String name = isBeta ? DB_NAME_BETA : DB_NAME_STABLE;
        if (isBeta) {
            if (instanceBeta == null) instanceBeta = new OfflineDatabase(ctx.getApplicationContext(), name);
            return instanceBeta;
        } else {
            if (instanceStable == null) instanceStable = new OfflineDatabase(ctx.getApplicationContext(), name);
            return instanceStable;
        }
    }

    // Default: channel from versionName (BUILD_CHANNEL proxy: version contains "-beta" → beta)
    // server.js:8 BUILD_CHANNEL env, client APP_VERSION beta check public/app.js:2, android versionName sync via package.json
    public static synchronized OfflineDatabase getInstance(Context ctx) {
        boolean isBeta = false;
        try {
            String ver = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
            if (ver != null && ver.contains("-beta")) isBeta = true;
        } catch (Exception ignored) {}
        return getInstance(ctx, isBeta);
    }

    private OfflineDatabase(Context ctx, String name) {
        super(ctx, name, null, DB_VERSION);
    }

    // For tests: allow custom db name (e.g., "test-offline.db" or ":memory:")
    public OfflineDatabase(Context ctx, String name, int version) {
        super(ctx, name, null, version);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS songs (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, duration TEXT, created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, type TEXT NOT NULL, stream_kind TEXT NOT NULL, content_length INTEGER, mime_type TEXT, chunk_size INTEGER NOT NULL DEFAULT 1048576, total_segments INTEGER, status TEXT NOT NULL, last_resolved_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sources_song_id ON sources(song_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS segments (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, idx INTEGER NOT NULL, byte_range TEXT NOT NULL, cached INTEGER NOT NULL DEFAULT 0, local_path TEXT, size INTEGER, checksum TEXT, updated_at INTEGER NOT NULL, UNIQUE(source_id, idx))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_segments_source_id ON segments(source_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS assets (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, type TEXT NOT NULL, source_url TEXT, cached INTEGER NOT NULL DEFAULT 0, local_path TEXT, mime_type TEXT, size INTEGER, payload_json TEXT, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_assets_song_id ON assets(song_id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS offline_jobs (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, source_id TEXT NOT NULL, state TEXT NOT NULL, total_items INTEGER NOT NULL, done_items INTEGER NOT NULL DEFAULT 0, failed_items INTEGER NOT NULL DEFAULT 0, error TEXT, started_at INTEGER, updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_song_id ON offline_jobs(song_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // NEVER drop & recreate - user cache would be lost.
        // Incremental migrations only, e.g.:
        // if (oldVersion < 2) db.execSQL("ALTER TABLE sources ADD COLUMN new_col TEXT");
        // if (oldVersion < 3) { ... }
        // Schema file android/app/src/main/assets/offline-schema.sql is source of truth for fresh installs,
        // but upgrades must preserve data via ALTER. Current DB_VERSION=1, so no migration yet.
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }
}
