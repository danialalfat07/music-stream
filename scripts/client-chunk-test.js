#!/usr/bin/env node
/* Client chunk-download comparison test.
   For each InnerTube player client: resolve audio URL (prefer itag 251/opus),
   then download byte 0..totalBytes in production-size Range chunks (102400),
   appending to a .part file. On HTTP 403: retry same URL once, then re-resolve
   (URL refresh, max 3x) and resume. Repeats N rounds per client.
   Usage:
     node scripts/client-chunk-test.js [--resolve-only] [--clients=A,B,...]
       [--rounds=N] [--video=VIDEOID] [--out=DIR]
   Defaults: all 8 clients, 3 rounds, video dQw4w9WgXcQ, out TMPDIR/client-chunk-test.
   Read-only vs repo source; writes only .part files + results.jsonl under out dir. */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const CHUNK = 102400; // production VisionOsCache.CHUNK
const CHUNK_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const REQ_TIMEOUT_MS = 25000;
const BETWEEN_CHUNKS_MS = 150;
const BETWEEN_ROUNDS_MS = 2000;
const BETWEEN_CLIENTS_MS = 3000;
const MAX_REFRESH = 3;

const CLIENTS = {
  VISIONOS: {
    endpoint: 'https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false',
    needVisitor: true,
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
  WEB_REMIX: {
    endpoint: 'https://music.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'WEB_REMIX', clientVersion: '1.20240101.00.00', hl: 'id', gl: 'ID',
    } },
    headers: {
      'Content-Type': 'application/json',
      Origin: 'https://music.youtube.com',
      Referer: 'https://music.youtube.com/',
      'User-Agent': CHUNK_UA,
    },
  },
  TVHTML5: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'TVHTML5', clientVersion: '7.20250212.10.00', hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': CHUNK_UA,
      'X-Goog-Api-Format-Version': '2',
    },
  },
  ANDROID_VR: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
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
  IOS: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'IOS', clientVersion: '19.29.1', deviceMake: 'Apple',
      deviceModel: 'iPhone15,2', hl: 'en', gl: 'US',
      osName: 'iPhone', osVersion: '17_4 like Mac OS X',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.ios.youtube/19.29.1 (iPhone15,2; U; CPU iPhone OS 17_4 like Mac OS X)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  MWEB: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'MWEB', clientVersion: '2.20240923.02.00', hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  ANDROID: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'ANDROID', clientVersion: '20.10.38', androidSdkVersion: 35, hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  WEB: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: { client: {
      clientName: 'WEB', clientVersion: '2.20250222.10.00', hl: 'en', gl: 'US',
    } },
    headers: {
      'Content-Type': 'application/json',
      Origin: 'https://www.youtube.com',
      Referer: 'https://www.youtube.com/',
      'User-Agent': CHUNK_UA,
    },
  },
};

