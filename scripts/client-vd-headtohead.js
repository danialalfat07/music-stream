#!/usr/bin/env node
/* Head-to-head VD test: hunt a NATURAL VISIONOS 403 (sequential 100KB chunks),
   then race two recovery branches IN PARALLEL on the same failed byte:
     A: re-resolve with the SAME visitorData -> GET byte
     B: rotate to FRESH visitorData, re-resolve -> GET byte
   Verdict: A&&B recover -> VD_BUKAN_KUNCI; !A&&B -> VD_KUNCI; neither -> IP_MASALAH.
   The VD string for A is snapshotted BEFORE Promise.all so B's rotation
   cannot leak into A. VD hashes are logged to prove A/B used different VDs.
   Usage: node scripts/client-vd-headtohead.js [--songs=ID,...] [--out=DIR] */
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHUNK = 102400;
const SONGS = ['dQw4w9WgXcQ', '9bZkp7q19f0', 'kJQP7kiw5Fk', 'e-ORhEE9VVg', 'RgKAFK5djSk',
  'hTWKbfoikeg', '60ItHLz5WEA', 'JGwWNGJdvx8', 'CevxZvSJLk8', 'hLQl3WQQoQ0'];
const DEFAULT_OUT = 'C:\\Users\\Danial\\AppData\\Local\\Temp\\client-vd-headtohead';
const CHUNK_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const REQ_TIMEOUT_MS = 25000;

const DEF = {
  endpoint: 'https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false',
  gapis: 'https://youtubei.googleapis.com/youtubei/v1/',
  context: { client: {
    clientName: 'VISIONOS', clientVersion: '1.02', deviceModel: 'RealityDevice14,1',
    osName: 'visionOS', osVersion: '25.6.0.23O471', hl: 'en', gl: 'US',
  } },
  headers: {
    'Content-Type': 'application/json',
    'User-Agent': 'com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)',
    'X-Goog-Api-Format-Version': '2',
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
const hash8 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex').slice(0, 8);

async function fetchTimeout(url, opts = {}, ms = REQ_TIMEOUT_MS) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    return await fetch(url, { ...opts, signal: ctl.signal });
  } finally {
    clearTimeout(t);
  }
}

let vdSlot = null;
async function fetchVd() {
  const ctx = JSON.parse(JSON.stringify(DEF.context));
  const res = await fetchTimeout(`${DEF.gapis}visitor_id?prettyPrint=false`, {
    method: 'POST', headers: DEF.headers, body: JSON.stringify({ context: ctx }),
  });
  if (!res.ok) throw new Error(`visitor_id HTTP ${res.status}`);
  const vd = (await res.json())?.responseContext?.visitorData;
  if (!vd) throw new Error('visitor_id: no visitorData');
  vdSlot = vd;
  return vd;
}

async function resolveWithVd(video, vdOrNull) {
  const t0 = Date.now();
  const ctx = JSON.parse(JSON.stringify(DEF.context));
  if (vdOrNull) ctx.client.visitorData = vdOrNull;
  try {
    const res = await fetchTimeout(DEF.endpoint, {
      method: 'POST', headers: DEF.headers,
      body: JSON.stringify({ context: ctx, videoId: video, racyCheckOk: true, contentCheckOk: true }),
    });
    if (!res.ok) return { ok: false, error: `player HTTP ${res.status}`, ms: Date.now() - t0 };
    const data = await res.json();
    const sd = data.streamingData;
    if (!sd) {
      const ps = data.playabilityStatus || {};
      return { ok: false, error: `no streamingData playability=${ps.status}`, ms: Date.now() - t0 };
    }
    const audios = [...(sd.adaptiveFormats || []), ...(sd.formats || [])]
      .filter((f) => f.mimeType && f.mimeType.includes('audio/'));
    if (!audios.length) return { ok: false, error: 'no audio formats', ms: Date.now() - t0 };
    audios.sort((a, b) => {
      const ao = a.mimeType.includes('opus') ? 1 : 0;
      const bo = b.mimeType.includes('opus') ? 1 : 0;
      if (ao !== bo) return bo - ao;
      return (b.bitrate || 0) - (a.bitrate || 0);
    });
    const best = audios[0];
    if (!best.url) return { ok: false, error: `cipher-only itag ${best.itag}`, ms: Date.now() - t0 };
    return { ok: true, url: best.url, itag: best.itag,
      total: best.contentLength ? parseInt(best.contentLength, 10) : null,
      host: new URL(best.url).host, urlHash: hash8(best.url), ms: Date.now() - t0 };
  } catch (e) {
    return { ok: false, error: `exception: ${e.message}`, ms: Date.now() - t0 };
  }
}

async function getRange(url, start, end) {
  const t0 = Date.now();
  try {
    const r = await fetchTimeout(url, {
      headers: { Range: `bytes=${start}-${end}`, 'User-Agent': CHUNK_UA },
    });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, bytes: buf.byteLength, ms: Date.now() - t0 };
  } catch (e) {
    return { status: 'ERR', bytes: 0, ms: Date.now() - t0, err: String(e.message || e) };
  }
}

