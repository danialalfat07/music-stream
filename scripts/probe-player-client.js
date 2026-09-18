#!/usr/bin/env node
/* Isolated experiment: resolve YouTube googlevideo URL via selectable player client,
   then raw HTTP Range probe on the SAME URL (chunk1-4) to test >512KB accessibility.
   Usage:
     YOUTUBE_PLAYER_CLIENT=current  node scripts/probe-player-client.js [videoId]
     YOUTUBE_PLAYER_CLIENT=visionos node scripts/probe-player-client.js [videoId]
     YOUTUBE_PLAYER_CLIENT=android  node scripts/probe-player-client.js [videoId]
     YOUTUBE_PLAYER_CLIENT=mweb     node scripts/probe-player-client.js [videoId]
   Does NOT touch server.js / public/app.js. Read-only resolver + probe. */
'use strict';

const VIDEO_ID = process.argv[2] || 'M7lc1UVf-VE';
const CLIENT = (process.env.YOUTUBE_PLAYER_CLIENT || 'current').toLowerCase();

const CHUNKS = [
  { label: 'chunk1', range: 'bytes=0-524287' },
  { label: 'chunk2', range: 'bytes=524288-1048575' },
  { label: 'chunk3', range: 'bytes=1048576-1572863' },
  { label: 'chunk4', range: 'bytes=1572864-2097151' },
];

// ---- player client definitions (mirror server.js ANDROID_CONTEXT + PipePipe visionos) ----
const CLIENTS = {
  current: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: {
      client: {
        clientName: 'ANDROID', clientVersion: '20.10.38', androidSdkVersion: 35, hl: 'en', gl: 'US',
      },
    },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  android: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: {
      client: {
        clientName: 'ANDROID', clientVersion: '21.03.38', androidSdkVersion: 35, hl: 'en', gl: 'US',
      },
    },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.android.youtube/21.03.38 (Linux; U; Android 14; en_US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  visionos: {
    endpoint: 'https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false',
    context: {
      client: {
        clientName: 'VISIONOS', clientVersion: '1.02', deviceModel: 'RealityDevice14,1',
        osName: 'visionOS', osVersion: '25.6.0.23O471', hl: 'en', gl: 'US',
      },
    },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)',
      'X-Goog-Api-Format-Version': '2',
    },
  },
  mweb: {
    endpoint: 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false',
    context: {
      client: {
        clientName: 'MWEB', clientVersion: '2.20240923.02.00', hl: 'en', gl: 'US',
      },
    },
    headers: {
      'Content-Type': 'application/json',
      'User-Agent': 'Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1',
      'X-Goog-Api-Format-Version': '2',
    },
  },
};

async function getVisitorData(gapisEndpoint, context, headers) {
  const res = await fetch(`${gapisEndpoint}visitor_id?prettyPrint=false`, {
    method: 'POST', headers, body: JSON.stringify({ context }),
  });
  if (!res.ok) throw new Error(`visitor_id HTTP ${res.status}`);
  const data = await res.json();
  const vd = data?.responseContext?.visitorData;
  if (!vd) throw new Error('could not get visitorData');
  return vd;
}

async function resolve(urlStr, context, headers, needVisitor = false) {
  if (needVisitor) {
    const gapisBase = urlStr.substring(0, urlStr.indexOf('/player'));
    context.client.visitorData = await getVisitorData(gapisBase + '/', context, headers);
  }
  const res = await fetch(urlStr, {
    method: 'POST', headers,
    body: JSON.stringify({ context, videoId: VIDEO_ID, racyCheckOk: true, contentCheckOk: true }),
  });
  if (!res.ok) throw new Error(`player HTTP ${res.status}`);
  const data = await res.json();
  const sd = data.streamingData;
  if (!sd) {
    const ps = data.playabilityStatus || {};
    throw new Error(`no streamingData playability=${ps.status} reason=${ps.reason}`);
  }
  const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
  const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
  if (!audios.length) throw new Error('no audio formats');
  audios.sort((a, b) => {
    const aO = a.mimeType.includes('opus') ? 1 : 0;
    const bO = b.mimeType.includes('opus') ? 1 : 0;
    if (aO !== bO) return bO - aO;
    return (b.bitrate || 0) - (a.bitrate || 0);
  });
  const best = audios[0];
  if (!best.url) throw new Error(`best has no url (itag ${best.itag}) cipher=${!!best.signatureCipher}`);
  return {
    url: best.url, itag: best.itag, mime: best.mimeType,
    bitrate: best.bitrate, clen: best.contentLength,
    host: new URL(best.url).host,
  };
}

async function probeRange(url, rangeHeader) {
  try {
    const r = await fetch(url, { headers: { Range: rangeHeader } });
    const buf = await r.arrayBuffer();
    return {
      status: r.status, bytes: buf.byteLength,
      clen: r.headers.get('content-length'), cr: r.headers.get('content-range'),
    };
  } catch (e) {
    return { status: 'ERR', bytes: 0, clen: '-', cr: String(e) };
  }
}

(async () => {
  const def = CLIENTS[CLIENT];
  if (!def) {
    console.error(`Unknown YOUTUBE_PLAYER_CLIENT="${CLIENT}". Options: ${Object.keys(CLIENTS).join(', ')}`);
    process.exit(2);
  }
  console.log(`CLIENT: ${CLIENT}`);
  const needVisitor = CLIENT === 'visionos';
  const info = await resolve(def.endpoint, def.context, def.headers, needVisitor);
  console.log(`URL HOST: ${info.host}`);
  console.log(`ITAG: ${info.itag}  MIME: ${info.mime}  BITRATE: ${info.bitrate}  CLEN: ${info.clen}`);

  // raw probe, SAME URL every request, no re-resolve
  const results = [];
  for (const c of CHUNKS) {
    const r = await probeRange(info.url, c.range);
    results.push({ ...c, ...r });
  }
  const urlStrings = Array(4).fill(info.url);
  const same = urlStrings.every((u) => u === urlStrings[0]);

  console.log('URL SAME: ' + (same ? 'YES' : 'NO'));
  for (const r of results) {
    console.log(`${r.label}: status=${r.status} clen=${r.clen} content-range=${r.cr} bytes=${r.bytes}`);
  }
  const chunk2 = results.find((r) => r.label === 'chunk2');
  const chunk3 = results.find((r) => r.label === 'chunk3');
  const chunk4 = results.find((r) => r.label === 'chunk4');
  const ok = (r) => r && (r.status === 206 || r.status === 200);
  console.log(`chunk2 >512KB: ${ok(chunk2) ? 'PASS' : 'FAIL'}`);
  console.log(`chunk3 >512KB: ${ok(chunk3) ? 'PASS' : 'FAIL'}`);
  console.log(`chunk4 >512KB: ${ok(chunk4) ? 'PASS' : 'FAIL'}`);
})().catch((e) => {
  console.error(`RESOLVE FAILED: ${e.message}`);
  process.exit(1);
});