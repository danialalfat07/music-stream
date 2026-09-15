package com.dnialify.musicstream;

/**
 * Rollback flag without code revert.
 * Phase 1 offline intercept disabled until approved.
 * Flip to true when Phase 1 (SQLite + Filesystem wrapper) is ready.
 */
public final class FeatureFlags {
    public static final boolean CACHE_INTERCEPT = false;
    private FeatureFlags() {}
}