async function runBranch(tag, video, byte, end, vdOrNull, vdHash) {
  const t0 = Date.now();
  const out = { branch: tag, vdHash, resolveOk: false, dlStatus: null, recovered: false, ms: null };
  const r = await resolveWithVd(video, vdOrNull);
  out.resolveMs = r.ms;
  out.resolveError = r.error || null;
  out.newUrlHash = r.urlHash || null;
  out.newHost = r.host || null;
  if (!r.ok) { out.ms = Date.now() - t0; return out; }
  out.resolveOk = true;
  const g = await getRange(r.url, byte, end);
  out.dlStatus = g.status;
  out.dlMs = g.ms;
  out.recovered = g.status === 206 && g.bytes === end - byte + 1;
  out.ms = Date.now() - t0;
  return out;
}

(async () => {
  const o = parseArgs(process.argv.slice(2));
  fs.mkdirSync(o.out, { recursive: true });
  const jsonl = path.join(o.out, 'results.jsonl');
  const log = (obj) => fs.appendFileSync(jsonl, JSON.stringify(obj) + '\n');
  console.log(`HUNT SONGS: ${o.songs.join(',')} OUT: ${o.out} TS: ${new Date().toISOString()}`);

  let found = null;
  for (const video of o.songs) {
    if (!vdSlot) await fetchVd();
    const r = await resolveWithVd(video, vdSlot);
    if (!r.ok || !r.total) {
      console.log(`[HUNT ${video}] resolve FAIL: ${r.error}`);
      log({ mode: 'HUNT', video, status: 'RESOLVE_FAIL', error: r.error });
      continue;
    }
    const total = r.total;
    let chunksOk = 0;
    let hit = null;
    for (let start = 0; start < total; start += CHUNK) {
      const end = Math.min(start + CHUNK - 1, total - 1);
      const g = await getRange(r.url, start, end);
      if (g.status === 206 && g.bytes === end - start + 1) {
        chunksOk += 1;
        await sleep(150);
        continue;
      }
      if (g.status === 403) {
        hit = { video, byte: start, end, chunk: start / CHUNK, url: r.url,
          host: r.host, itag: r.itag, total };
        break;
      }
      console.log(`[HUNT ${video}] non-403 status=${g.status} at ${start}, aborting song`);
      break;
    }
    if (hit) {
      console.log(`[HUNT ${video}] NATURAL 403 at byte ${hit.byte} (chunk ${hit.chunk}) after ${chunksOk} clean chunks`);
      log({ mode: 'HUNT', video, status: 'FOUND_403', ...hit, url: undefined });
      found = hit;
      break;
    }
    console.log(`[HUNT ${video}] clean (${chunksOk} chunks, no 403)`);
    log({ mode: 'HUNT', video, status: 'CLEAN', chunksOk, total });
    await sleep(2000);
  }

  if (!found) {
    console.log('NO_403: no natural VISIONOS 403 across all hunt songs');
    log({ mode: 'NO_403' });
    return;
  }

  // snapshot VD for branch A BEFORE parallel start (B rotates the slot)
  const vdSame = vdSlot;
  const vdSameHash = hash8(vdSame);
  console.log(`HEAD-TO-HEAD byte=${found.byte} end=${found.end} vdSameHash=${vdSameHash}`);
  const [A, B] = await Promise.all([
    runBranch('A', found.video, found.byte, found.end, vdSame, vdSameHash),
    (async () => {
      const vdNew = await fetchVd(); // rotates slot; A already holds its snapshot
      return runBranch('B', found.video, found.byte, found.end, vdNew, hash8(vdNew));
    })(),
  ]);
  const vdDiffer = A.vdHash !== B.vdHash;
  let verdict;
  if (A.recovered && B.recovered) verdict = 'VD_BUKAN_KUNCI';
  else if (!A.recovered && B.recovered) verdict = 'VD_KUNCI';
  else verdict = 'IP_MASALAH';
  const rec = { mode: 'HEADTOHEAD', video: found.video, byte: found.byte, chunk: found.chunk,
    host: found.host, itag: found.itag, vdSameHash: A.vdHash, vdNewHash: B.vdHash,
    vdDiffer, A, B, verdict };
  log(rec);
  for (const b of [A, B]) {
    console.log(`[BRANCH ${b.branch}] vdHash=${b.vdHash} resolve=${b.resolveOk ? `OK ${b.resolveMs}ms` : 'FAIL:' + b.resolveError} ` +
      `dl=${b.dlStatus} recovered=${b.recovered} totalMs=${b.ms}`);
  }
  console.log(`VERDICT: ${verdict} (vdDiffer=${vdDiffer})`);
  console.log(`DONE. raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
