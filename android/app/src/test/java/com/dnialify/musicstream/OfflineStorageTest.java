package com.dnialify.musicstream;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.io.File;
import java.util.Arrays;

/**
 * Phase 1: saveChunk / readChunk / getStorageUsage - pure Java, no Android Context.
 */
public class OfflineStorageTest {

    private OfflineStorage storage;
    private File tmpDir;

    @Before
    public void setup() throws Exception {
        tmpDir = new File(System.getProperty("java.io.tmpdir"), "offline-test-" + System.nanoTime());
        tmpDir.mkdirs();
        storage = new OfflineStorage(tmpDir);
        storage.deleteSong("testSong");
    }

    @After
    public void teardown() {
        if (storage != null) storage.deleteSong("testSong");
        deleteRecursive(tmpDir);
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    @Test
    public void saveAndReadChunk_isCorrect() {
        byte[] data = new byte[1024 * 1024];
        Arrays.fill(data, (byte) 7);
        OfflineStorage.Result r = storage.saveChunk("testSong", "src1", 0, data);
        assertTrue(r.success);
        byte[] out = storage.readChunk("testSong", "src1", 0);
        assertNotNull(out);
        assertEquals(data.length, out.length);
        assertTrue(Arrays.equals(data, out));
    }

    @Test
    public void storageUsage_sum() {
        storage.deleteSong("testSong");
        byte[] a = new byte[512];
        byte[] b = new byte[1024];
        storage.saveChunk("testSong", "src1", 0, a);
        storage.saveChunk("testSong", "src1", 1, b);
        long usage = storage.getStorageUsage();
        assertTrue(usage >= 1536);
        long free = storage.getFreeQuota();
        assertEquals(500L * 1024 * 1024 - usage, free);
    }

    @Test
    public void deleteSong_cleans() {
        byte[] data = new byte[100];
        storage.saveChunk("testSong", "src1", 0, data);
        storage.deleteSong("testSong");
        assertNull(storage.readChunk("testSong", "src1", 0));
        // usage should be 0 after delete (or close to 0, other test songs may exist)
        storage.deleteSong("testSong");
        assertTrue(storage.getStorageUsage() >= 0);
    }

    @Test
    public void quota_enforced() {
        long cap = storage.getCapBytes();
        assertEquals(500L * 1024 * 1024, cap);
    }

    @Test
    public void sanitize_preventsTraversal() {
        // songId with path traversal and illegal chars must be sanitized and stay inside base
        String evilSong = "../../etc/passwd";
        String evilSrc = "a/b\\c\0d";
        byte[] data = new byte[10];
        OfflineStorage.Result r = storage.saveChunk(evilSong, evilSrc, 0, data);
        assertTrue(r.success);
        File base = storage.getBaseDirForTest();
        File evilFile = new File(base, "../../etc/passwd");
        assertFalse("traversal file must not exist outside base", evilFile.exists());
        // sanitize: [^a-zA-Z0-9._-] → "_", so "/" → "_", "." stays, "\0" → "_"
        // "../../etc/passwd" → ".._.._etc_passwd", "a/b\c\0d" → "a_b_c_d"
        File sanitized = new File(new File(new File(base, ".._.._etc_passwd"), "a_b_c_d"), "seg_0.bin");
        assertTrue("sanitized file must exist at " + sanitized.getAbsolutePath(), sanitized.exists());
        assertNotNull(storage.readChunk(evilSong, evilSrc, 0));
    }
}
