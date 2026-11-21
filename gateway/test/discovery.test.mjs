import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readCatalog} from '../src/catalog.ts';
import {DiscoveryReads} from '../src/discovery.ts';

test('matching catalog revisions read only metadata and reflect the current discovery mode', async () => {
  for (const scopedDiscovery of [false, true]) {
    const storage = {get: async keys => {
      assert.deepEqual(keys, ['catalogRevision', 'scopedDiscovery']);
      return new Map([['catalogRevision', 'revision'], ['scopedDiscovery', scopedDiscovery]]);
    }};
    assert.deepEqual(await readCatalog(storage, 'revision'), {revision:'revision', scopedDiscovery, unchanged:true});
  }
});

test('a cache miss reads a coherent current catalog, including on cold start', async () => {
  const rows = [{descriptor:{id:'device.battery'}}];
  const full = new Map([['catalogRevision','third'], ['capabilities',rows], ['scopedDiscovery',true]]);
  const calls = [];
  const storage = {get: async keys => {
    calls.push(keys);
    if (!keys.includes('capabilities')) return new Map([['catalogRevision','second']]);
    return full;
  }};
  for (const known of ['first', null]) {
    calls.length = 0;
    assert.deepEqual(await readCatalog(storage, known), {revision:'third', scopedDiscovery:true, capabilities:rows});
    assert.equal(calls.length, known ? 2 : 1);
  }
  assert.deepEqual(await readCatalog({get: async () => new Map()}, null),
    {revision:null, scopedDiscovery:false, capabilities:[]});
});

test('overlapping scope reads share one frame at capacity and keep credentials and sessions independent', async () => {
  const reads = new DiscoveryReads();
  const frames = [];
  const send = id => frames.push(id);
  const first = reads.read('socket', 'agent', 'hash', send);
  const rotated = reads.read('socket', 'agent', 'rotated-hash', send);
  const differentAgent = reads.read('socket', 'other', 'hash', send);
  const replacement = reads.read('replacement', 'agent', 'hash', send);
  const others = Array.from({length:28}, (_, i) => reads.read('socket', 'user' + i, 'other-hash', send));
  const duplicates = Array.from({length:64}, () => reads.read('socket', 'agent', 'hash', send));
  assert.equal(frames.length, 32);
  assert.equal((await reads.read('socket', 'extra', 'extra', send)).status, 'UNCONFIRMED');
  reads.answer('replacement', frames[0], ['wrong-socket']);
  reads.answer('socket', frames[0], ['device.battery']);
  for (const result of await Promise.all([first, ...duplicates])) {
    assert.deepEqual(result, {allowed:['device.battery'], status:'OBSERVED'});
  }
  reads.disconnect('socket');
  reads.answer('socket', frames[3], ['late']);
  reads.answer('replacement', frames[3], []);
  assert.deepEqual(await replacement, {allowed:[], status:'OBSERVED'});
  assert.ok((await Promise.all([rotated, differentAgent, ...others])).every(r => r.status === 'UNCONFIRMED'));
  const fresh = reads.read('replacement', 'agent', 'hash', send);
  assert.equal(frames.length, 33);
  reads.answer('replacement', frames.at(-1), ['device.status']);
  assert.deepEqual((await fresh).allowed, ['device.status']);
});
