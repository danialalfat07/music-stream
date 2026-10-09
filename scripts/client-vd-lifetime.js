#!/usr/bin/env node
/* VD effective-lifetime measurement.
   Fetch up to N visitorDatas staggered (1/min); probe each every 60s with
   resolve + 3-chunk download on a fixed probe song until first failure
   (resolve !ok OR any chunk != 206) or CAP age (censored).
   Resumable across invocations via vd-state.json; each run works ~budget min.
   Usage: node scripts/client-vd-lifetime.js [--out=DIR] [--vds=N] [--cap-min=M]
     [--probe-sec=S] [--budget-min=B] [--summary] [--song=ID] */
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');

const DEFAULT_OUT = 'C:\\Users\\Danial\\AppData\\Local\\Temp\\client-vd-lifetime';
const CHUNK = 102400;
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
  const o = { out: DEFAULT_OUT, vds: 10, capMin: 15, probeSec: 60, budgetMin: 8.5,
    summary: false, song: 'dQw4w9WgXcQ' };
  for (const a of argv) {
    if (a.startsWith('--out=')) o.out = a.slice(6);
    else if (a.startsWith('--vds=')) o.vds = Math.max(1, parseInt(a.slice(6), 10) || 10);
    else if (a.startsWith('--cap-min=')) o.capMin = Math.max(1, parseFloat(a.slice(10)) || 15);
    else if (a.startsWith('--probe-sec=')) o.probeSec = Math.max(10, parseInt(a.slice(12), 10) || 60);
    else if (a.startsWith('--budget-min=')) o.budgetMin = Math.max(1, parseFloat(a.slice(13)) || 8.5);
    else if (a === '--summary') o.summary = true;
    else if (a.startsWith('--song=')) o.song = a.slice(7).trim() || o.song;
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

async function fetchVdOnce() {
  const ctx = JSON.parse(JSON.stringify(DEF.context));
  const res = await fetchTimeout(`${DEF.gapis}visitor_id?prettyPrint=false`, {
    method: 'POST', headers: DEF.headers, body: JSON.stringify({ context: ctx }),
  });
  if (!res.ok) throw new Error(`visitor_id HTTP ${res.status}`);
  const vd = (await res.json())?.responseContext?.visitorData;
  if (!vd) throw new Error('visitor_id: no visitorData');
  return vd;
}

async function resolveWithVd(video, vd) {
  const ctx = JSON.parse(JSON.stringify(DEF.context));
  if (vd) ctx.client.visitorData = vd;
  try {
    const res = await fetchTimeout(DEF.endpoint, {
      method: 'POST', headers: DEF.headers,
      body: JSON.stringify({ context: ctx, videoId: video, racyCheckOk: true, contentCheckOk: true }),
    });
    if (!res.ok) return { ok: false, error: `player HTTP ${res.status}` };
    const data = await res.json();
    const sd = data.streamingData;
    if (!sd) {
      const ps = data.playabilityStatus || {};
      return { ok: false, error: `no streamingData playability=${ps.status} reason=${ps.reason || '-'}` };
    }
    const audios = [...(sd.adaptiveFormats || []), ...(sd.formats || [])]
      .filter((f) => f.mimeType && f.mimeType.includes('audio/'));
    if (!audios.length) return { ok: false, error: 'no audio formats' };
    audios.sort((a, b) => {
      const ao = a.mimeType.includes('opus') ? 1 : 0;
      const bo = b.mimeType.includes('opus') ? 1 : 0;
      if (ao !== bo) return bo - ao;
      return (b.bitrate || 0) - (a.bitrate || 0);
    });
    const best = audios[0];
    if (!best.url) return { ok: false, error: `cipher-only itag ${best.itag}` };
    return { ok: true, url: best.url, itag: best.itag, host: new URL(best.url).host };
  } catch (e) {
    return { ok: false, error: `exception: ${e.message}` };
  }
}

async function getRange(url, start, end) {
  try {
    const r = await fetchTimeout(url, {
      headers: { Range: `bytes=${start}-${end}`, 'User-Agent': CHUNK_UA },
    });
    await r.arrayBuffer();
    return { status: r.status };
  } catch (e) {
    return { status: 'ERR', err: String(e.message || e) };
  }
}

function loadState(out) {
  const p = path.join(out, 'vd-state.json');
  try {
    return JSON.parse(fs.readFileSync(p, 'utf8'));
  } catch {
    return { vds: [], lastFetchTs: 0, song: null };
  }
}
function saveState(out, st) {
  fs.writeFileSync(path.join(out, 'vd-state.json'), JSON.stringify(st, null, 1));
}

(async () => {
  const o = parseArgs(process.argv.slice(2));
  fs.mkdirSync(o.out, { recursive: true });
  const jsonl = path.join(o.out, 'results.jsonl');
  const log = (obj) => fs.appendFileSync(jsonl, JSON.stringify(obj) + '\n');
  const st = loadState(o.out);
  if (!st.song) st.song = o.song;

  if (o.summary) {
    console.log('VD | fetchTs | failTs | ageMin | result | note');
    for (const v of st.vds) {
      const age = v.failTs ? ((v.failTs - v.fetchTs) / 60000).toFixed(1)
        : (((Date.now() - v.fetchTs) / 60000).toFixed(1) + '+');
      console.log(`#${v.n} | ${new Date(v.fetchTs).toISOString().slice(11, 19)} | ` +
        `${v.failTs ? new Date(v.failTs).toISOString().slice(11, 19) : '-'} | ${age} | ` +
        `${v.fetchFail ? 'FETCH_FAIL' : v.failKind ? 'FAIL(' + v.failKind + ')' : v.censored ? 'CENSORED' : 'ACTIVE'} | ${v.note || ''}`);
    }
    return;
  }

  console.log(`VD-LIFETIME song=${st.song} target=${o.vds} cap=${o.capMin}min probe=${o.probeSec}s budget=${o.budgetMin}min OUT: ${o.out}`);
  const runStart = Date.now();
  const deadline = runStart + o.budgetMin * 60000;
  const grid0 = runStart;
  let tick = 0;
  for (;;) {
    const now = Date.now();
    if (now >= deadline) break;
    // (a) fetch next VD if due (staggered 1/min)
    const pending = st.vds.length;
    if (pending < o.vds && (pending === 0 || now - st.lastFetchTs >= 60000)) {
      const n = pending + 1;
      try {
        const vd = await fetchVdOnce();
        st.vds.push({ n, vd, fetchTs: Date.now(), done: false });
        st.lastFetchTs = Date.now();
        console.log(`[FETCH VD#${n}] ok`);
        log({ mode: 'VD_FETCH', n, fetchTs: st.lastFetchTs });
      } catch (e) {
        await sleep(5000);
        try {
          const vd = await fetchVdOnce();
          st.vds.push({ n, vd, fetchTs: Date.now(), done: false });
          st.lastFetchTs = Date.now();
          console.log(`[FETCH VD#${n}] ok (retry)`);
          log({ mode: 'VD_FETCH', n, fetchTs: st.lastFetchTs, retried: true });
        } catch (e2) {
          st.vds.push({ n, vd: null, fetchTs: Date.now(), done: true, fetchFail: true, note: String((e2 && e2.message) || e2) });
          st.lastFetchTs = Date.now();
          console.log(`[FETCH VD#${n}] FAIL ${e2.message}`);
          log({ mode: 'VD_FETCH', n, ok: false, error: String((e2 && e2.message) || e2) });
        }
      }
      saveState(o.out, st);
    }
    // (b) probe all active VDs
    for (const v of st.vds) {
      if (v.done || !v.vd) continue;
      const ageMin = (Date.now() - v.fetchTs) / 60000;
      if (ageMin >= o.capMin) {
        v.done = true; v.censored = true;
        console.log(`[VD#${v.n}] CENSORED age=${ageMin.toFixed(1)}min`);
        log({ mode: 'VD_CENSORED', n: v.n, ageMin: +ageMin.toFixed(1) });
        continue;
      }
      const r = await resolveWithVd(st.song, v.vd);
      if (!r.ok) {
        v.done = true; v.failTs = Date.now(); v.failKind = 'resolve';
        v.note = r.error;
        console.log(`[VD#${v.n}] FAIL-resolve age=${ageMin.toFixed(1)}min ${r.error}`);
        log({ mode: 'PROBE', n: v.n, ageMin: +ageMin.toFixed(1), ok: false, kind: 'resolve', detail: r.error });
        continue;
      }
      let bad = null;
      for (let i = 0; i < 3; i += 1) {
        const g = await getRange(r.url, i * CHUNK, Math.min(i * CHUNK + CHUNK - 1, 3 * CHUNK - 1));
        if (g.status !== 206) { bad = { chunk: i, status: g.status }; break; }
      }
      if (bad) {
        v.done = true; v.failTs = Date.now(); v.failKind = 'dl';
        v.note = `chunk${bad.chunk}=${bad.status}`;
        console.log(`[VD#${v.n}] FAIL-dl age=${ageMin.toFixed(1)}min ${v.note}`);
        log({ mode: 'PROBE', n: v.n, ageMin: +ageMin.toFixed(1), ok: false, kind: 'dl', detail: v.note });
      } else {
        console.log(`[VD#${v.n}] ok age=${ageMin.toFixed(1)}min host=${r.host}`);
        log({ mode: 'PROBE', n: v.n, ageMin: +ageMin.toFixed(1), ok: true, host: r.host });
      }
    }
    saveState(o.out, st);
    const allDone = st.vds.length >= o.vds && st.vds.every((v) => v.done);
    if (allDone) { console.log('ALL DONE'); break; }
    tick += 1;
    const nextGrid = grid0 + tick * o.probeSec * 1000;
    const wait = nextGrid - Date.now();
    if (wait > 0) await sleep(wait);
  }
  saveState(o.out, st);
  console.log(`RUN END. state: ${path.join(o.out, 'vd-state.json')} raw: ${jsonl}`);
})().catch((e) => { console.error('FATAL', e); process.exit(1); });
