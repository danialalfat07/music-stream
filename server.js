/* Dnialify Project - Dnialify Music Stream - backend proxy for YouTube Music InnerTube API + LRCLIB lyrics */
const express = require('express');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');
const { Readable } = require('stream');

const PKG = (() => { try { return require('./package.json'); } catch { return { version: '1.2.1' }; } })();
// TEST-ONLY guard: BLOCK_YOUTUBE=1 makes any outbound YouTube/googlevideo fetch fail loudly,
// proving the local-cache playback path has zero YouTube dependency. Does not alter logic otherwise.
if (String(process.env.BLOCK_YOUTUBE || '') === '1') {
  const YT_HOSTS = /(^|\.)(youtube\.com|music\.youtube\.com|googlevideo\.com|youtubei\.googleapis\.com|youtu\.be|ytimg\.com)$/i;
  const rawFetch = globalThis.fetch;
  globalThis.fetch = (input, init) => {
    try {
      const host = new URL(typeof input === 'string' ? input : input.url).hostname;
      if (YT_HOSTS.test(host)) {
        return Promise.reject(new Error(`BLOCK_YOUTUBE: refused ${host}`));
      }
    } catch {}
    return rawFetch(input, init);
  };
  console.warn('BLOCK_YOUTUBE=1: outbound YouTube/googlevideo requests are refused');
}
const APP_VERSION_SERVER = PKG.version || '1.2.1';
const BUILD_CHANNEL = process.env.BUILD_CHANNEL || (String(APP_VERSION_SERVER).includes('-beta') ? 'beta' : 'stable');
const app = express();
app.use(express.json());
// trust proxy for Vercel/X-Forwarded-For
app.set('trust proxy', 1);
// static with no-cache for versioned HTML/JS to prevent 2.1.5 stale loop
app.use(
  express.static(path.join(__dirname, 'public'), {
    maxAge: 0,
    etag: true,
    lastModified: true,
    setHeaders(res, filePath) {
      if (filePath.endsWith('.html') || filePath.endsWith('app.js') || filePath.endsWith('sw.js') || filePath.endsWith('styles.css')) {
        res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
        res.setHeader('Pragma', 'no-cache');
        res.setHeader('Expires', '0');
      } else {
        res.setHeader('Cache-Control', 'public, max-age=3600');
      }
    },
  }),
);
// basic rate limit: 90 req / 60s per IP for /api
const rl = new Map();
function rateLimit(req, res, next) {
  if (!req.path.startsWith('/api/')) return next();
  const ip = req.ip || req.headers['x-forwarded-for'] || 'anon';
  const now = Date.now();
  const win = 60 * 1000;
  const max = 90;
  let rec = rl.get(ip);
  if (!rec || now - rec.start > win) rec = { start: now, count: 1 };
  else rec.count++;
  rl.set(ip, rec);
  if (rec.count > max)
    return res.status(429).json({ error: 'Too many requests, slow down.' });
  res.setHeader('X-RateLimit-Remaining', String(Math.max(0, max - rec.count)));
  next();
}
app.use(rateLimit);
// security headers
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  next();
});

const YTM = 'https://music.youtube.com/youtubei/v1';
const CONTEXT = {
  client: {
    clientName: 'WEB_REMIX',
    clientVersion: '1.20240101.00.00',
    hl: 'id',
    gl: 'ID',
  },
};
const HEADERS = {
  'Content-Type': 'application/json',
  Origin: 'https://music.youtube.com',
  Referer: 'https://music.youtube.com/',
  'User-Agent':
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36',
};

async function yt(endpoint, body = {}, query = '') {
  const res = await fetch(`${YTM}/${endpoint}?prettyPrint=false${query}`, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify({ context: CONTEXT, ...body }),
  });
  if (!res.ok) throw new Error(`YTM ${endpoint} -> ${res.status}`);
  return res.json();
}

/* ---------------- deep helpers ---------------- */
function findAll(obj, key, out = []) {
  if (!obj || typeof obj !== 'object') return out;
  if (Array.isArray(obj)) {
    for (const v of obj) findAll(v, key, out);
    return out;
  }
  for (const k of Object.keys(obj)) {
    if (k === key) out.push(obj[k]);
    findAll(obj[k], key, out);
  }
  return out;
}
const findFirst = (obj, key) => findAll(obj, key)[0];

const text = (o) =>
  o && o.runs ? o.runs.map((r) => r.text).join('') : (o && o.simpleText) || '';

function normalizeDuration(s) {
  const t = String(s || '').trim();
  if (/^\d{1,2}(\.\d{2}){1,2}$/.test(t)) return t.replace(/\./g, ':');
  return t;
}

function runsInfo(o) {
  // extract artists/albums with browseIds from runs
  const out = [];
  if (!o || !o.runs) return out;
  for (const r of o.runs) {
    const be = r.navigationEndpoint && r.navigationEndpoint.browseEndpoint;
    if (be) out.push({ name: r.text, browseId: be.browseId });
  }
  return out;
}

function thumbs(o) {
  const t = findAll(o, 'thumbnails')
    .flat()
    .filter((x) => x && x.url);
  if (!t.length) return null;
  const best = t.reduce((a, b) => ((b.width || 0) >= (a.width || 0) ? b : a));
  return upscale(best.url);
}
function upscale(url) {
  if (!url) return url;
  if (url.includes('googleusercontent.com'))
    return url.replace(/=w\d+-h\d+.*$/, '=w544-h544-l90-rj');
  return url;
}

function endpointInfo(nav) {
  if (!nav) return {};
  const we = nav.watchEndpoint;
  const be = nav.browseEndpoint;
  const wpe = nav.watchPlaylistEndpoint;
  if (we) return { videoId: we.videoId, playlistId: we.playlistId };
  if (wpe) return { playlistId: wpe.playlistId, watchPlaylist: true };
  if (be) {
    const id = be.browseId;
    let type = 'browse';
    if (id.startsWith('MPRE')) type = 'album';
    else if (id.startsWith('UC') || id.startsWith('MPLA')) type = 'artist';
    else if (
      id.startsWith('VL') ||
      id.startsWith('PL') ||
      id.startsWith('RDCLAK')
    )
      type = 'playlist';
    return { browseId: id, browseType: type };
  }
  return {};
}

/* ---------------- item parsers ---------------- */
function parseTwoRow(r) {
  const nav = r.navigationEndpoint || {};
  let info = endpointInfo(nav);
  // title may browse to album/playlist even if overlay is a watchEndpoint
  if (!info.browseId && r.title && r.title.runs) {
    const tNav = r.title.runs[0] && r.title.runs[0].navigationEndpoint;
    const extra = endpointInfo(tNav || {});
    if (extra.browseId) info = { ...info, ...extra };
  }
  let type = 'song';
  if (
    info.browseType === 'album' ||
    info.browseType === 'playlist' ||
    info.browseType === 'artist'
  )
    type = info.browseType;
  else if (info.videoId) type = 'song';
  else if (info.playlistId || info.watchPlaylist) type = 'playlist';
  const item = {
    type,
    title: text(r.title),
    subtitle: text(r.subtitle),
    thumbnail: thumbs(r.thumbnailRenderer),
    artists: runsInfo(r.subtitle),
    ...info,
  };
  // circle thumbnails => artist
  if (r.thumbnailRenderer && findFirst(r, 'musicThumbnailRenderer')) {
    const style = findFirst(r, 'musicThumbnailRenderer').thumbnailCrop;
    if (style === 'MUSIC_THUMBNAIL_CROP_CIRCLE') item.type = 'artist';
  }
  return item;
}

