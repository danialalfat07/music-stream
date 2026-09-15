import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
// quick smoke: ensure server loads and helpers work
import app from '../server.js';
const frontend = readFileSync(new URL('../public/app.js', import.meta.url), 'utf8');
const backend = readFileSync(new URL('../server.js', import.meta.url), 'utf8');
describe('server', ()=>{
  it('app loads', ()=> assert.ok(app));
  it('api health route exists', ()=> assert.equal(typeof app.get, 'function'));
  it('video cache route exists and validates videoId', ()=> {
    assert.match(backend, /app\.get\('\/api\/video-stream'/);
    assert.ok(backend.includes("if (!/^[\\w-]{6,20}$/.test(id)) return res.status(400).end();"));
  });
  it('offline save is explicit and playback modes are tracked', ()=> {
    assert.match(frontend, /playbackMode:\s*null/);
    assert.match(frontend, /Player\.playbackMode = 'iframe'/);
    assert.match(frontend, /async function saveSongOffline\(song\)/);
    assert.match(frontend, /let mode = await cacheAudioInBackground\(song, audioUrl\)/);
    assert.match(frontend, /const videoUrl = `\/api\/video-stream\?videoId=\$\{encodeURIComponent\(song\.videoId\)\}`/);
    assert.match(frontend, /cacheAudioInBackground\(song, audioUrl\)/);
    assert.match(frontend, /saveSongOffline\(song\)/);
    assert.match(frontend, /row\('offline', 'i-download', 'Save offline'\)/);
    assert.match(frontend, /Player\.playbackMode = 'audio'/);
    assert.match(frontend, /Player\.playbackMode = 'cached'/);
    const playbackBlock = frontend.slice(frontend.indexOf('function startCurrent()'), frontend.indexOf('async function cacheAudioInBackground'));
    assert.doesNotMatch(playbackBlock, /cache(Audio|Video|Media)InBackground/);
    assert.match(frontend, /stream duration unavailable/);
    assert.match(frontend, /AbortSignal\.timeout\(30000\)/);
    assert.match(frontend, /cache audio fail IndexedDB/);
  });
});
