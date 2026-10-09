#!/usr/bin/env node
/* Resolve + 403-recovery + substitution test for VISIONOS / ANDROID / ANDROID_VR.
   10 songs x 3 clients x 3 scenarios (A baseline strict, B forced-403 at chunk 11
   with 5-step recovery ladder, C invalid-visitorData block simulation) = 90 cases.
   Usage:
     node scripts/client-resolve-403-test.js [--clients=A,B] [--songs=ID,ID,...]
       [--out=DIR] [--seed=N]
   Appends one JSON line per scenario to <out>/results.jsonl + saves .part files.
   Read-only vs repo source. */
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');

const CHUNK = 102400; // production VisionOsCache.CHUNK
const SABOTAGE_IDX = 11; // chunk 11 -> byte 1126400
const CHUNK_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const REQ_TIMEOUT_MS = 25000;
const BETWEEN_CHUNKS_MS = 150;
const MAX_REFRESH = 3;
const SONGS = ['dQw4w9WgXcQ', '9bZkp7q19f0', 'kJQP7kiw5Fk', 'e-ORhEE9VVg', 'RgKAFK5djSk',
  'hTWKbfoikeg', '60ItHLz5WEA', 'JGwWNGJdvx8', 'CevxZvSJLk8', 'hLQl3WQQoQ0'];
const CHAIN = ['VISIONOS', 'ANDROID', 'ANDROID_VR'];
const DEFAULT_OUT = 'C:\\Users\\Danial\\AppData\\Local\\Temp\\client-resolve-403-test';

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
    useVd: false, // production parity: no visitorData on baseline resolve
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
  const o = { clients: Object.keys(CLIENTS), songs: SONGS.slice(), out: DEFAULT_OUT, seed: 7 };
  for (const a of argv) {
    if (a.startsWith('--clients=')) o.clients = a.slice(10).split(',').map((s) => s.trim().toUpperCase()).filter((s) => CLIENTS[s]);
    else if (a.startsWith('--songs=')) o.songs = a.slice(8).split(',').map((s) => s.trim()).filter(Boolean);
    else if (a.startsWith('--out=')) o.out = a.slice(6);
    else if (a.startsWith('--seed=')) o.seed = parseInt(a.slice(7), 10) || 0;
  }
  return o;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function hash8(s) { return crypto.createHash('sha256').update(String(s)).digest('hex').slice(0, 8); }
// seeded PRNG for reproducible sabotage choice
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function seedOf(seed, song, client) {
  let h = seed >>> 0;
  const s = `${song}:${client}`;
  for (let i = 0; i < s.length; i += 1) h = Math.imul(h ^ s.charCodeAt(i), 2654435761);
  return h >>> 0;
}

async function fetchTimeout(url, opts = {}, ms = REQ_TIMEOUT_MS) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), ms);
  try {
    return await fetch(url, { ...opts, signal: ctl.signal });
  } finally {
    clearTimeout(t);
  }
}

// per-client identity slots (never shared across clients)
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

// resolve with explicit visitorData policy; vd:'slot'|'fresh'|'invalid'|null
async function resolveClient(name, videoId, vdOpt) {
  const def = CLIENTS[name];
  const ctx = JSON.parse(JSON.stringify(def.context));
  let vdAttached = false;
  try {
    if (vdOpt === 'invalid') {
      ctx.client.visitorData = `INVALID_EXPIRED_TEST_VD_${Date.now() % 100000}`;
      vdAttached = true;
    } else if (vdOpt === 'fresh') {
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
    if (!sd) return { ok: false, error: `no streamingData playability=${ps.status} reason=${ps.reason || '-'}`, vdAttached, playability: ps.status };
    const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
    const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
    if (!audios.length) return { ok: false, error: 'no audio formats', vdAttached, playability: ps.status };
    audios.sort((a, b) => {
      const ao = a.mimeType.includes('opus') ? 1 : 0;
      const bo = b.mimeType.includes('opus') ? 1 : 0;
      if (ao !== bo) return bo - ao;
      return (b.bitrate || 0) - (a.bitrate || 0);
    });
    const best = audios[0];
    if (!best.url) return { ok: false, error: `cipher-only itag ${best.itag}`, vdAttached, itag: best.itag, playability: ps.status };
    return {
      ok: true, url: best.url, itag: best.itag, mime: best.mimeType, bitrate: best.bitrate,
      total: best.contentLength ? parseInt(best.contentLength, 10) : null,
      host: new URL(best.url).host, urlHash: hash8(best.url), vdAttached,
      playability: ps.status || 'OK',
      durSec: data.videoDetails?.lengthSeconds ? parseInt(data.videoDetails.lengthSeconds, 10) : null,
    };
  } catch (e) {
    return { ok: false, error: `exception: ${e.message}`, vdAttached };
  }
}

async function getRange(url, start, end, extraHeaders) {
  const headers = { Range: `bytes=${start}-${end}`, 'User-Agent': CHUNK_UA, ...(extraHeaders || {}) };
  try {
    const r = await fetchTimeout(url, { headers });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, bytes: buf.byteLength, buf,
      cr: r.headers.get('content-range') };
  } catch (e) {
    return { status: 'ERR', bytes: 0, buf: null, cr: null, err: String(e.message || e) };
  }
}

