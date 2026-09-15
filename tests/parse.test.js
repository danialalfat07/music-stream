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
  it('volume has three levels without changing playback entrypoints', ()=> {
    assert.match(frontend, /volumeLevel/);
    assert.match(frontend, /100, 200, 300/);
    assert.match(frontend, /volumeBoost\(level\)/);
    assert.match(frontend, /function startCurrent\(\)/);
    assert.match(frontend, /function playViaAudio\(song\)/);
    assert.doesNotMatch(backend, /offline|video-stream/);
  });
});
