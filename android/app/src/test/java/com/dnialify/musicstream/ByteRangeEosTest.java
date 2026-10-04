package com.dnialify.musicstream;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Byte-range EOS classification: pure function, no Android dependencies.
 * Verdict genuine when everything available has been consumed (within
 * 1 chunk = 102400 bytes), regardless of metadata duration.
 */
public class ByteRangeEosTest {

    private static final long CHUNK = 102400L;
    private static final long AVAIL = 3584000L; // k15wcUamnJA .part size

    @Test
    public void chunkSizeMatchesProduction() {
        assertEquals(102400, SongCache.chunkBytes());
    }

    @Test
    public void consumedEqualsAvailable_isGenuine() {
        // Case 1+6: k15wcUamnJA — reader consumed all 3584000 available bytes.
        // Byte verdict wins over pos=194794 vs metadata dur=253021.
        assertTrue(SongCache.isByteRangeEos(3584000L, AVAIL, CHUNK));
    }

    @Test
    public void bufferExhaustedWhileDataRemains_isPremature_running() {
        // Case 2: playback at 1843200, 1740800 bytes still unplayed,
        // writer RUNNING (more data coming) -> keep downloading, recover.
        assertFalse(SongCache.isByteRangeEos(1843200L, AVAIL, CHUNK));
    }

    @Test
    public void bufferExhaustedWhileDataRemains_isPremature_stalled() {
        // Case 3: same byte position, writer NOT_RUNNING. Still premature:
        // data remains on disk that playback never read -> not an EOS,
        // the stall has another cause (do not skip the track).
        assertFalse(SongCache.isByteRangeEos(1843200L, AVAIL, CHUNK));
    }

    @Test
    public void withinOneChunkThreshold_isGenuine() {
        // Case 4: 3481600 = 3584000 - 102400, exactly at threshold (inclusive).
        assertTrue(SongCache.isByteRangeEos(3481600L, AVAIL, CHUNK));
    }

    @Test
    public void justOutsideThreshold_isPremature() {
        assertFalse(SongCache.isByteRangeEos(AVAIL - CHUNK - 1, AVAIL, CHUNK));
    }

    @Test
    public void noData_isPremature() {
        // Case 5: nothing available, nothing consumed -> not an end.
        assertFalse(SongCache.isByteRangeEos(0L, 0L, CHUNK));
    }

    @Test
    public void zeroConsumedWithData_isPremature() {
        assertFalse(SongCache.isByteRangeEos(0L, AVAIL, CHUNK));
    }
}