function parseTotal(cr) {
  const m = cr && cr.match(/\/(\d+)\s*$/);
  return m ? parseInt(m[1], 10) : null;
}

function nextInChain(name, blacklist) {
  const i = CHAIN.indexOf(name);
  for (let k = 1; k <= CHAIN.length; k += 1) {
    const cand = CHAIN[(i + k) % CHAIN.length];
    if (!blacklist.has(cand)) return cand;
  }
  return null;
}

// ---------- SCENARIO A: strict baseline, production mirror (no retry/refresh) ----------
async function scenarioA(name, video, songDir) {
  const t0 = Date.now();
  const r = await resolveClient(name, video, 'slot');
  const out = { mode: 'A', client: name, video,
    resolve: r.ok ? { ok: true, itag: r.itag, host: r.host, urlHash: r.urlHash,
      vdAttached: r.vdAttached, total: r.total, durSec: r.durSec } : { ok: false, error: r.error },
    chunksOk: 0, http403: 0, failByte: null, cachedBytes: 0, status: null, ms: null, partFile: null };
  if (!r.ok || !r.total) { out.status = 'A_FAIL_RESOLVE'; out.ms = Date.now() - t0; return out; }
  const total = r.total;
  const partFile = path.join(songDir, `A_${name}.part`);
  out.partFile = partFile;
  const fd = fs.openSync(partFile, 'w');
  try {
    for (let start = 0; start < total;) {
      const end = Math.min(start + CHUNK - 1, total - 1);
      const g = await getRange(r.url, start, end);
      if (g.status === 403) out.http403 += 1;
      if (g.status !== 206 || !g.buf || g.buf.byteLength !== end - start + 1) {
        out.failByte = start;
        out.status = g.status === 403 ? 'A_FAIL_403' : 'A_FAIL_OTHER';
        break;
      }
      fs.writeSync(fd, g.buf);
      start += g.buf.byteLength;
      out.chunksOk += 1;
      out.cachedBytes = start;
      await sleep(BETWEEN_CHUNKS_MS);
    }
  } finally {
    fs.closeSync(fd);
  }
  if (out.status === null) {
    out.status = out.cachedBytes === total && fs.statSync(partFile).size === total ? 'A_PASS' : 'A_FAIL_SIZE';
  }
  out.ms = Date.now() - t0;
  return out;
}