function parseListItem(r) {
  const cols = (r.flexColumns || []).map((c) =>
    c.musicResponsiveListItemFlexColumnRenderer
      ? c.musicResponsiveListItemFlexColumnRenderer.text
      : null,
  );
  const title = cols[0] ? text(cols[0]) : '';
  const subtitle = cols
    .slice(1)
    .map((c) => text(c))
    .filter(Boolean)
    .join(' • ');
  let videoId = null;
  if (r.playlistItemData) videoId = r.playlistItemData.videoId;
  if (!videoId && cols[0] && cols[0].runs) {
    const we =
      cols[0].runs[0] &&
      cols[0].runs[0].navigationEndpoint &&
      cols[0].runs[0].navigationEndpoint.watchEndpoint;
    if (we) videoId = we.videoId;
  }
  if (!videoId) {
    const we = findFirst(r.overlay || {}, 'watchEndpoint');
    if (we) videoId = we.videoId;
  }
  const navInfo = endpointInfo(r.navigationEndpoint);
  const artists = [];
  const albums = [];
  for (const c of cols.slice(1)) {
    for (const e of runsInfo(c)) {
      if (e.browseId.startsWith('MPRE')) albums.push(e);
      else artists.push(e);
    }
  }
  let type = videoId ? 'song' : navInfo.browseType || 'song';
  const item = {
    type,
    title,
    subtitle,
    videoId,
    thumbnail: thumbs(r.thumbnail),
    artists,
    album: albums[0] || null,
    ...navInfo,
  };
  // duration from fixed column
  const fixed = findFirst(r, 'musicResponsiveListItemFixedColumnRenderer');
  if (fixed) item.duration = normalizeDuration(text(fixed.text));
  return item;
}

function parseSections(contents) {
  const sections = [];
  for (const s of contents || []) {
    const car = s.musicCarouselShelfRenderer;
    const shelf = s.musicShelfRenderer;
    if (car) {
      const header = findFirst(car.header || {}, 'title');
      const items = (car.contents || [])
        .map((c) =>
          c.musicTwoRowItemRenderer
            ? parseTwoRow(c.musicTwoRowItemRenderer)
            : c.musicResponsiveListItemRenderer
              ? parseListItem(c.musicResponsiveListItemRenderer)
              : null,
        )
        .filter((x) => x && x.title);
      if (items.length) sections.push({ title: text(header), items });
    } else if (shelf) {
      const items = (shelf.contents || [])
        .map((c) =>
          c.musicResponsiveListItemRenderer
            ? parseListItem(c.musicResponsiveListItemRenderer)
            : null,
        )
        .filter((x) => x && x.title);
      if (items.length)
        sections.push({ title: text(shelf.title), items, list: true });
    }
  }
  return sections;
}

/* ---------------- routes ---------------- */
const cache = new Map();
function cached(key, ttlMs, fn) {
  const hit = cache.get(key);
  if (hit && Date.now() - hit.t < ttlMs) return Promise.resolve(hit.v);
  return fn().then((v) => {
    cache.set(key, { v, t: Date.now() });
    return v;
  });
}
function etagFor(obj) {
  const s = JSON.stringify(obj);
  return (
    'W/"' + crypto.createHash('sha1').update(s).digest('hex').slice(0, 16) + '"'
  );
}
function sendJsonWithCache(req, res, data, maxAgeSec) {
  const etag = etagFor(data);
  res.setHeader('ETag', etag);
  res.setHeader('Cache-Control', `public, max-age=${maxAgeSec}`);
  if (req.headers['if-none-match'] === etag) return res.status(304).end();
  res.json(data);
}

