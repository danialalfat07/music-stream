-- Phase 1 Offline DB schema v1
-- Songs: primary metadata
CREATE TABLE IF NOT EXISTS songs (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  artist TEXT,
  duration TEXT,
  created_at INTEGER NOT NULL
);

-- Sources: one song may have multiple sources (audioStream / iframeStream)
CREATE TABLE IF NOT EXISTS sources (
  id TEXT PRIMARY KEY,
  song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
  type TEXT NOT NULL, -- 'audioStream' | 'iframeStream'
  stream_kind TEXT NOT NULL, -- 'googlevideo' | 'unknown'
  content_length INTEGER,
  mime_type TEXT,
  chunk_size INTEGER NOT NULL DEFAULT 1048576,
  total_segments INTEGER,
  status TEXT NOT NULL, -- 'not_cached' | 'partial' | 'full' | 'unavailable_offline'
  last_resolved_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sources_song_id ON sources(song_id);

-- Segments: 1MB virtual chunks
CREATE TABLE IF NOT EXISTS segments (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  idx INTEGER NOT NULL,
  byte_range TEXT NOT NULL, -- "0-1048575"
  cached INTEGER NOT NULL DEFAULT 0,
  local_path TEXT,
  size INTEGER,
  checksum TEXT,
  updated_at INTEGER NOT NULL,
  UNIQUE(source_id, idx)
);
CREATE INDEX IF NOT EXISTS idx_segments_source_id ON segments(source_id);

-- Assets: artwork, lyrics
CREATE TABLE IF NOT EXISTS assets (
  id TEXT PRIMARY KEY,
  song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
  type TEXT NOT NULL, -- 'artwork' | 'lyrics'
  source_url TEXT,
  cached INTEGER NOT NULL DEFAULT 0,
  local_path TEXT,
  mime_type TEXT,
  size INTEGER,
  payload_json TEXT, -- lyrics: {raw_synced, raw_plain, lines, source}
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_assets_song_id ON assets(song_id);

-- Offline jobs
CREATE TABLE IF NOT EXISTS offline_jobs (
  id TEXT PRIMARY KEY,
  song_id TEXT NOT NULL REFERENCES songs(id) ON DELETE CASCADE,
  source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
  state TEXT NOT NULL, -- 'queued' | 'running' | 'paused' | 'done' | 'failed'
  total_items INTEGER NOT NULL,
  done_items INTEGER NOT NULL DEFAULT 0,
  failed_items INTEGER NOT NULL DEFAULT 0,
  error TEXT,
  started_at INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_song_id ON offline_jobs(song_id);