// ---------- recovery ladder shared by B (also used for natural failures) ----------
async function ladder(name, video, byte, end, blacklist, stats, curUrl) {
  // stats: {refreshes, http403} mutated; returns {step, url, host, urlHash, subClient, ms} or {step:0 fail}
  const t0 = Date.now();
  const rec = { step: 0, url: curUrl || null, host: null, urlHash: null, subClient: null, ms: null, fail: null };
  const clean = () => getRange(rec.url, byte, end);
  // L1: same URL retry after 500ms (caller sets rec.url first)
  await sleep(500);
  let g = await clean();
  if (g.status === 403) stats.http403 += 1;
  if (g.status === 206 && g.buf && g.buf.byteLength === end - byte + 1) {
    rec.step = 1; rec.buf = g.buf; rec.ms = Date.now() - t0; return rec;
  }
  // L2..L5
  const attempts = [
    { step: 2, how: 'same-client same-VD', fn: async () => resolveClient(name, video, 'slot') },
    { step: 3, how: 'same-client fresh-VD', fn: async () => resolveClient(name, video, 'fresh') },
    { step: 4, how: 'next-client', fn: async () => {
      const sub = nextInChain(name, blacklist);
      if (!sub) return { ok: false, error: 'no substitute (all blacklisted)', _sub: null };
      const rr = await resolveClient(sub, video, 'slot');
      rr._sub = sub;
      return rr;
    } },
    { step: 5, how: 'next-client fresh-VD', fn: async () => {
      const sub = nextInChain(name, blacklist);
      if (!sub) return { ok: false, error: 'no substitute (all blacklisted)', _sub: null };
      const rr = await resolveClient(sub, video, 'fresh');
      rr._sub = sub;
      return rr;
    } },
  ];
  for (const a of attempts) {
    if (stats.refreshes >= MAX_REFRESH && a.step <= 3) {
      rec.fail = 'refresh budget exhausted';
      break;
    }
    const rr = await a.fn();
    stats.refreshes += 1;
    if (!rr.ok) {
      rec.lastResolveError = rr.error;
      continue;
    }
    if (rr.total && rr.total !== stats.total) {
      rec.totalMismatch = { was: stats.total, now: rr.total };
    }
    rec.url = rr.url; rec.host = rr.host; rec.urlHash = rr.urlHash;
    if (rr._sub) rec.subClient = rr._sub;
    g = await getRange(rec.url, byte, end);
    if (g.status === 403) stats.http403 += 1;
    if (g.status === 206 && g.buf && g.buf.byteLength === end - byte + 1) {
      rec.step = a.step; rec.buf = g.buf; rec.ms = Date.now() - t0; return rec;
    }
  }
  rec.fail = rec.fail || 'ladder exhausted';
  rec.ms = Date.now() - t0;
  return rec;
}