function parseArgs(argv) {
  const o = { resolveOnly: false, clients: Object.keys(CLIENTS), rounds: 3,
    video: 'dQw4w9WgXcQ', out: path.join(os.tmpdir(), 'client-chunk-test') };
  for (const a of argv) {
    if (a === '--resolve-only') o.resolveOnly = true;
    else if (a.startsWith('--clients=')) o.clients = a.slice(10).split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
    else if (a.startsWith('--rounds=')) o.rounds = Math.max(1, parseInt(a.slice(9), 10) || 1);
    else if (a.startsWith('--video=')) o.video = a.slice(8).trim();
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

async function resolveClient(name, videoId) {
  const def = CLIENTS[name];
  const ctx = JSON.parse(JSON.stringify(def.context));
  try {
    if (def.needVisitor) {
      const base = def.endpoint.substring(0, def.endpoint.indexOf('/player'));
      const vr = await fetchTimeout(`${base}/visitor_id?prettyPrint=false`, {
        method: 'POST', headers: def.headers, body: JSON.stringify({ context: ctx }),
      });
      if (!vr.ok) return { ok: false, error: `visitor_id HTTP ${vr.status}` };
      const vd = (await vr.json())?.responseContext?.visitorData;
      if (!vd) return { ok: false, error: 'visitor_id: no visitorData' };
      ctx.client.visitorData = vd;
    }
    const res = await fetchTimeout(def.endpoint, {
      method: 'POST', headers: def.headers,
      body: JSON.stringify({ context: ctx, videoId, racyCheckOk: true, contentCheckOk: true }),
    });
    if (!res.ok) return { ok: false, error: `player HTTP ${res.status}` };
    const data = await res.json();
    const ps = data.playabilityStatus || {};
    const sd = data.streamingData;
    if (!sd) return { ok: false, error: `no streamingData playability=${ps.status} reason=${ps.reason || '-'}` };
    const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
    const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
    if (!audios.length) return { ok: false, error: 'no audio formats' };
    audios.sort((a, b) => {
      const ao = a.mimeType.includes('opus') ? 1 : 0;
      const bo = b.mimeType.includes('opus') ? 1 : 0;
      if (ao !== bo) return bo - ao;
      return (b.bitrate || 0) - (a.bitrate || 0);
    });
    const best = audios[0];
    if (!best.url) return { ok: false, error: `cipher-only (itag ${best.itag}, no direct url)`, itag: best.itag, playability: ps.status };
    return {
      ok: true, url: best.url, itag: best.itag, mime: best.mimeType,
      bitrate: best.bitrate, clen: best.contentLength ? parseInt(best.contentLength, 10) : null,
      host: new URL(best.url).host, playability: ps.status || 'OK',
    };
  } catch (e) {
    return { ok: false, error: `exception: ${e.message}` };
  }
}

async function getRange(url, start, end) {
  try {
    const r = await fetchTimeout(url, {
      headers: { Range: `bytes=${start}-${end}`, 'User-Agent': CHUNK_UA },
    });
    const buf = Buffer.from(await r.arrayBuffer());
    return { status: r.status, bytes: buf.byteLength, buf,
      cr: r.headers.get('content-range'), cl: r.headers.get('content-length') };
  } catch (e) {
    return { status: 'ERR', bytes: 0, buf: null, cr: null, cl: null, err: String(e.message || e) };
  }
}

function parseTotal(cr) {
  const m = cr && cr.match(/\/(\d+)\s*$/);
  return m ? parseInt(m[1], 10) : null;
}

async function runRound(name, videoId, round, clientDir) {
  const t0 = Date.now();
  const res = await resolveClient(name, videoId);
  const out = {
    client: name, round, videoId,
    resolve: res.ok
      ? { ok: true, itag: res.itag, mime: res.mime, bitrate: res.bitrate, host: res.host,
          playability: res.playability, clen: res.clen }
      : { ok: false, error: res.error, itag: res.itag || null },
    total: null, chunksTotal: null, chunksOk: 0, firstFailByte: null,
    http403: 0, retries: 0, refreshes: 0, refreshOk: null,
    cachedBytes: 0, cachedPct: null, status: null, ms: null, partFile: null,
  };
  if (!res.ok) {
    out.status = 'FAIL_RESOLVE';
    out.ms = Date.now() - t0;
    return out;
  }
  let url = res.url;
  const partFile = path.join(clientDir, `round${round}.part`);
  out.partFile = partFile;
  const fd = fs.openSync(partFile, 'w');
  let received = 0;
  let total = null;
  try {
    for (;;) {
      const end = total === null ? CHUNK - 1 : Math.min(received + CHUNK - 1, total - 1);
      let r = await getRange(url, received, end);
      if (r.status === 206 && r.buf && r.buf.byteLength > 0) {
        if (total === null) {
          total = parseTotal(r.cr) || res.clen;
          if (!total) { out.status = 'FAIL_OTHER'; out.firstFailByte = received; break; }
          out.total = total;
          out.chunksTotal = Math.ceil(total / CHUNK);
        }
        fs.writeSync(fd, r.buf);
        received += r.buf.byteLength;
        out.chunksOk += 1;
        out.cachedBytes = received;
        await sleep(BETWEEN_CHUNKS_MS);
        if (received >= total) {
          out.status = received === total ? 'PASS_FULL' : 'FAIL_OTHER';
          break;
        }
        continue;
      }
      // --- failure path ---
      if (r.status === 403) out.http403 += 1;
      out.firstFailByte = out.firstFailByte === null ? received : out.firstFailByte;
      // retry 1: same URL
      await sleep(1000);
      out.retries += 1;
      r = await getRange(url, received, end);
      if (r.status === 206 && r.buf && r.buf.byteLength > 0) {
        if (total === null) {
          total = parseTotal(r.cr) || res.clen;
          if (!total) { out.status = 'FAIL_OTHER'; break; }
          out.total = total;
          out.chunksTotal = Math.ceil(total / CHUNK);
        }
        fs.writeSync(fd, r.buf);
        received += r.buf.byteLength;
        out.chunksOk += 1;
        out.cachedBytes = received;
        await sleep(BETWEEN_CHUNKS_MS);
        if (received >= total) {
          out.status = received === total ? 'PASS_FULL' : 'FAIL_OTHER';
          break;
        }
        continue;
      }
      if (r.status === 403) out.http403 += 1;
      // refresh: re-resolve and resume
      if (out.refreshes >= MAX_REFRESH) {
        out.status = r.status === 403 ? 'FAIL_403' : 'FAIL_OTHER';
        break;
      }
      out.refreshes += 1;
      const rr = await resolveClient(name, videoId);
      if (!rr.ok) {
        out.status = 'FAIL_REFRESH_RESOLVE';
        break;
      }
      url = rr.url;
      const r3 = await getRange(url, received, end);
      if (r3.status === 206 && r3.buf && r3.buf.byteLength > 0) {
        out.refreshOk = out.refreshOk === false ? true : true;
        if (total === null) {
          total = parseTotal(r3.cr) || rr.clen;
          if (!total) { out.status = 'FAIL_OTHER'; break; }
          out.total = total;
          out.chunksTotal = Math.ceil(total / CHUNK);
        }
        fs.writeSync(fd, r3.buf);
        received += r3.buf.byteLength;
        out.chunksOk += 1;
        out.cachedBytes = received;
        if (r.status === 403 && r3.status === 206) out.refreshOk = true;
        await sleep(BETWEEN_CHUNKS_MS);
        if (received >= total) {
          out.status = received === total ? 'PASS_FULL' : 'FAIL_OTHER';
          break;
        }
        continue;
      }
      if (r3.status === 403) { out.http403 += 1; out.refreshOk = false; }
      out.status = r3.status === 403 ? 'FAIL_403' : 'FAIL_OTHER';
      break;
    }
  } finally {
    fs.closeSync(fd);
  }
  if (out.total) out.cachedPct = +(100 * received / out.total).toFixed(1);
  if (out.status === 'PASS_FULL') {
    const sz = fs.statSync(partFile).size;
    if (sz !== out.total) out.status = 'FAIL_SIZE_MISMATCH';
  }
  if (out.refreshes === 0) out.refreshOk = null;
  out.ms = Date.now() - t0;
  return out;
}

(async () => {
  const o = parseArgs(process.argv.slice(2));
  fs.mkdirSync(o.out, { recursive: true });
  const jsonl = path.join(o.out, 'results.jsonl');
  const log = (obj) => fs.appendFileSync(jsonl, JSON.stringify(obj) + '\n');
  console.log(`VIDEO: ${o.video}  ROUNDS: ${o.rounds}  OUT: ${o.out}`);
  if (o.resolveOnly) {
    for (const name of o.clients) {
      if (!CLIENTS[name]) { console.log(`${name}: UNKNOWN CLIENT`); continue; }
      const r = await resolveClient(name, o.video);
      console.log(`${name}: ${r.ok
        ? `OK itag=${r.itag} host=${r.host} play=${r.playability} clen=${r.clen}`
        : `FAIL ${r.error}`}`);
      log({ mode: 'resolve-only', client: name, ...r, url: undefined });
      await sleep(1000);
    }
    return;
  }
  for (const name of o.clients) {
    if (!CLIENTS[name]) { console.log(`${name}: UNKNOWN CLIENT`); continue; }
    const cdir = path.join(o.out, name);
    fs.mkdirSync(cdir, { recursive: true });
    for (let rd = 1; rd <= o.rounds; rd += 1) {
      const r = await runRound(name, o.video, rd, cdir);
      log(r);
      const ri = r.resolve;
      console.log(`[${name} r${rd}] resolve=${ri.ok ? `itag${ri.itag}@${ri.host}` : 'FAIL:' + ri.error} ` +
        `chunks=${r.chunksOk}/${r.chunksTotal === null ? '?' : r.chunksTotal} ` +
        `cached=${r.cachedBytes}/${r.total === null ? '?' : r.total} ` +
        `403s=${r.http403} retry=${r.retries} refresh=${r.refreshes}${r.refreshOk === null ? '' : `(ok=${r.refreshOk})`} ` +
        `status=${r.status} ${r.ms}ms`);
      await sleep(BETWEEN_ROUNDS_MS);
    }
    await sleep(BETWEEN_CLIENTS_MS);
  }
  console.log(`DONE. raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
