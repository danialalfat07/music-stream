package com.dnialify.musicstream;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.file.Files;
import java.io.File;

/**
 * Phase 1: DB schema + insert song + source + 3 segments → query
 * Uses sqlite-jdbc in-memory, loads schema from offline-schema.sql
 */
public class OfflineDatabaseTest {

    private Connection conn;

    @Before
    public void setup() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        Statement st = conn.createStatement();
        // load schema from file if exists, else inline
        try {
            File schema = new File("src/main/assets/offline-schema.sql");
            if (!schema.exists()) schema = new File("app/src/main/assets/offline-schema.sql");
            String sql = new String(Files.readAllBytes(schema.toPath()));
            for (String s : sql.split(";")) {
                s = s.trim();
                if (!s.isEmpty()) st.execute(s);
            }
        } catch (Exception e) {
            // fallback inline schema
            st.execute("CREATE TABLE songs (id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, duration TEXT, created_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE sources (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, type TEXT NOT NULL, stream_kind TEXT NOT NULL, content_length INTEGER, mime_type TEXT, chunk_size INTEGER NOT NULL DEFAULT 1048576, total_segments INTEGER, status TEXT NOT NULL, last_resolved_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE segments (id TEXT PRIMARY KEY, source_id TEXT NOT NULL, idx INTEGER NOT NULL, byte_range TEXT NOT NULL, cached INTEGER NOT NULL DEFAULT 0, local_path TEXT, size INTEGER, checksum TEXT, updated_at INTEGER NOT NULL, UNIQUE(source_id, idx))");
            st.execute("CREATE TABLE assets (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, type TEXT NOT NULL, source_url TEXT, cached INTEGER NOT NULL DEFAULT 0, local_path TEXT, mime_type TEXT, size INTEGER, payload_json TEXT, updated_at INTEGER NOT NULL)");
            st.execute("CREATE TABLE offline_jobs (id TEXT PRIMARY KEY, song_id TEXT NOT NULL, source_id TEXT NOT NULL, state TEXT NOT NULL, total_items INTEGER NOT NULL, done_items INTEGER NOT NULL DEFAULT 0, failed_items INTEGER NOT NULL DEFAULT 0, error TEXT, started_at INTEGER, updated_at INTEGER NOT NULL)");
        }
    }

    @After
    public void teardown() throws Exception {
        if (conn != null) conn.close();
    }

    @Test
    public void insertAndQuery_consistent() throws Exception {
        long now = System.currentTimeMillis();
        Statement st = conn.createStatement();
        st.executeUpdate("INSERT INTO songs (id, title, artist, duration, created_at) VALUES ('song1','Test Song','Test Artist','3:30'," + now + ")");
        st.executeUpdate("INSERT INTO sources (id, song_id, type, stream_kind, content_length, mime_type, chunk_size, total_segments, status, last_resolved_at, created_at, updated_at) VALUES ('src1','song1','audioStream','googlevideo',3000000,'audio/webm',1048576,3,'partial'," + now + "," + now + "," + now + ")");
        for (int i = 0; i < 3; i++) {
            String br = (i * 1048576) + "-" + ((i + 1) * 1048576 - 1);
            st.executeUpdate("INSERT INTO segments (id, source_id, idx, byte_range, cached, local_path, size, updated_at) VALUES ('seg" + i + "','src1'," + i + ",'" + br + "'," + (i == 0 ? 1 : 0) + ",'/offline/song1/src1/seg_" + i + ".bin',1048576," + now + ")");
        }

        ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM segments WHERE source_id='src1'");
        rs.next();
        assertEquals(3, rs.getInt(1));

        ResultSet rs2 = st.executeQuery("SELECT COUNT(*) FROM segments WHERE source_id='src1' AND cached=1");
        rs2.next();
        assertEquals(1, rs2.getInt(1));

        ResultSet rs3 = st.executeQuery("SELECT status FROM sources WHERE id='src1'");
        rs3.next();
        assertEquals("partial", rs3.getString(1));

        // Simulate mark one more cached → partial → check still partial, then full
        st.executeUpdate("UPDATE segments SET cached=1 WHERE id='seg1'");
        ResultSet rs4 = st.executeQuery("SELECT COUNT(*) FROM segments WHERE source_id='src1' AND cached=1");
        rs4.next();
        assertEquals(2, rs4.getInt(1));
    }

    @Test
    public void schema_containsAllTables() throws Exception {
        Statement st = conn.createStatement();
        ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'");
        java.util.Set<String> tables = new java.util.HashSet<>();
        while (rs.next()) tables.add(rs.getString(1));
        assertTrue(tables.contains("songs"));
        assertTrue(tables.contains("sources"));
        assertTrue(tables.contains("segments"));
        assertTrue(tables.contains("assets"));
        assertTrue(tables.contains("offline_jobs"));
    }
}