// ---------- SCENARIO B: sabotage chunk 11, then ladder ----------
const SABOTAGE = ['auth-header', 'range-overflow', 'offset-shift'];
async function scenarioB(name, video, songDir, seed, blacklist) {
  const t0 = Date.now();
  const rng = mulberry32(seedOf(seed, video, name));
  const method = SABOTAGE[Math.floor(rng() * SABOTAGE.length)];
  const out = { mode: 'B', client: name, video, sabotage: method,
    resolve: null, chunksOk: 0, http403: 0, inducedStatus: null,
    recoveredStep: null, naturalRecoveries: [], subClient: null, refreshes: 0, ladderMs: null,
    cachedBytes: 0, total: null, status: null, ms: null, partFile: null, blacklisted: false };
  const stats = { refreshes: 0, http403: 0, total: null };
  const rr0 = await resolveClient(name, video, 'slot');
  if (!rr0.ok) {
    out.resolve = { ok: false, error: rr0.error };
    out.status = 'B_FAIL_RESOLVE';
    out.ms = Date.now() - t0;
    return out;
  }
  out.resolve = { ok: true, itag: rr0.itag, host: rr0.host, urlHash: rr0.urlHash,
    vdAttached: rr0.vdAttached, durSec: rr0.durSec };
  const total = rr0.total;
  if (!total) { out.status = 'B_FAIL_NO_TOTAL'; out.ms = Date.now() - t0; return out; }
  out.total = total; stats.total = total;
  const nChunks = Math.ceil(total / CHUNK);
  const sabIdx = nChunks > 13 ? SABOTAGE_IDX : Math.max(1, Math.floor(nChunks / 2));
  let url = rr0.url;
  const partFile = path.join(songDir, `B_${name}.part`);
  out.partFile = partFile;
  const fd = fs.openSync(partFile, 'w');
  let received = 0;
  try {
    for (let idx = 0; idx < nChunks; idx += 1) {
      const start = idx * CHUNK;
      const end = Math.min(start + CHUNK - 1, total - 1);
      if (idx === sabIdx) {
        // --- sabotage request (body always discarded) ---
        let g;
        if (method === 'auth-header') {
          g = await getRange(url, start, end, { Authorization: 'Bearer INVALID_TEST_TOKEN' });
        } else if (method === 'range-overflow') {
          g = await getRange(url, 99999999, 99999999 + CHUNK - 1);
        } else {
          g = await getRange(url, start + 1024, end + 1024);
        }
        out.inducedStatus = g.status;
        if (g.status === 403) { out.http403 += 1; stats.http403 += 1; }
        if (g.status === 206) {
          // sabotage ineffective at HTTP level: re-fetch CLEAN bytes and write them
          out.recoveredStep = 'N/A-no-error';
          const clean = await getRange(url, start, end);
          if (clean.status === 206 && clean.buf && clean.buf.byteLength === end - start + 1) {
            fs.writeSync(fd, clean.buf);
            received = end + 1;
            out.chunksOk += 1;
            out.cachedBytes = received;
          } else {
            const lr0 = await ladder(name, video, start, end, blacklist, stats, url);
            out.refreshes = stats.refreshes;
            out.http403 = stats.http403;
            out.ladderMs = lr0.ms;
            if (lr0.step > 0) {
              out.recoveredStep = lr0.step;
              if (lr0.subClient) out.subClient = lr0.subClient;
              if (lr0.host) { url = lr0.url; out.resolve.host = lr0.host; out.resolve.urlHash = lr0.urlHash; }
              fs.writeSync(fd, lr0.buf);
              received = end + 1;
              out.chunksOk += 1;
              out.cachedBytes = received;
            } else {
              out.status = 'B_FAIL_LADDER';
              out.failByte = start;
              break;
            }
          }
        } else {
          const lr = await ladder(name, video, start, end, blacklist, stats, url);
          out.refreshes = stats.refreshes;
          out.http403 = stats.http403;
          out.ladderMs = lr.ms;
          if (lr.step > 0) {
            out.recoveredStep = lr.step;
            if (lr.subClient) out.subClient = lr.subClient;
            if (lr.host) { url = lr.url; out.resolve.host = lr.host; out.resolve.urlHash = lr.urlHash; }
            fs.writeSync(fd, lr.buf);
            received = end + 1;
            out.chunksOk += 1;
            out.cachedBytes = received;
          } else {
            out.status = 'B_FAIL_LADDER';
            out.failByte = start;
            break;
          }
        }
        await sleep(BETWEEN_CHUNKS_MS);
        continue;
      }
      // --- normal chunk (natural failure -> ladder too) ---
      const g = await getRange(url, start, end);
      if (g.status === 206 && g.buf && g.buf.byteLength === end - start + 1) {
        fs.writeSync(fd, g.buf);
        received = end + 1;
        out.chunksOk += 1;
        out.cachedBytes = received;
        await sleep(BETWEEN_CHUNKS_MS);
        continue;
      }
      if (g.status === 403) { out.http403 += 1; stats.http403 += 1; }
      const lr = await ladder(name, video, start, end, blacklist, stats, url);
      out.refreshes = stats.refreshes;
      out.http403 = stats.http403;
      out.ladderMs = (out.ladderMs || 0) + lr.ms;
      if (lr.step > 0) {
        out.naturalRecoveries.push({ idx, step: lr.step, sub: lr.subClient || null });
        if (lr.subClient && !out.subClient) out.subClient = lr.subClient;
        if (lr.host) { url = lr.url; }
        fs.writeSync(fd, lr.buf);
        received = end + 1;
        out.chunksOk += 1;
        out.cachedBytes = received;
        await sleep(BETWEEN_CHUNKS_MS);
        continue;
      }
      out.status = 'B_FAIL_LADDER';
      out.failByte = start;
      break;
    }
  } finally {
    fs.closeSync(fd);
  }
  // substitution / fail rules
  const failBy403 = out.http403 >= 3;
  if (out.status === null) {
    if (out.cachedBytes === total && fs.statSync(partFile).size === total) {
      out.status = out.recoveredStep === 'N/A-no-error' ? 'B_PASS_NO_ERROR' : 'B_PASS';
    } else {
      out.status = 'B_FAIL_SIZE';
    }
  }
  if ((out.status || '').startsWith('B_FAIL') || failBy403 || stats.refreshes >= MAX_REFRESH) {
    if (failBy403 || stats.refreshes >= MAX_REFRESH || (out.status || '').startsWith('B_FAIL')) {
      blacklist.add(name);
      out.blacklisted = true;
    }
  }
  out.ms = Date.now() - t0;
  return out;
}

