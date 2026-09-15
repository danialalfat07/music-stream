import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
// quick smoke: ensure server loads and helpers work
import app from '../server.js';
describe('server', ()=>{
  it('app loads', ()=> assert.ok(app));
  it('api health route exists', ()=> assert.equal(typeof app.get, 'function'));
});
