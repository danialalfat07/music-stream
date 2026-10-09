#!/usr/bin/env node
/* Real-time playback + cache-fill simulation, VISIONOS only (production mirror).
   - CHUNK 102400, WINDOW 5 ahead of play chunk, 1000ms wall-clock ticks.
   - Virtual play locks to wall clock; stalls only when the NEEDED chunk is missing.
   - Natural 403 -> 5-step ladder (10s budget) while playback keeps running.
   - 5 songs x itag 251 x 3-5 min. One JSON line per song to <out>/results.jsonl.
   Usage: node scripts/client-realtime-cache-sim.js [--songs=ID,...] [--out=DIR] */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const CHUNK = 102400;
const WINDOW = 5;
const TICK = 1000;
const STALL_OK_MAX = 2000;
const LADDER_BUDGET_MS = 10000;
const SONGS = ['dQw4w9WgXcQ', '9bZkp7q19f0', 'e-ORhEE9VVg', 'RgKAFK5djSk', 'hTWKbfoikeg'];
const DEFAULT_OUT = 'C:\\Users\\Danial\\AppData\\Local\\Temp\\client-realtime-cache-sim';
const CHUNK_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const REQ_TIMEOUT_MS = 25000;

const CLIENTS = {
  VISIONOS: {
    endpoint: 'https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false',
    gapis: 'https://youtubei.googleapis.com/youtubei/v1/',
    useVd: true,
    context: { client: {
      clientName: 'VISIONOS', clientVersion: '1.02', deviceModel: 'RealityDevice14,1',
      osName: 'visionOS', osVersion: '25.6.0.23O471', hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  ANDROID: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    gapis: 'https://youtubei.googleapis.com/youtubei/v1/',
    useVd: false,
    context: { client: {
      clientName: 'ANDROID', clientVersion: '20.10.38', androidSdkVersion: 35, hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  ANDROID_VR: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    gapis: 'https://youtubei.googleapis.com/youtubei/v1/',
    useVd: false,
    context: { client: {
      clientName: 'ANDROID_VR', clientVersion: '1.56.21', deviceMake: 'Oculus',
      deviceModel: 'Quest', androidSdkVersion: 32, hl: 'en', gl: 'US',
      osName: 'Android', osVersion: '12',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.apps.youtube.vr/1.56.21 (Linux; U; Android 12; Quest)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
};

function parseArgs(argv) {
  const o = { songs: SONGS.slice(), out: DEFAULT_OUT };
  for (const a of argv) {
    if (a.startsWith('--songs=')) o.songs = a.slice(8).split(',').map((s) => s.trim()).filter(Boolean);
    else if (a.startsWith('--out=')) o.out = a.slice(6);
  }
  return o;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function fetchTimeout(url, opts = {}, ms = REQ_TIMEOUT_MS) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    return await fetch(url, { ...opts, signal: ctl.signal });
  } finally {
    clearTimeout(t);
  }
}

const vdStore = { VISIONOS: null, ANDROID: null, ANDROID_VR: null };

async function fetchVd(name) {
  const def = CLIENTS[name];
  const ctx = JSON.parse(JSON.stringify(def.context));
  delete ctx.client.visitorData;
  const res = await fetchTimeout(`${def.gapis}visitor_id?prettyPrint=false`, {
    method: 'POST', headers: def.headers, body: JSON.stringify({ context: ctx }),
  });
  if (!res.ok) throw new Error(`visitor_id HTTP ${res.status}`);
  const vd = (await res.json())?.responseContext?.visitorData;
  if (!vd) throw new Error('visitor_id: no visitorData');
  vdStore[name] = vd;
  return vd;
}

async function resolveClient(name, videoId, vdOpt) {
  const def = CLIENTS[name];
  const ctx = JSON.parse(JSON.stringify(def.context));
  let vdAttached = false;
  try {
    if (vdOpt === 'fresh') {
      ctx.client.visitorData = await fetchVd(name);
      vdAttached = true;
    } else if (vdOpt === 'slot') {
      if (!vdStore[name]) await fetchVd(name);
      if (def.useVd) { ctx.client.visitorData = vdStore[name]; vdAttached = true; }
    }
    const res = await fetchTimeout(def.endpoint, {
      method: 'POST', headers: def.headers,
      body: JSON.stringify({ context: ctx, videoId, racyCheckOk: true, contentCheckOk: true }),
    });
    if (!res.ok) return { ok: false, error: `player HTTP ${res.status}`, vdAttached };
    const data = await res.json();
    const ps = data.playabilityStatus || {};
    const sd = data.streamingData;
    if (!sd) return { ok: false, error: `no streamingData playability=${ps.status}`, vdAttached };
    const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
    const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
    if (!audios.length) return { ok: false, error: 'no audio formats', vdAttached };
    audios.sort((a, b) => {
      const ao = a.mimeType.includes('opus') ? 1 : 0;
      const bo = b.mimeType.includes('opus') ? 1 : 0;
      if (ao !== bo) return bo - ao;
      return (b.bitrate || 0) - (a.bitrate || 0);
    });
    const best = audios[0];
    if (!best.url) return { ok: false, error: `cipher-only itag ${best.itag}`, vdAttached, itag: best.itag };
    return {
      ok: true, url: best.url, itag: best.itag, mime: best.mimeType,
      total: best.contentLength ? parseInt(best.contentLength, 10) : null,
      host: new URL(best.url).host, vdAttached,
      durSec: data.videoDetails?.lengthSeconds ? parseInt(data.videoDetails.lengthSeconds, 10) : null,
    };
  } catch (e) {
    return { ok: false, error: `exception: ${e.message}`, vdAttached };
  }
}

async function getRange(url, start, end) {
  const t0 = Date.now();
  try {
    const r = await fetchTimeout(url, {
      headers: { Range: `bytes=${start}-${end}`, 'User-Agent': CHUNK_UA },
    });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, bytes: buf.byteLength, buf, ms: Date.now() - t0 };
  } catch (e) {
    return { status: 'ERR', bytes: 0, buf: null, ms: Date.now() - t0, err: String(e.message || e) };
  }
}

// 5-step ladder, 10s budget. Returns {step, buf, sub} or {step:0}.
async function ladder(video, byte, end, curUrl) {
  const t0 = Date.now();
  const budgetLeft = () => LADDER_BUDGET_MS - (Date.now() - t0);
  const tryUrl = async (u) => {
    const g = await getRange(u, byte, end);
    if (g.status === 206 && g.buf && g.buf.byteLength === end - byte + 1) return g.buf;
    return null;
  };
  // L1: same URL retry after 500ms
  if (budgetLeft() > 600) {
    await sleep(500);
    const b = await tryUrl(curUrl);
    if (b) return { step: 1, buf: b, sub: null, url: curUrl };
  }
  // L2: same client, same VD
  if (budgetLeft() > 1500) {
    const r = await resolveClient('VISIONOS', video, 'slot');
    if (r.ok && r.url) {
      const b = await tryUrl(r.url);
      if (b) return { step: 2, buf: b, sub: null, host: r.host, url: r.url };
    }
  }
  // L3: same client, fresh VD
  if (budgetLeft() > 1500) {
    const r = await resolveClient('VISIONOS', video, 'fresh');
    if (r.ok && r.url) {
      const b = await tryUrl(r.url);
      if (b) return { step: 3, buf: b, sub: null, host: r.host, url: r.url };
    }
  }
  // L4: fallback ANDROID
  if (budgetLeft() > 1500) {
    const r = await resolveClient('ANDROID', video, 'slot');
    if (r.ok && r.url) {
      const b = await tryUrl(r.url);
      if (b) return { step: 4, buf: b, sub: 'ANDROID', host: r.host, url: r.url };
    }
  }
  // L5: fallback ANDROID_VR + fresh VD
  if (budgetLeft() > 1500) {
    const r = await resolveClient('ANDROID_VR', video, 'fresh');
    if (r.ok && r.url) {
      const b = await tryUrl(r.url);
      if (b) return { step: 5, buf: b, sub: 'ANDROID_VR', host: r.host, url: r.url };
    }
  }
  return { step: 0, buf: null, sub: null };
}

async function simSong(video, outDir) {
  const t0wall = Date.now();
  const out = { mode: 'SIM', client: 'VISIONOS', video,
    itag: null, host: null, durMs: null, total: null, totalChunks: null,
    events403: [], recoveries: [], stalls: 0, totalStallMs: 0, gapMaxMs: 0,
    playMsEnd: 0, wallMs: null, cacheFillMs: null,
    playbackFinished: false, fileIntact: false, status: null, partFile: null };
  const r = await resolveClient('VISIONOS', video, 'slot');
  if (!r.ok || !r.total || !r.durSec) {
    out.status = 'SIM_FAIL_RESOLVE';
    out.resolveError = r.error || 'no total/duration';
    out.ms = Date.now() - t0wall;
    return out;
  }
  out.itag = r.itag; out.host = r.host; out.vdAttached = r.vdAttached;
  const total = r.total;
  const durMs = r.durSec * 1000;
  out.total = total; out.durMs = durMs;
  const nChunks = Math.ceil(total / CHUNK);
  out.totalChunks = nChunks;
  const chunkDurMs = (CHUNK / total) * durMs;
  const idxOf = (playMs) => Math.min(nChunks - 1, Math.max(0, Math.floor(playMs / chunkDurMs)));
  let url = r.url;
  const partFile = path.join(outDir, `${video}.part`);
  out.partFile = partFile;
  const fd = fs.openSync(partFile, 'w');
  const have = new Set();
  const writeChunk = (idx, buf) => {
    // chunks arrive in order except ladder recoveries; use positional write
    fs.writeSync(fd, buf, 0, buf.byteLength, idx * CHUNK);
    have.add(idx);
  };
  const fetchWrite = async (idx) => {
    const start = idx * CHUNK;
    const end = Math.min(start + CHUNK - 1, total - 1);
    const g = await getRange(url, start, end);
    return { ...g, idx, start, end };
  };

  // initial window 0..4 (blocking, before playback)
  for (let i = 0; i < Math.min(WINDOW, nChunks); i += 1) {
    const g = await fetchWrite(i);
    if (g.status === 206 && g.bytes === g.end - g.start + 1) {
      writeChunk(i, g.buf);
    } else {
      if (g.status === 403) out.events403.push({ chunk: i, byte: g.start, playMs: 0, phase: 'init' });
      const lr = await ladder(video, g.start, g.end, url);
      if (lr.step > 0) {
        if (lr.url) url = lr.url;
        out.recoveries.push({ chunk: i, step: lr.step, sub: lr.sub, host: lr.host || null });
        writeChunk(i, lr.buf);
      } else {
        fs.closeSync(fd);
        out.status = 'SIM_FAIL_INIT';
        out.ms = Date.now() - t0wall;
        return out;
      }
    }
  }

  const t0 = Date.now();
  let playMs = 0;
  let stallMs = 0;
  let stallRun = 0;
  let cacheFillMs = null;
  let broken = null;
  const stopAt = durMs - 5000;
  for (;;) {
    const tickStart = Date.now();
    const expected = (tickStart - t0) - stallMs;
    const need = idxOf(expected);
    if (have.has(need)) {
      playMs = expected;
      stallRun = 0;
    } else {
      stallMs += TICK;
      stallRun += TICK;
      out.stalls += 1;
      out.totalStallMs += TICK;
      if (stallRun > out.gapMaxMs) out.gapMaxMs = stallRun;
    }
    // window fill ahead of current need
    const cur = idxOf(playMs);
    for (let idx = cur + 1; idx <= Math.min(cur + WINDOW, nChunks - 1); idx += 1) {
      if (have.has(idx)) continue;
      const g = await fetchWrite(idx);
      if (g.status === 206 && g.bytes === g.end - g.start + 1) {
        writeChunk(idx, g.buf);
      } else {
        if (g.status === 403) {
          out.events403.push({ chunk: idx, byte: g.start, playMs: Math.round(playMs), phase: 'window' });
        }
        const lr = await ladder(video, g.start, g.end, url);
        if (lr.step > 0) {
          if (lr.url) url = lr.url;
          if (lr.host) out.host = `${out.host}+${lr.host}`;
          out.recoveries.push({ chunk: idx, step: lr.step, sub: lr.sub, host: lr.host || null });
          writeChunk(idx, lr.buf);
        } else {
          broken = { atPlayMs: Math.round(playMs), chunk: idx, byte: g.start };
          break;
        }
      }
      if (have.size === nChunks && cacheFillMs === null) cacheFillMs = Date.now() - t0;
    }
    if (broken) break;
    if (playMs >= stopAt) break;
    if (Date.now() - t0 > durMs + 180000) { broken = { guard: 'timeout', atPlayMs: Math.round(playMs) }; break; }
    const spent = Date.now() - tickStart;
    if (spent < TICK) await sleep(TICK - spent);
  }
  fs.closeSync(fd);
  out.playMsEnd = Math.round(playMs);
  out.wallMs = Date.now() - t0;
  out.cacheFillMs = cacheFillMs;
  out.playbackFinished = !broken && playMs >= stopAt;
  try {
    out.fileIntact = fs.statSync(partFile).size === total && have.size === nChunks;
  } catch { out.fileIntact = false; }
  if (broken) {
    out.status = 'SIM_BROKEN';
    out.broken = broken;
  } else if (!out.playbackFinished) {
    out.status = 'SIM_INCOMPLETE';
  } else if (!out.fileIntact) {
    out.status = 'SIM_FILE_SHORT';
  } else if (out.gapMaxMs > STALL_OK_MAX) {
    out.status = 'SIM_STALL_FAIL';
  } else {
    out.status = 'SIM_OK';
  }
  out.ms = Date.now() - t0wall;
  return out;
}

(async () => {
  const o = parseArgs(process.argv.slice(2));
  fs.mkdirSync(o.out, { recursive: true });
  const jsonl = path.join(o.out, 'results.jsonl');
  const log = (obj) => fs.appendFileSync(jsonl, JSON.stringify(obj) + '\n');
  console.log(`SONGS: ${o.songs.join(',')} OUT: ${o.out} TS: ${new Date().toISOString()}`);
  for (const video of o.songs) {
    const songDir = path.join(o.out, video);
    fs.mkdirSync(songDir, { recursive: true });
    const r = await simSong(video, songDir);
    log(r);
    console.log(`[${video}] dur=${r.durMs === null ? '?' : Math.round(r.durMs / 1000) + 's'} ` +
      `chunks=${r.totalChunks === null ? '?' : r.totalChunks} 403s=${r.events403.length} ` +
      `first403=${r.events403.length ? `chunk${r.events403[0].chunk}@${r.events403[0].byte}/play${Math.round(r.events403[0].playMs / 1000)}s` : '-'} ` +
      `rec=[${r.recoveries.map((x) => `c${x.chunk}:L${x.step}${x.sub ? '/' + x.sub : ''}`).join(',')}] ` +
      `gapMax=${r.gapMaxMs}ms stalls=${r.stalls} playEnd=${Math.round((r.playMsEnd || 0) / 1000)}s ` +
      `fillMs=${r.cacheFillMs} wallMs=${r.wallMs} finished=${r.playbackFinished} intact=${r.fileIntact} status=${r.status}`);
    await sleep(30000); // breather between songs
  }
  console.log(`DONE. raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