app.get('/api/home', async (req, res) => {
  try {
    const data = await cached('home_ID', 8 * 60 * 1000, async () => {
      let d = await yt('browse', { browseId: 'FEmusic_home' });
      let sections = [];
      let sl = findFirst(d, 'sectionListRenderer');
      if (sl) sections = parseSections(sl.contents);
      let cont =
        sl &&
        sl.continuations &&
        sl.continuations[0] &&
        sl.continuations[0].nextContinuationData;
      let n = 0;
      while (cont && n < 3) {
        const d2 = await yt(
          'browse',
          {},
          `&ctoken=${cont.continuation}&continuation=${cont.continuation}&type=next`,
        );
        const slc = findFirst(d2, 'sectionListContinuation');
        if (!slc) break;
        sections = sections.concat(parseSections(slc.contents));
        cont =
          slc.continuations &&
          slc.continuations[0] &&
          slc.continuations[0].nextContinuationData;
        n++;
      }
      return { sections };
    });
    sendJsonWithCache(req, res, data, 480);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/charts', async (req, res) => {
  try {
    const data = await cached('charts', 20 * 60 * 1000, async () => {
      const d = await yt('browse', { browseId: 'FEmusic_charts' });
      const sl = findFirst(d, 'sectionListRenderer');
      return { sections: sl ? parseSections(sl.contents) : [] };
    });
    sendJsonWithCache(req, res, data, 1200);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* SponsorBlock segments (skip non-music parts) */
app.get('/api/sponsorblock', async (req, res) => {
  try {
    const vid = String(req.query.videoId || '');
    const cats = encodeURIComponent(
      JSON.stringify([
        'sponsor',
        'selfpromo',
        'interaction',
        'intro',
        'outro',
        'music_offtopic',
      ]),
    );
    const r = await fetch(
      `https://sponsor.ajay.app/api/skipSegments?videoID=${encodeURIComponent(vid)}&categories=${cats}`,
    );
    if (r.status === 404) return res.json({ segments: [] });
    if (!r.ok) return res.json({ segments: [] });
    const arr = await r.json();
    res.json({
      segments: arr
        .filter((s) => s.actionType === 'skip')
        .map((s) => ({
          category: s.category,
          start: s.segment[0],
          end: s.segment[1],
        })),
    });
  } catch {
    res.json({ segments: [] });
  }
});

app.get('/api/moods', async (req, res) => {
  try {
    const data = await cached('moods', 60 * 60 * 1000, async () => {
      const d = await yt('browse', { browseId: 'FEmusic_moods_and_genres' });
      const cats = findAll(d, 'musicNavigationButtonRenderer').map((b) => ({
        title: text(b.buttonText),
        color: b.solid
          ? '#' +
            (b.solid.leftStripeColor >>> 0)
              .toString(16)
              .padStart(8, '0')
              .slice(2)
          : null,
        browseId:
          b.clickCommand &&
          b.clickCommand.browseEndpoint &&
          b.clickCommand.browseEndpoint.browseId,
        params:
          b.clickCommand &&
          b.clickCommand.browseEndpoint &&
          b.clickCommand.browseEndpoint.params,
      }));
      return { categories: cats.filter((c) => c.browseId) };
    });
    sendJsonWithCache(req, res, data, 3600);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

const SEARCH_PARAMS = {
  songs: 'EgWKAQIIAWoMEA4QChADEAQQCRAF',
  videos: 'EgWKAQIQAWoMEA4QChADEAQQCRAF',
  albums: 'EgWKAQIYAWoMEA4QChADEAQQCRAF',
  artists: 'EgWKAQIgAWoMEA4QChADEAQQCRAF',
  playlists: 'EgeKAQQoAEABagwQDhAKEAMQBBAJEAU=',
};

app.get('/api/search', async (req, res) => {
  try {
    const q = String(req.query.q || '').trim();
    if (!q) return res.json({ sections: [] });
    const filter = req.query.filter;
    const body = { query: q };
    if (filter && SEARCH_PARAMS[filter]) body.params = SEARCH_PARAMS[filter];
    const d = await yt('search', body);
    const sections = [];
    const shelves = findAll(d, 'musicShelfRenderer');
    for (const shelf of shelves) {
      const items = (shelf.contents || [])
        .map((c) =>
          c.musicResponsiveListItemRenderer
            ? parseListItem(c.musicResponsiveListItemRenderer)
            : null,
        )
        .filter((x) => x && x.title);
      if (items.length) sections.push({ title: text(shelf.title), items });
    }
    // Newer general-search layout: flat itemSectionRenderers, one item each
    if (!sections.length) {
      const flat = [];
      const seen = new Set();
      for (const sec of findAll(d, 'itemSectionRenderer')) {
        for (const c of sec.contents || []) {
          if (!c.musicResponsiveListItemRenderer) continue;
          const it = parseListItem(c.musicResponsiveListItemRenderer);
          const key = it.videoId || it.browseId || it.title;
          if (it.title && !seen.has(key)) {
            seen.add(key);
            flat.push(it);
          }
        }
      }
      if (flat.length) sections.push({ title: 'Results', items: flat });
    }
    const top = findFirst(d, 'musicCardShelfRenderer');
    if (top) {
      const info = endpointInfo(
        findFirst(top.title || {}, 'navigationEndpoint') ||
          (top.title.runs && top.title.runs[0].navigationEndpoint),
      );
      sections.unshift({
        title: 'Top result',
        items: [
          {
            type: info.videoId ? 'song' : info.browseType || 'song',
            title: text(top.title),
            subtitle: text(top.subtitle),
            thumbnail: thumbs(top.thumbnail),
            ...info,
          },
        ],
      });
    }
    res.json({ sections });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/suggest', async (req, res) => {
  try {
    const d = await yt('music/get_search_suggestions', {
      input: req.query.q || '',
    });
    const sugg = findAll(d, 'searchSuggestionRenderer').map((s) =>
      text(s.suggestion),
    );
    res.json({ suggestions: sugg });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* queue / radio for a song */
app.get('/api/next', async (req, res) => {
  try {
    const body = {
      isAudioOnly: true,
      tunerSettingValue: 'AUTOMIX_SETTING_NORMAL',
    };
    if (req.query.videoId) {
      body.videoId = req.query.videoId;
      body.playlistId = req.query.playlistId || `RDAMVM${req.query.videoId}`;
      body.watchEndpointMusicSupportedConfigs = {
        watchEndpointMusicConfig: { musicVideoType: 'MUSIC_VIDEO_TYPE_ATV' },
      };
    } else if (req.query.playlistId) {
      body.playlistId = req.query.playlistId;
    }
    if (req.query.params) body.params = req.query.params;
    const d = await yt('next', body);
    const panels = findAll(d, 'playlistPanelVideoRenderer');
    const queue = panels.map((p) => ({
      videoId: p.videoId,
      title: displayTitle(text(p.title)),
      artist: text(p.shortBylineText || p.longBylineText),
      artists: runsInfo(p.longBylineText),
      duration: text(p.lengthText),
      thumbnail: thumbs(p.thumbnail),
      selected: !!p.selected,
    }));
    // lyrics + related browse ids from tabs
    let lyricsBrowseId = null;
    let relatedBrowseId = null;
    for (const tab of findAll(d, 'tabRenderer')) {
      const id =
        tab.endpoint &&
        tab.endpoint.browseEndpoint &&
        tab.endpoint.browseEndpoint.browseId;
      if (!id) continue;
      if (id.startsWith('MPLYt')) lyricsBrowseId = id;
      if (id.startsWith('MPTRt')) relatedBrowseId = id;
    }
    res.json({ queue, lyricsBrowseId, relatedBrowseId });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.get('/api/related', async (req, res) => {
  try {
    const d = await yt('browse', { browseId: req.query.browseId });
    const sl = findFirst(d, 'sectionListRenderer');
    let sections = sl ? parseSections(sl.contents) : [];
    // some related pages use grids instead of carousels/shelves
    for (const g of findAll(d, 'gridRenderer')) {
      const items = (g.items || [])
        .map((c) => {
          if (c.musicTwoRowItemRenderer)
            return parseTwoRow(c.musicTwoRowItemRenderer);
          if (c.musicResponsiveListItemRenderer)
            return parseListItem(c.musicResponsiveListItemRenderer);
          return null;
        })
        .filter((x) => x && x.title);
      if (items.length)
        sections.push({
          title: text(findFirst(g.header || {}, 'title') || {}),
          items,
        });
    }
    // dedupe empty-title dupes & drop empty sections
    sections = sections.filter((x) => x.items && x.items.length);
    res.json({ sections });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* album / playlist / artist / mood pages */
async function browsePage(rawId, params) {
  let id = rawId || '';
  if (/^(PL|RDCLAK|VLPL|OLAK)/.test(id) && !id.startsWith('VL')) id = 'VL' + id;
  const body = { browseId: id };
  if (params) body.params = params;
  const d = await yt('browse', body);

  // header
  let header = null;
  const hResp =
    findFirst(d, 'musicResponsiveHeaderRenderer') ||
    findFirst(d, 'musicDetailHeaderRenderer') ||
    findFirst(d, 'musicImmersiveHeaderRenderer') ||
    findFirst(d, 'musicVisualHeaderRenderer') ||
    findFirst(d, 'musicEditablePlaylistDetailHeaderRenderer');
  if (hResp) {
    header = {
      title: text(hResp.title),
      subtitle: [text(hResp.subtitle), text(hResp.secondSubtitle)]
        .filter(Boolean)
        .join(' • '),
      description:
        text(hResp.description) || text(findFirst(hResp, 'description') || {}),
      thumbnail: thumbs(hResp.thumbnail || hResp.foregroundThumbnail || {}),
      artists: runsInfo(hResp.subtitle).concat(
        runsInfo(hResp.straplineTextOne),
      ),
      strapline: text(hResp.straplineTextOne),
    };
    if (!header.thumbnail) header.thumbnail = thumbs(hResp);
  }

  // shuffle/radio playlist ids
  let playlistId = null;
  const wpe = findFirst(d, 'watchPlaylistEndpoint');
  if (wpe) playlistId = wpe.playlistId;

  // track list (musicShelfRenderer or playlistShelfRenderer contents)
  let tracks = [];
  const shelves = findAll(d, 'musicShelfRenderer').concat(
    findAll(d, 'musicPlaylistShelfRenderer'),
  );
  for (const shelf of shelves) {
    const items = (shelf.contents || [])
      .map((c) =>
        c.musicResponsiveListItemRenderer
          ? parseListItem(c.musicResponsiveListItemRenderer)
          : null,
      )
      .filter((x) => x && x.title);
    if (
      items.length &&
      items.filter((i) => i.videoId).length >= items.length / 2 &&
      !tracks.length
    ) {
      tracks = items;
    }
  }

  // other sections (carousels: related albums, artist albums etc.)
  let sections = [];
  const sl = findFirst(d, 'sectionListRenderer');
  if (sl)
    sections = parseSections(sl.contents).filter(
      (s) => !s.list || !tracks.length,
    );
  // for artist pages the first musicShelf (songs) is in sections too; dedupe
  if (tracks.length)
    sections = sections.filter(
      (s) =>
        !(s.list && s.items[0] && s.items[0].videoId === tracks[0].videoId),
    );

  // grid (mood/genre pages)
  const grids = findAll(d, 'gridRenderer');
  for (const g of grids) {
    const items = (g.items || [])
      .map((c) =>
        c.musicTwoRowItemRenderer
          ? parseTwoRow(c.musicTwoRowItemRenderer)
          : null,
      )
      .filter(Boolean);
    if (items.length)
      sections.push({
        title: text(findFirst(g.header || {}, 'title') || {}),
        items,
      });
  }

  // fallback thumbnail from first track
  if (header && !header.thumbnail && tracks[0])
    header.thumbnail = tracks[0].thumbnail;
  // album pages often omit per-track artist; copy from header
  if (header && tracks.length) {
    const ha =
      (header.artists && header.artists[0]) ||
      (header.strapline ? { name: header.strapline } : null);
    if (ha && ha.name) {
      tracks = tracks.map((t) => {
        if (t.artist || (t.artists && t.artists.length)) return t;
        return {
          ...t,
          artist: ha.name,
          artists: t.artists && t.artists.length ? t.artists : [ha],
          artistBrowseId: ha.browseId || t.artistBrowseId,
        };
      });
    }
  }

  return { header, tracks, sections, playlistId };
}

app.get('/api/browse', async (req, res) => {
  try {
    res.json(await browsePage(req.query.id, req.query.params));
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* ---------------- music download via third-party converter (loader.to) ----------------
   Highest quality MP3 (320kbps). Our server orchestrates the conversion job:
   start -> poll progress -> hand the final direct file URL to the browser.
   The user never sees or visits the third-party site — the file just downloads. */
const LOADER_API = 'https://loader.to/ajax/download.php';
const DL_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';

/* start a conversion job: returns { jobId, progressUrl } */
app.get('/api/download-start', async (req, res) => {
  const videoId = String(req.query.videoId || '');
  if (!/^[\w-]{6,20}$/.test(videoId))
    return res.status(400).json({ error: 'bad id' });
  try {
    const u = `${LOADER_API}?format=mp3&url=${encodeURIComponent('https://www.youtube.com/watch?v=' + videoId)}`;
    const r = await fetch(u, {
      headers: { 'User-Agent': DL_UA, Referer: 'https://loader.to/' },
    });
    if (!r.ok) throw new Error(`start -> ${r.status}`);
    const d = await r.json();
    if (!d.success || !d.id) throw new Error('converter refused this song');
    res.json({
      jobId: d.id,
      progressUrl: d.progress_url,
      title: d.title || null,
    });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/* poll job progress: returns { progress (0-1000), done, url } */
app.get('/api/download-progress', async (req, res) => {
  const purl = String(req.query.progressUrl || '');
  try {
    const pu = new URL(purl);
    const host = pu.hostname;
    const okHost =
      host === 'loader.to' ||
      host === 'savenow.to' ||
      host === 'affadaffa.com' ||
      host.endsWith('.loader.to') ||
      host.endsWith('.savenow.to') ||
      host.endsWith('.affadaffa.com');
    if (!okHost) {
      return res.status(400).json({ error: 'bad progress url' });
    }
    const r = await fetch(purl, { headers: { 'User-Agent': DL_UA } });
    if (!r.ok) throw new Error(`progress -> ${r.status}`);
    const d = await r.json();
    res.json({
      progress: d.progress || 0,
      done: !!d.success && !!d.download_url,
      url: d.download_url || null,
      text: d.text || '',
    });
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

/* resolve a YT Music / YouTube URL (playlist, album, artist, song) into an app route */
app.get('/api/resolve', async (req, res) => {
  try {
    const raw = String(req.query.url || '').trim();
    let u;
    try {
      u = new URL(raw.includes('://') ? raw : 'https://' + raw);
    } catch {
      return res.status(400).json({ error: 'Invalid URL' });
    }
    const list = u.searchParams.get('list');
    const v = u.searchParams.get('v');
    const m = u.pathname.match(/\/(playlist|channel|browse|watch)\/?([^/]*)?/);
    if (list && !v) return res.json({ kind: 'playlist', id: list });
    if (v)
      return res.json({ kind: 'song', videoId: v, playlistId: list || null });
    if (m && m[1] === 'channel' && m[2])
      return res.json({ kind: 'artist', id: m[2] });
    if (m && m[1] === 'browse' && m[2])
      return res.json({
        kind: m[2].startsWith('MPRE') ? 'album' : 'playlist',
        id: m[2],
      });
    return res
      .status(400)
      .json({
        error:
          'Could not recognize this link. Paste a YouTube Music playlist/album/song link.',
      });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* ---------------- lyrics: multi-strategy matcher ----------------
   LRCLIB (synced) -> LRCLIB fuzzy -> YouTube Music (plain)
   -> NetEase (synced/plain) -> lyrics.ovh (plain). */

function displayTitle(t) {
  const raw = String(t || '').trim();
  if (!raw) return '';
  const cleaned = raw
    .replace(
      /\s*[\(\[]\s*official\s*(hd\s*)?(4k\s*)?(music\s*)?(lyric(s)?\s*)?(audio|video|visualizer|mv)[^\)\]]*[\)\]]/gi,
      '',
    )
    .replace(
      /\s*[\(\[]\s*(official\s*)?(hd\s*)?(music\s*)?(lyric(s)?\s*)?(audio|video|visualizer|mv)[^\)\]]*[\)\]]/gi,
      '',
    )
    .replace(
      /\s*[\(\[]\s*(official\s*)?(4k|hd|hq|8d(?:\s*audio)?|1080p|720p)\s*[\)\]]/gi,
      '',
    )
    .replace(
      /\s*-\s*(official|lyric(s)?|audio|video|visualizer|topic).*$/gi,
      '',
    )
    .replace(/\s{2,}/g, ' ')
    .trim();
  return cleaned || raw;
}
function cleanTitle(t) {
  return String(t || '')
    .replace(/\((feat|ft|with|prod)[^)]*\)/gi, '')
    .replace(/\[(feat|ft|with|prod)[^\]]*\]/gi, '')
    .replace(
      /\((official|lyric|lyrics|audio|video|visualizer|music video|mv|hd|4k|remaster(ed)?( \d{4})?|live|acoustic|explicit|clean)[^)]*\)/gi,
      '',
    )
    .replace(
      /\[[^\]]*(official|lyric|audio|video|remaster|visualizer|live|mv)[^\]]*\]/gi,
      '',
    )
    .replace(/[\(\[]\s*(4k|hd|hq|8d( audio)?|1080p|720p)\s*[\)\]]/gi, '')
    .replace(
      /\s*-\s*(official|lyric|lyrics|audio|video|visualizer|topic).*/gi,
      '',
    )
    .replace(/\s+/g, ' ')
    .trim();
}
function primaryArtist(a) {
  return String(a || '')
    .split(/\s*[,&•·]\s*|\s+(?:feat\.?|ft\.?|with|x|vs\.?)\s+/i)[0]
    .replace(/\s*-\s*topic$/i, '')
    .trim();
}
function norm(x) {
  return String(x || '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]+/g, ' ')
    .trim();
}
function simScore(a, b) {
  a = norm(a);
  b = norm(b);
  if (!a || !b) return 0;
  if (a === b) return 1;
  if (a.includes(b) || b.includes(a)) return 0.85;
  const aw = new Set(a.split(' ')),
    bw = new Set(b.split(' '));
  let hit = 0;
  for (const w of aw) if (bw.has(w)) hit++;
  return hit / Math.max(aw.size, bw.size);
}
async function fetchTimeout(url, opts = {}, ms = 4500) {
  const ac = new AbortController();
  const t = setTimeout(() => ac.abort(), ms);
  try {
    return await fetch(url, { ...opts, signal: ac.signal });
  } finally {
    clearTimeout(t);
  }
}

async function lyricsOvh(title, artist) {
  if (!title || !artist) return null;
  try {
    const r = await fetchTimeout(
      `https://api.lyrics.ovh/v1/${encodeURIComponent(artist)}/${encodeURIComponent(title)}`,
    );
    if (!r.ok) return null;
    const j = await r.json();
    const lyr = String(j.lyrics || '')
      .replace(/\r\n/g, '\n')
      .trim();
    return lyr.length > 24 ? lyr : null;
  } catch {
    return null;
  }
}

async function neteaseLyrics(title, artist) {
  try {
    const q = `${title} ${artist}`.trim();
    if (!q) return null;
    const r = await fetchTimeout(
      `https://music.163.com/api/search/get/web?s=${encodeURIComponent(q)}&type=1&limit=8`,
      {
        headers: {
          'User-Agent': 'Mozilla/5.0',
          Referer: 'https://music.163.com/',
        },
      },
    );
    if (!r.ok) return null;
    const j = await r.json();
    const songs = (j.result || {}).songs || [];
    let best = null,
      bestScore = 0;
    for (const song of songs) {
      const an = (song.artists || []).map((a) => a.name).join(' ');
      const score = simScore(song.name, title) * 2 + simScore(an, artist);
      if (score > bestScore) {
        bestScore = score;
        best = song;
      }
    }
    if (!best || bestScore < 1.4) return null;
    const lr = await fetchTimeout(
      `https://music.163.com/api/song/lyric?id=${best.id}&lv=1&kv=1&tv=-1`,
      {
        headers: {
          'User-Agent': 'Mozilla/5.0',
          Referer: 'https://music.163.com/',
        },
      },
    );
    if (!lr.ok) return null;
    const L = await lr.json();
    const synced = (L.lrc && L.lrc.lyric) || '';
    const hasTime = /\[[0-9]+:[0-9]/.test(synced);
    if (hasTime && synced.length > 40) {
      const plain = synced
        .replace(/\[[^\]]+\]/g, '')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
      return { synced, plain: plain || null };
    }
    const plain = synced.replace(/\[[^\]]+\]/g, '').trim();
    if (plain.length > 24) return { synced: null, plain };
    return null;
  } catch {
    return null;
  }
}

async function lrclibGet(title, artist, duration) {
  try {
    const u = `https://lrclib.net/api/get?track_name=${encodeURIComponent(title)}&artist_name=${encodeURIComponent(artist)}${duration ? `&duration=${Math.round(duration)}` : ''}`;
    const r = await fetchTimeout(
      u,
      { headers: { 'User-Agent': 'DnialifyMusic/1.0' } },
      4000,
    );
    if (!r.ok) return null;
    const j = await r.json();
    if (j.instrumental) return null;
    return j.syncedLyrics || j.plainLyrics ? j : null;
  } catch {
    return null;
  }
}
async function lrclibSearch(params) {
  try {
    const qs = new URLSearchParams(params).toString();
    const r = await fetchTimeout(
      `https://lrclib.net/api/search?${qs}`,
      { headers: { 'User-Agent': 'DnialifyMusic/1.0' } },
      4000,
    );
    if (!r.ok) return [];
    return await r.json();
  } catch {
    return [];
  }
}
function pickBest(cands, title, artist, duration) {
  const dur = Number(duration) || 0;
  let best = null,
    bestScore = 0;
  for (const c of cands) {
    if (!c || c.instrumental || (!c.syncedLyrics && !c.plainLyrics)) continue;
    const tScore = simScore(c.trackName || c.name, title);
    let score = tScore * 2 + simScore(c.artistName, artist);
    if (dur && c.duration) {
      const diff = Math.abs(c.duration - dur);
      if (diff <= 2) score += 1.2;
      else if (diff <= 5) score += 0.6;
      else if (diff > 20) score -= 1;
    }
    if (c.syncedLyrics) score += 0.8;
    if (score > bestScore) {
      bestScore = score;
      best = c;
    }
  }
  if (!best) return null;
  if (bestScore >= 1.4) return best;
  if (simScore(best.trackName || best.name, title) >= 0.85 && bestScore >= 0.95)
    return best;
  return null;
}

async function textylLyrics(title, artist) {
  const q = `${artist || ''} ${title || ''}`.trim();
  if (!q) return null;
  try {
    const r = await fetchTimeout(
      `https://api.textyl.co/api/lyrics?q=${encodeURIComponent(q)}`,
    );
    if (!r.ok) return null;
    const arr = await r.json();
    if (!Array.isArray(arr) || arr.length < 4) return null;
    const synced = arr
      .map((x) => {
        const sec = Number(x.seconds) || 0;
        const m = Math.floor(sec / 60);
        const s = (sec % 60).toFixed(2).padStart(5, '0');
        return `[${m}:${s}]${x.lyrics || ''}`;
      })
      .join('\n');
    return synced.length > 40 ? synced : null;
  } catch {
    return null;
  }
}

function extractYtmLyrics(d) {
  for (const shelf of findAll(d, 'musicDescriptionShelfRenderer')) {
    const lyr = text(shelf.description);
    if (lyr && lyr.length > 20) return lyr;
  }
  for (const block of findAll(d, 'formattedDescription')) {
    const lyr = text(block);
    if (lyr && lyr.length > 40 && lyr.split('\n').length > 4) return lyr;
  }
  return null;
}

app.get('/api/lyrics', async (req, res) => {
  const { title = '', artist = '', duration = 0, browseId = '' } = req.query;
  try {
    const ct = cleanTitle(title);
    const pa = primaryArtist(artist);
    let tUse = ct || title;
    let aUse = pa || artist;
    const dash = String(tUse).match(/^(.{2,48}?)\s*[-–—]\s+(.+)$/);
    if (dash && (!aUse || simScore(dash[1], aUse) >= 0.45)) {
      aUse = aUse || dash[1];
      tUse = dash[2];
    }

    let synced = null,
      plain = null,
      source = null;

    // 1) YouTube Music lyrics for this exact video (plain, but correct)
    if (browseId) {
      try {
        const body = {
          context: { client: { ...CONTEXT.client, hl: 'id', gl: 'ID' } },
          browseId,
        };
        const r = await fetchTimeout(
          `${YTM}/browse?prettyPrint=false`,
          {
            method: 'POST',
            headers: HEADERS,
            body: JSON.stringify(body),
          },
          4500,
        );
        if (r.ok) {
          const d = await r.json();
          const lyr = extractYtmLyrics(d);
          if (lyr) {
            plain = lyr;
            source = 'YouTube Music';
          }
        }
      } catch {}
    }

    // 2) LRCLIB exact (synced preferred) — parallel
    const exactHits = await Promise.all([
      lrclibGet(tUse, aUse, duration),
      lrclibGet(tUse, aUse, 0),
      title && title !== tUse ? lrclibGet(cleanTitle(title), pa, 0) : null,
    ]);
    for (const hit of exactHits) {
      if (!hit) continue;
      synced = synced || hit.syncedLyrics || null;
      plain = plain || hit.plainLyrics || null;
      source = synced ? 'LRCLIB' : source || 'LRCLIB';
      if (synced) break;
    }

    // 3) Fuzzy LRCLIB + other catalogs in parallel when still no synced
    if (!synced) {
      const [s1, s2, s3, ne, ovh, tx] = await Promise.all([
        lrclibSearch({ track_name: tUse, artist_name: aUse }),
        lrclibSearch({ q: `${tUse} ${aUse}`.trim() }),
        lrclibSearch({ track_name: tUse }),
        neteaseLyrics(tUse, aUse),
        plain ? null : lyricsOvh(tUse, aUse),
        textylLyrics(tUse, aUse),
      ]);
      const best = pickBest(
        [].concat(s1 || [], s2 || [], s3 || []),
        tUse,
        aUse,
        duration,
      );
      if (best) {
        synced = best.syncedLyrics || synced;
        plain = plain || best.plainLyrics;
        source = best.syncedLyrics ? 'LRCLIB' : source || 'LRCLIB';
      }
      if (!synced && ne && ne.synced) {
        synced = ne.synced;
        plain = plain || ne.plain;
        source = 'NetEase';
      } else if (!plain && ne && ne.plain) {
        plain = ne.plain;
        source = source || 'NetEase';
      }
      if (!synced && tx) {
        synced = tx;
        source = 'Textyl';
      }
      if (!synced && !plain && ovh) {
        plain = ovh;
        source = 'lyrics.ovh';
      }
    }

    res.json({ synced: synced || null, plain: plain || null, source });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

/* album-art proxy so the PiP canvas is not CORS-tainted */
app.get('/api/thumb', async (req, res) => {
  try {
    const raw = String(req.query.url || '');
    const u = new URL(raw);
    const host = u.hostname;
    const ok =
      host.endsWith('ytimg.com') ||
      host.endsWith('ggpht.com') ||
      host.endsWith('googleusercontent.com');
    if (!ok) return res.status(400).end();
    const r = await fetch(raw, {
      headers: {
        'User-Agent': 'Mozilla/5.0 DnialifyThumb/1.0',
        Accept: 'image/*',
      },
    });
    if (!r.ok) return res.status(502).end();
    res.setHeader(
      'Content-Type',
      r.headers.get('content-type') || 'image/jpeg',
    );
    res.setHeader('Cache-Control', 'public, max-age=86400');
    res.send(Buffer.from(await r.arrayBuffer()));
  } catch {
    res.status(500).end();
  }
});

// ---- player client selection (env configurable; default keeps legacy behavior) ----
const PLAYER_CLIENT = String(process.env.YOUTUBE_PLAYER_CLIENT || 'current').toLowerCase();
const VISIONOS_GAPIS = 'https://youtubei.googleapis.com/youtubei/v1/';
const VISIONOS_CONTEXT = {
  client: {
    clientName: 'VISIONOS',
    clientVersion: '1.02',
    deviceModel: 'RealityDevice14,1',
    osName: 'visionOS',
    osVersion: '25.6.0.23O471',
    hl: 'en',
    gl: 'US',
  },
};
const VISIONOS_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent':
    'com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)',
  'X-Goog-Api-Format-Version': '2',
};
// cached fresh visitorData (memory/session only; NOT fetched per song)
let cachedVisitorData = null;
let visitorFetchedAt = 0;
async function getVisionOsVisitorData(force = false) {
  // reuse cached visitorData for a while; force refresh only when LOGIN_REQUIRED retry
  if (!force && cachedVisitorData && Date.now() - visitorFetchedAt < 60 * 60 * 1000) {
    return cachedVisitorData;
  }
  const res = await fetch(`${VISIONOS_GAPIS}visitor_id?prettyPrint=false`, {
    method: 'POST',
    headers: VISIONOS_HEADERS,
    body: JSON.stringify({ context: VISIONOS_CONTEXT }),
  });
  if (!res.ok) throw new Error(`visionos visitor_id HTTP ${res.status}`);
  const data = await res.json();
  const vd = data && data.responseContext && data.responseContext.visitorData;
  if (!vd) throw new Error('visionos could not get visitorData');
  cachedVisitorData = vd;
  visitorFetchedAt = Date.now();
  return vd;
}
async function fetchVisionOsPlayer(videoId, visitorData) {
  const context = JSON.parse(JSON.stringify(VISIONOS_CONTEXT));
  context.client.visitorData = visitorData;
  const res = await fetch(`${VISIONOS_GAPIS}player?prettyPrint=false`, {
    method: 'POST',
    headers: VISIONOS_HEADERS,
    body: JSON.stringify({ context, videoId, racyCheckOk: true, contentCheckOk: true }),
  });
  if (!res.ok) return { http: res.status };
  return { data: await res.json() };
}

// ---- audio stream URL via InnerTube (ANDROID primary, WEB/IOS fallback) ----
const ANDROID_CONTEXT = {
  client: {
    clientName: 'ANDROID',
    clientVersion: '20.10.38',
    androidSdkVersion: 35,
    hl: 'en',
    gl: 'US',
  },
};
const ANDROID_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent': 'com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)',
  'X-Goog-Api-Format-Version': '2',
};
const IOS_CONTEXT = {
  client: {
    clientName: 'IOS',
    clientVersion: '19.29.1',
    deviceMake: 'Apple',
    deviceModel: 'iPhone15,2',
    hl: 'en',
    gl: 'US',
    osName: 'iPhone',
    osVersion: '17_4 like Mac OS X',
  },
};
const IOS_HEADERS = {
  'Content-Type': 'application/json',
  'User-Agent': 'com.google.ios.youtube/19.29.1 (iPhone15,2; U; CPU iPhone OS 17_4 like Mac OS X)',
  'X-Goog-Api-Format-Version': '2',
};

async function getAudioUrl(videoId) {
  // VisionOS client (env YOUTUBE_PLAYER_CLIENT=visionos): fresh visitorData + one retry on LOGIN_REQUIRED
  if (PLAYER_CLIENT === 'visionos') {
    const visionAttempts = [false, true];
    for (const forceVisitor of visionAttempts) {
      let visitorData;
      try {
        visitorData = await getVisionOsVisitorData(forceVisitor);
      } catch (e) {
        console.warn('getAudioUrl visionos visitorData', e.message);
        break;
      }
      const { http, data } = await fetchVisionOsPlayer(videoId, visitorData);
      if (http) {
        console.warn(`getAudioUrl visionos -> HTTP ${http}`);
        if (forceVisitor) break;
        continue;
      }
      const sd = data && data.streamingData;
      const ps = data && data.playabilityStatus;
      if (!sd) {
        console.warn(`getAudioUrl visionos no streamingData playability=${ps?.status} reason=${ps?.reason}`);
        if ((ps && ps.status === 'LOGIN_REQUIRED') && !forceVisitor) {
          console.warn('getAudioUrl visionos LOGIN_REQUIRED -> refreshing visitorData');
          continue; // retry once with forced fresh visitorData
        }
        break;
      }
      const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
      const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
      if (!audios.length) break;
      audios.sort((a, b) => {
        const aOpus = a.mimeType.includes('opus') ? 1 : 0;
        const bOpus = b.mimeType.includes('opus') ? 1 : 0;
        if (aOpus !== bOpus) return bOpus - aOpus;
        return (b.bitrate || 0) - (a.bitrate || 0);
      });
      const best = audios[0];
      if (best.url) {
        return {
          url: best.url,
          client: 'visionos',
          mimeType: best.mimeType,
          bitrate: best.bitrate,
          itag: best.itag,
          approxDurationMs: best.approxDurationMs || data.videoDetails?.lengthSeconds * 1000 || null,
        };
      }
      console.warn(`getAudioUrl visionos best has no url, cipher=${!!best.signatureCipher}`);
      break;
    }
    console.warn(`getAudioUrl visionos failed for ${videoId}`);
    return null;
  }

  // legacy: Vercel IP diblok untuk ANDROID/IOS di www.youtube.com — coba music.youtube.com + WEB_REMIX juga
  const tryClients = [
    { host: 'https://www.youtube.com', context: ANDROID_CONTEXT, headers: ANDROID_HEADERS },
    { host: 'https://www.youtube.com', context: IOS_CONTEXT, headers: IOS_HEADERS },
    { host: 'https://music.youtube.com', context: CONTEXT, headers: HEADERS },
    { host: 'https://www.youtube.com', context: CONTEXT, headers: HEADERS },
  ];
  for (const { host, context, headers } of tryClients) {
    try {
      const res = await fetch(`${host}/youtubei/v1/player?prettyPrint=false`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          context,
          videoId,
          racyCheckOk: true,
          contentCheckOk: true,
        }),
      });
      if (!res.ok) {
        console.warn(`getAudioUrl ${context.client.clientName} -> HTTP ${res.status}`);
        continue;
      }
      const data = await res.json();
      const sd = data.streamingData;
      if (!sd) {
        console.warn(`getAudioUrl ${context.client.clientName} no streamingData playability=${data.playabilityStatus?.status} reason=${data.playabilityStatus?.reason}`);
        continue;
      }
      const formats = [...(sd.adaptiveFormats || []), ...(sd.formats || [])];
      const audios = formats.filter((f) => f.mimeType && f.mimeType.includes('audio/'));
      if (!audios.length) {
        console.warn(`getAudioUrl ${context.client.clientName} no audio formats`);
        continue;
      }
      audios.sort((a, b) => {
        const aOpus = a.mimeType.includes('opus') ? 1 : 0;
        const bOpus = b.mimeType.includes('opus') ? 1 : 0;
        if (aOpus !== bOpus) return bOpus - aOpus;
        return (b.bitrate || 0) - (a.bitrate || 0);
      });
      const best = audios[0];
      if (best.url) {
        return {
          url: best.url,
          mimeType: best.mimeType,
          bitrate: best.bitrate,
          approxDurationMs: best.approxDurationMs || data.videoDetails?.lengthSeconds * 1000 || null,
        };
      }
      console.warn(`getAudioUrl ${context.client.clientName} best has no url, has cipher=${!!best.signatureCipher}`);
    } catch (e) {
      console.warn(`getAudioUrl ${context.client.clientName} exception`, e.message);
    }
  }
  console.warn(`getAudioUrl all clients failed for ${videoId}`);
  return null;
}

app.get('/api/audio', async (req, res) => {
  const id = String(req.query.videoId || '').trim();
  if (!/^[\w-]{6,20}$/.test(id)) return res.status(400).json({ error: 'bad videoId' });
  try {
    const cacheKey = 'audio_' + id;
    const hit = cache.get(cacheKey);
    if (hit && Date.now() - hit.t < 6 * 60 * 1000 && hit.v && hit.v.url) {
      return sendJsonWithCache(req, res, hit.v, 300);
    }
    const info = await getAudioUrl(id);
    if (!info || !info.url) {
      const debug = req.query.debug ? { debug: 'all clients failed, check Vercel logs playabilityStatus' } : {};
      return res.status(404).json({ error: 'no audio url', ...debug });
    }
    cache.set(cacheKey, { v: info, t: Date.now() });
    res.setHeader('Access-Control-Allow-Origin', '*');
    sendJsonWithCache(req, res, info, 300);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---- persistent audio cache (Phase 5): <videoId>.webm, atomic .part -> final ----
const AUDIO_CACHE_DIR = process.env.AUDIO_CACHE_DIR || path.join(__dirname, 'cache', 'audio');
const AUDIO_CHUNK = 524288;
function cacheFilePath(id) {
  return path.join(AUDIO_CACHE_DIR, `${id}.webm`);
}
function cachePartPath(id) {
  return path.join(AUDIO_CACHE_DIR, `${id}.webm.part`);
}
function ensureCacheDir() {
  fs.mkdirSync(AUDIO_CACHE_DIR, { recursive: true });
}
function readFinalCache(id) {
  // returns { bytes } if a complete final file exists, else null
  try {
    const st = fs.statSync(cacheFilePath(id));
    if (st.isFile() && st.size > 0) return { bytes: st.size };
  } catch {}
  return null;
}
function hasEbmlMagic(filePath) {
  try {
    const fd = fs.openSync(filePath, 'r');
    const buf = Buffer.alloc(4);
    const n = fs.readSync(fd, buf, 0, 4, 0);
    fs.closeSync(fd);
    return n === 4 && buf[0] === 0x1a && buf[1] === 0x45 && buf[2] === 0xdf && buf[3] === 0xa3;
  } catch {
    return false;
  }
}
async function downloadToCache(id, url, total, ua) {
  ensureCacheDir();
  const part = cachePartPath(id);
  const final = cacheFilePath(id);
  try {
    fs.unlinkSync(part);
  } catch {}
  const ws = fs.createWriteStream(part);
  let received = 0;
  try {
    while (received < total) {
      const end = Math.min(received + AUDIO_CHUNK - 1, total - 1);
      const ctl = new AbortController();
      const timer = setTimeout(() => ctl.abort(), 30000);
      let r;
      try {
        r = await fetch(url, {
          headers: { Range: `bytes=${received}-${end}`, 'User-Agent': ua },
          signal: ctl.signal,
        });
      } finally {
        clearTimeout(timer);
      }
      if (r.status !== 206) {
        const e = new Error(`cache chunk HTTP ${r.status} at ${received}`);
        e.code = 'CACHE_CHUNK_' + r.status;
        throw e;
      }
      const buf = Buffer.from(await r.arrayBuffer());
      if (!buf.length) throw new Error(`cache empty chunk at ${received}`);
      await new Promise((resolve, reject) => ws.write(buf, (e) => (e ? reject(e) : resolve())));
      received += buf.length;
    }
  } catch (e) {
    try {
      ws.destroy();
    } catch {}
    try {
      fs.unlinkSync(part);
    } catch {}
    throw e;
  }
  await new Promise((resolve, reject) => ws.end((e) => (e ? reject(e) : resolve())));
  // validate before atomic finalize: exact size + EBML/WebM magic, else never a valid cache
  let ok = false;
  try {
    ok = fs.statSync(part).size === total && hasEbmlMagic(part);
  } catch {}
  if (!ok) {
    try {
      fs.unlinkSync(part);
    } catch {}
    throw new Error('cache validation failed (size/magic)');
  }
  try {
    fs.unlinkSync(final);
  } catch {}
  fs.renameSync(part, final); // atomic finalize
  return { bytes: total };
}
function cacheMetaPath(id) {
  return path.join(AUDIO_CACHE_DIR, `${id}.json`);
}
function readCacheMeta(id) {
  // sidecar meta only; never affects cache validity
  try {
    return JSON.parse(fs.readFileSync(cacheMetaPath(id), 'utf8'));
  } catch {
    return null;
  }
}
// Test/support endpoint: ensure <videoId>.webm is cached (download once, then serve from disk)
app.get('/api/cache-audio', async (req, res) => {
  const id = String(req.query.videoId || '').trim();
  if (!/^[\w-]{6,20}$/.test(id)) return res.status(400).json({ error: 'bad videoId' });
  try {
    const hit = readFinalCache(id);
    if (hit) {
      res.setHeader('Access-Control-Allow-Origin', '*');
      return res.json({
        videoId: id,
        cached: true,
        bytes: hit.bytes,
        meta: readCacheMeta(id),
        file: `${id}.webm`,
      });
    }
    const info = await getAudioUrl(id);
    if (!info || !info.url) return res.status(404).json({ error: 'no audio url' });
    let total = 0;
    try {
      total = Number(new URL(info.url).searchParams.get('clen')) || 0;
    } catch {}
    if (!total) return res.status(502).json({ error: 'no content length' });
    const ua =
      info.client === 'visionos' ? VISIONOS_HEADERS['User-Agent'] : ANDROID_HEADERS['User-Agent'];
    const done = await downloadToCache(id, info.url, total, ua);
    const meta = {
      itag: info.itag ?? null,
      mimeType: info.mimeType ?? null,
      bitrate: info.bitrate ?? null,
      client: info.client ?? 'current',
      bytes: done.bytes,
      cachedAt: new Date().toISOString(),
    };
    try {
      fs.writeFileSync(cacheMetaPath(id), JSON.stringify(meta));
    } catch {}
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.json({ videoId: id, cached: false, downloaded: true, bytes: done.bytes, meta, file: `${id}.webm` });
  } catch (e) {
    res.status(502).json({ error: String((e && e.message) || e) });
  }
});

// ---- local cache playback (Phase 6): serve cached .webm with Range, ZERO YouTube calls ----
function serveCacheFile(req, res, id) {
  const file = cacheFilePath(id);
  let st;
  try {
    st = fs.statSync(file);
    if (!st.isFile() || st.size <= 0) return false;
  } catch {
    return false;
  }
  const total = st.size;
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Accept-Ranges', 'bytes');
  res.setHeader('Content-Type', 'audio/webm');
  const range = req.headers.range;
  if (!range) {
    res.setHeader('Content-Length', String(total));
    fs.createReadStream(file).pipe(res);
    return true;
  }
  const m = /^bytes=(\d*)-(\d*)$/.exec(String(range).trim());
  if (!m) {
    res.status(416).end();
    return true;
  }
  let start = m[1] === '' ? total - Number(m[2] || 0) : Number(m[1]);
  let end = m[2] === '' ? total - 1 : Number(m[2]);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end >= total || start > end) {
    res.setHeader('Content-Range', `bytes */${total}`);
    res.status(416).end();
    return true;
  }
  res.status(206);
  res.setHeader('Content-Range', `bytes ${start}-${end}/${total}`);
  res.setHeader('Content-Length', String(end - start + 1));
  fs.createReadStream(file, { start, end }).pipe(res);
  return true;
}
app.get('/api/local-audio', (req, res) => {
  const id = String(req.query.videoId || '').trim();
  if (!/^[\w-]{6,20}$/.test(id)) return res.status(400).end();
  // no cache -> 404; never resolve/download YouTube here
  if (!serveCacheFile(req, res, id)) return res.status(404).end();
});

// Stream proxy for TWA background (same-origin, Range 206, no IP mismatch)
app.get('/api/stream', async (req, res) => {
  const id = String(req.query.videoId || '').trim();
  if (!/^[\w-]{6,20}$/.test(id)) return res.status(400).end();
  // Phase 7B: cache-first — cached ids are served from local disk, zero YouTube calls
  if (readFinalCache(id)) {
    console.log(`stream cache-hit ${id} range=${req.headers.range || 'full'}`);
    serveCacheFile(req, res, id);
    return;
  }
  try {
    const info = await getAudioUrl(id);
    if (!info || !info.url) return res.status(404).end();
    const headers = {};
    if (req.headers.range) headers['Range'] = req.headers.range;
    // Use client-appropriate UA for googlevideo
    headers['User-Agent'] =
      info.client === 'visionos'
        ? VISIONOS_HEADERS['User-Agent']
        : ANDROID_HEADERS['User-Agent'];
    headers['Accept'] = '*/*';
    const upstream = await fetch(info.url, { headers });
    // Forward status and headers
    res.status(upstream.status);
    const pass = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'cache-control', 'expires'];
    for (const [k, v] of upstream.headers.entries()) {
      if (pass.includes(k.toLowerCase())) res.setHeader(k, v);
    }
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Accept-Ranges', 'bytes');
    if (!res.getHeader('Content-Type')) res.setHeader('Content-Type', info.mimeType || 'audio/webm');
    // Stream body
    if (upstream.body) {
      // Node 18 fetch body is Web ReadableStream
      try {
        const nodeStream = Readable.fromWeb(upstream.body);
        nodeStream.pipe(res);
        nodeStream.on('error', () => res.end());
      } catch {
        const buf = Buffer.from(await upstream.arrayBuffer());
        res.send(buf);
      }
    } else {
      const buf = Buffer.from(await upstream.arrayBuffer());
      res.send(buf);
    }
  } catch (e) {
    res.status(502).end();
  }
});

app.get('/api/app-version', (req, res) => {
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
  res.setHeader('Surrogate-Control', 'no-store');
  res.json({
    version: APP_VERSION_SERVER,
    channel: BUILD_CHANNEL,
    assetCache: `dnialify-assets-v${APP_VERSION_SERVER}`,
    minVersion: '1.2.0',
    updatedAt: new Date().toISOString(),
  });
});
app.get('/api/health', (req, res) => {
  res.json({
    ok: true,
    app: 'dnialify-music-stream',
    version: APP_VERSION_SERVER,
    uptime: process.uptime(),
  });
});
app.get('/api/meta', (req, res) => {
  res.json({
    name: 'Dnialify Music Stream',
    by: 'Dnialify Project',
    wa: '089648528585',
    fb: 'https://www.facebook.com/danial.alfat7/',
    ig: 'https://instagram.com/dann4lfat_',
    twitter: 'https://twitter.com/dann4lfat_',
    telegram: 'https://t.me/dann4lfat',
  });
});
app.use((req, res) =>
  res.sendFile(path.join(__dirname, 'public', 'index.html')),
);

const PORT = process.env.PORT || 3000;
if (require.main === module) {
  app.listen(PORT, '0.0.0.0', () =>
    console.log(`Dnialify Music Stream running on :${PORT}`),
  );
}
module.exports = app;