// ---------- SCENARIO C: invalid visitorData block simulation ----------
async function scenarioC(name, video) {
  const t0 = Date.now();
  const out = { mode: 'C', client: name, video, attempts: [], resolveOk: false,
    chunksOk: 0, status: null, ms: null };
  let consecFail = 0;
  let goodUrl = null;
  for (let a = 1; a <= 3; a += 1) {
    const r = await resolveClient(name, video, 'invalid');
    out.attempts.push({ n: a, ok: r.ok, error: r.error || null,
      playability: r.playability || null, vdAttached: !!r.vdAttached });
    if (r.ok && r.url) {
      consecFail = 0;
      goodUrl = r;
      break;
    }
    consecFail += 1;
    if (consecFail >= 2) break; // unhealthy threshold
    await sleep(1000);
  }
  if (!goodUrl) {
    out.resolveOk = false;
    out.status = 'C_UNHEALTHY';
    out.ms = Date.now() - t0;
    return out;
  }
  out.resolveOk = true;
  out.urlHost = goodUrl.host; out.itag = goodUrl.itag;
  // try first 3 chunks with the invalid-VD url (no recovery)
  for (let i = 0; i < 3; i += 1) {
    const start = i * CHUNK;
    const end = Math.min(start + CHUNK - 1, goodUrl.total - 1);
    const g = await getRange(goodUrl.url, start, end);
    if (g.status === 206 && g.buf && g.buf.byteLength === end - start + 1) {
      out.chunksOk += 1;
    } else {
      out.chunkFail = { idx: i, status: g.status };
      break;
    }
    await sleep(BETWEEN_CHUNKS_MS);
  }
  out.status = out.chunksOk === 3 ? 'C_HEALTHY' : 'C_URL_DEAD';
  out.ms = Date.now() - t0;
  return out;
}

(async () => {
  const o = parseArgs(process.argv.slice(2));
  fs.mkdirSync(o.out, { recursive: true });
  const jsonl = path.join(o.out, 'results.jsonl');
  const log = (obj) => fs.appendFileSync(jsonl, JSON.stringify(obj) + '\n');
  console.log(`SONGS: ${o.songs.length} CLIENTS: ${o.clients.join(',')} OUT: ${o.out} SEED: ${o.seed}`);
  for (const name of o.clients) {
    const blacklist = new Set(); // per-video below; chain-wide fallback skips blacklisted
    for (const video of o.songs) {
      const songDir = path.join(o.out, name, video);
      fs.mkdirSync(songDir, { recursive: true });
      const bl = new Set([...blacklist].filter((k) => k.startsWith(video + ':')).map((k) => k.split(':')[1]));
      const a = await scenarioA(name, video, songDir);
      log(a);
      console.log(`[A ${name} ${video}] resolve=${a.resolve.ok ? `itag${a.resolve.itag}@${a.resolve.host}` : 'FAIL:' + a.resolve.error} chunks=${a.chunksOk} cached=${a.cachedBytes} 403s=${a.http403} status=${a.status} ${a.ms}ms`);
      const b = await scenarioB(name, video, songDir, o.seed, bl);
      log(b);
      if (b.blacklisted) blacklist.add(`${video}:${name}`);
      console.log(`[B ${name} ${video}] sab=${b.sabotage} induced=${b.inducedStatus} recovered=${b.recoveredStep}${b.subClient ? ` sub=${b.subClient}` : ''} chunks=${b.chunksOk} cached=${b.cachedBytes}/${b.total} 403s=${b.http403} refresh=${b.refreshes} status=${b.status} ${b.ms}ms`);
      const c = await scenarioC(name, video);
      log(c);
      console.log(`[C ${name} ${video}] attempts=${c.attempts.map((x) => (x.ok ? 'OK' : x.error)).join(' | ')} chunks=${c.chunksOk}/3 status=${c.status} ${c.ms}ms`);
      await sleep(1500);
    }
  }
  console.log(`DONE. raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
