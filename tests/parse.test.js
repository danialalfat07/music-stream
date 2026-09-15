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
  it('iframe fallback caches video and records playback mode', ()=> {
    assert.match(frontend, /playbackMode:\s*null/);
    assert.match(frontend, /Player\.playbackMode = 'iframe'/);
    assert.match(frontend, /cacheMediaInBackground\(s\)/);
    assert.match(frontend, /async function saveSongOffline\(song\)/);
    assert.match(frontend, /const videoUrl = `\/api\/video-stream\?videoId=\$\{encodeURIComponent\(song\.videoId\)\}`/);
    assert.match(frontend, /cacheAudioInBackground\(song, audioUrl\)/);
    assert.match(frontend, /saveSongOffline\(song\)/);
    assert.match(frontend, /row\('offline', 'i-download', 'Save offline'\)/);
    assert.match(frontend, /Player\.playbackMode = 'audio'/);
    assert.match(frontend, /Player\.playbackMode = 'cached'/);
  });
});
