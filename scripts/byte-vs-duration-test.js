#!/usr/bin/env node
/* Byte-vs-duration hypothesis validation (no ADB).
   For each videoId: resolve audio itag 251 (opus) via WEB_REMIX, record
   metadata duration (lengthSeconds) + total bytes (contentLength, verified
   with a bytes=0-0 Range probe), estimate true duration from bytes using the
   format bitrate (fallback 128000), classify MATCH <5% / MISMATCH >5% /
   CRITICAL >20%.
   Usage: node scripts/byte-vs-duration-test.js [--out=DIR]
   Writes results.jsonl to out dir (default Temp/byte-vs-duration). */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
const REQ_TIMEOUT_MS = 25000;
const FALLBACK_BPS = 128000;

const VIDEOS = [
  'dQw4w9WgXcQ', '9bZkp7q19f0', 'e-ORhEE9VVg', 'RgKAFK5djSk', 'kJQP7kiw5Fk',
  'k15wcUamnJA', 'hTWKbfoikeg', '60ItHLz5WEA', 'JGwWNGJdvx8', 'YQHsXMglC9A',
];

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

async function resolveAudio(videoId) {
  const body = JSON.stringify({
    context: { client: {
      clientName: 'ANDROID', clientVersion: '20.10.38', androidSdkVersion: 35, hl: 'en', gl: 'US',
    } },
    videoId, racyCheckOk: true, contentCheckOk: true,
  });
  const res = await fetchTimeout('https://www.youtube.com/youtubei/v1/player?prettyPrint=false', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)',
      'X-Goog-Api-Format-Version': '2' },
    body,
  });
  if (!res.ok) return { ok: false, error: `player HTTP ${res.status}` };
  const data = await res.json();
  const ps = data.playabilityStatus || {};
  const vd = data.videoDetails || {};
  const fmts = [...(data.streamingData?.adaptiveFormats || []), ...(data.streamingData?.formats || [])];
  const aud = fmts.filter((f) => f.mimeType && f.mimeType.includes('audio/') && f.url);
  if (!aud.length) return { ok: false, error: `no audio w/ url (playability=${ps.status})` };
  aud.sort((a, b) => {
    const ao = /itag.?251|\b251\b/.test(String(a.itag)) || (a.mimeType.includes('opus') ? 1 : 0);
    const bo = /itag.?251|\b251\b/.test(String(b.itag)) || (b.mimeType.includes('opus') ? 1 : 0);
    const av = a.itag === 251 ? 2 : (a.mimeType.includes('opus') ? 1 : 0);
    const bv = b.itag === 251 ? 2 : (b.mimeType.includes('opus') ? 1 : 0);
    if (av !== bv) return bv - av;
    return (b.bitrate || 0) - (a.bitrate || 0);
  });
  const best = aud[0];
  return {
    ok: true,
    title: vd.title || null,
    metaSec: vd.lengthSeconds ? parseInt(vd.lengthSeconds, 10) : null,
    itag: best.itag, mime: best.mimeType, bitrate: best.bitrate || null,
    clen: best.contentLength ? parseInt(best.contentLength, 10) : null,
    url: best.url,
  };
}

async function probeTotal(url) {
  // bytes=0-0: cheap, proves URL alive + authoritative total via content-range.
  const r = await fetchTimeout(url, { headers: { Range: 'bytes=0-0', 'User-Agent': UA } });
  if (r.status !== 206 && r.status !== 200) return { ok: false, error: `probe HTTP ${r.status}` };
  await r.arrayBuffer().catch(() => null);
  const cr = r.headers.get('content-range');
  const m = cr && cr.match(/\/(\d+)\s*$/);
  const cl = r.headers.get('content-length');
  const total = m ? parseInt(m[1], 10) : (cl ? parseInt(cl, 10) : null);
  if (!total) return { ok: false, error: 'probe: no total' };
  return { ok: true, total };
}

(async () => {
  let out = path.join(os.tmpdir(), 'byte-vs-duration');
  for (const a of process.argv.slice(2)) {
    if (a.startsWith('--out=')) out = a.slice(6);
  }
  fs.mkdirSync(out, { recursive: true });
  const jsonl = path.join(out, 'results.jsonl');
  fs.writeFileSync(jsonl, '');
  const log = (o) => fs.appendFileSync(jsonl, JSON.stringify(o) + '\n');
  console.log('VIDEOS: ' + VIDEOS.length + '  OUT: ' + out);
  console.log('| VideoId | Judul | Metadata (s) | Byte Est (s) | Selisih (s) | Selisih (%) | Klasifikasi |');
  for (const vid of VIDEOS) {
    const row = { videoId: vid };
    try {
      const r = await resolveAudio(vid);
      if (!r.ok) {
        row.status = 'FAIL_RESOLVE'; row.error = r.error;
        console.log(`| ${vid} | - | - | - | - | - | FAIL_RESOLVE ${r.error} |`);
        log(row); await sleep(1500); continue;
      }
      const p = await probeTotal(r.url);
      if (!p.ok) {
        row.status = 'FAIL_PROBE'; row.error = p.error;
        console.log(`| ${vid} | - | - | - | - | - | FAIL_PROBE ${p.error} |`);
        log(row); await sleep(1500); continue;
      }
      const bps = r.bitrate || FALLBACK_BPS;
      const est = (p.total * 8) / bps;
      const diff = r.metaSec == null ? null : Math.abs(r.metaSec - est);
      const pct = r.metaSec == null ? null : (100 * diff) / r.metaSec;
      const cls = pct == null ? 'UNKNOWN' : pct < 5 ? 'MATCH' : pct > 20 ? 'CRITICAL' : 'MISMATCH';
      Object.assign(row, { status: 'OK', title: r.title, metaSec: r.metaSec,
        itag: r.itag, bitrate: r.bitrate, bitrateSrc: r.bitrate ? 'format' : 'fallback128k',
        totalBytes: p.total, byteEstSec: +est.toFixed(1),
        diffSec: diff == null ? null : +diff.toFixed(1),
        diffPct: pct == null ? null : +pct.toFixed(1), classification: cls });
      console.log(`| ${vid} | ${(r.title || '-').slice(0, 28)} | ${r.metaSec} | ${est.toFixed(1)} | ` +
        `${diff == null ? '-' : diff.toFixed(1)} | ${pct == null ? '-' : pct.toFixed(1)} | ${cls} |`);
      log(row);
    } catch (e) {
      row.status = 'FAIL_EXCEPTION'; row.error = String(e.message || e);
      console.log(`| ${vid} | - | - | - | - | - | FAIL_EXCEPTION |`);
      log(row);
    }
    await sleep(1500);
  }
  console.log(`DONE. raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
