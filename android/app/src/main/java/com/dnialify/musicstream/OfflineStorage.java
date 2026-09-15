package com.dnialify.musicstream;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Phase 1: Filesystem wrapper for offline chunks.
 * Path: getFilesDir()/offline/{songId}/{sourceId}/seg_{index}.bin
 * For beta, isolated path is offline-beta (see getBaseDir).
 * All methods return status + error via exception message, additive (no download logic).
 */
public class OfflineStorage {
    private static final String DIR_BETA = "offline-beta";
    private static final String DIR_STABLE = "offline";
    private static final long CAP_BYTES = 500L * 1024 * 1024; // 500 MB

    private final Context context;
    private final boolean isBeta;

    public OfflineStorage(Context ctx) {
        this.context = ctx.getApplicationContext();
        boolean beta = false;
        try {
            String ver = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (ver != null && ver.contains("-beta")) beta = true;
        } catch (Exception ignored) {}
        this.isBeta = beta;
    }

    // For tests, allow explicit channel
    public OfflineStorage(Context ctx, boolean isBeta) {
        this.context = ctx.getApplicationContext();
        this.isBeta = isBeta;
    }

    // For tests: direct File base (no Context)
    private File testBaseDir = null;
    public OfflineStorage(File baseDir) {
        this.context = null;
        this.isBeta = true;
        this.testBaseDir = baseDir;
        if (!testBaseDir.exists()) testBaseDir.mkdirs();
    }

    private File getBaseDir() {
        if (testBaseDir != null) return testBaseDir;
        String dir = isBeta ? DIR_BETA : DIR_STABLE;
        File base = new File(context.getFilesDir(), dir);
        if (!base.exists()) base.mkdirs();
        return base;
    }

    private File getChunkFile(String songId, String sourceId, int index) {
        File base = getBaseDir();
        File songDir = new File(base, sanitize(songId));
        File srcDir = new File(songDir, sanitize(sourceId));
        return new File(srcDir, "seg_" + index + ".bin");
    }

    private static String sanitize(String s) {
        if (s == null) return "unknown";
        return s.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    public static class Result {
        public final boolean success;
        public final String error;
        public Result(boolean success, String error) { this.success = success; this.error = error; }
        public static Result ok() { return new Result(true, null); }
        public static Result fail(String e) { return new Result(false, e); }
    }

    /** Save chunk byte[] to file. */
    public Result saveChunk(String songId, String sourceId, int index, byte[] data) {
        if (songId == null || sourceId == null || data == null) return Result.fail("null args");
        if (index < 0) return Result.fail("negative index");
        try {
            File f = getChunkFile(songId, sourceId, index);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return Result.fail("mkdir failed " + parent);
            // quota check
            long usage = getStorageUsage();
            if (usage + data.length > CAP_BYTES) return Result.fail("quota exceeded 500MB");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
            return Result.ok();
        } catch (IOException e) {
            return Result.fail(e.getMessage());
        }
    }

    /** Read chunk, null if not exists. */
    public byte[] readChunk(String songId, String sourceId, int index) {
        try {
            File f = getChunkFile(songId, sourceId, index);
            if (!f.exists()) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                if (read != buf.length) return null;
                return buf;
            }
        } catch (IOException e) {
            return null;
        }
    }

    public Result deleteChunk(String songId, String sourceId, int index) {
        try {
            File f = getChunkFile(songId, sourceId, index);
            if (!f.exists()) return Result.ok();
            if (!f.delete()) return Result.fail("delete failed");
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    public Result deleteSource(String songId, String sourceId) {
        try {
            File f = getChunkFile(songId, sourceId, 0).getParentFile();
            if (f == null || !f.exists()) return Result.ok();
            deleteRecursive(f);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    public Result deleteSong(String songId) {
        try {
            File base = getBaseDir();
            File songDir = new File(base, sanitize(songId));
            if (!songDir.exists()) return Result.ok();
            deleteRecursive(songDir);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    private void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }

    /** Total bytes in files/offline(-beta)/ */
    public long getStorageUsage() {
        File base = getBaseDir();
        return sizeRecursive(base);
    }

    private long sizeRecursive(File f) {
        if (!f.exists()) return 0;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) sum += sizeRecursive(c);
        return sum;
    }

    public long getFreeQuota() {
        long used = getStorageUsage();
        long free = CAP_BYTES - used;
        return free < 0 ? 0 : free;
    }

    public long getCapBytes() { return CAP_BYTES; }

    public File getBaseDirForTest() { return getBaseDir(); }
}
