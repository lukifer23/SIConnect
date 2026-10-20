// Test-only device peer. Physical phone evidence is collected separately by device-client.mjs.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, createHash } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import WebSocket from 'ws';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { Client as LegacyClient } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport as LegacyTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const secret=()=>randomBytes(32).toString('base64url');
const agent=secret(), device=secret(), enrollment=secret();
const hash=x=>createHash('sha256').update(x).digest('hex');
const base='http://127.0.0.1:8788';
let dir, worker, socket;
let executions=0;
let output='';
before(async()=>{
  dir=await mkdtemp(join(tmpdir(),'siconnect-protocol-'));
  const config={name:'siconnect-protocol-test',main:resolve('src/index.ts'),compatibility_date:'2026-10-20',compatibility_flags:['nodejs_compat'],
    durable_objects:{bindings:[{name:'DEVICES',class_name:'DeviceBridge'}]},migrations:[{tag:'v1',new_sqlite_classes:['DeviceBridge']}],
    vars:{DEVICE_ENROLL_TOKEN:enrollment,AGENTS_JSON:JSON.stringify([{id:'test-agent',deviceId:'test-device',tokenSha256:hash(agent),expiresAt:Date.now()+3600000}])}};
  await writeFile(join(dir,'wrangler.json'),JSON.stringify(config),{mode:0o600});
  worker=spawn(resolve('node_modules/.bin/wrangler'),['dev','--config',join(dir,'wrangler.json'),'--port','8788','--ip','127.0.0.1','--local'],{env:{...process.env,WRANGLER_SEND_METRICS:'false'},stdio:['ignore','pipe','pipe']});
  worker.stdout.on('data',b=>{output+=b}); worker.stderr.on('data',b=>{output+=b});
  let online=false;
  for(let i=0;i<150;i++) { try { online=(await fetch(base+'/health')).ok } catch {} if(online) break; await new Promise(r=>setTimeout(r,100)); }
  assert.ok(online,output.replaceAll(agent,'<redacted>').replaceAll(enrollment,'<redacted>'));
  const registered=await fetch(base+'/register/test-device',{method:'POST',headers:{Authorization:`Bearer ${enrollment}`,'Content-Type':'application/json'},body:JSON.stringify({deviceToken:device})});
  assert.equal(registered.status,200);
  socket=new WebSocket(base.replace('http:','ws:')+'/device/test-device',{headers:{Authorization:`Bearer ${device}`}});
  await new Promise((resolve,reject)=>{socket.once('open',resolve);socket.once('error',reject)});
  socket.send(JSON.stringify({type:'hello',deviceId:'test-device',capabilities:[{descriptor:{id:'test.echo',version:1,title:'Fixture echo',description:'Test-only protocol peer',risk:'READ_ONLY',inputSchema:{type:'object',properties:{value:{type:'string'}},required:['value'],additionalProperties:false}},availability:'IMPLEMENTED'}]}));
  socket.on('message',raw=>{const m=JSON.parse(raw);if(m.type==='invoke'){executions++;socket.send(JSON.stringify({type:'result',id:m.id,record:{requestId:m.request.requestId,state:'SUCCEEDED',result:{output:{value:m.request.arguments.value},backend:'TEST_ONLY'}}}))}});
  await new Promise(r=>setTimeout(r,100));
});
after(async()=>{socket?.close();worker?.kill('SIGTERM');if(dir) await rm(dir,{recursive:true,force:true});});

async function client(legacy=false){
  const c=legacy?new LegacyClient({name:'SIConnect-v1-test',version:'1'}):new Client({name:'SIConnect-v2-test',version:'1'});
  const Transport=legacy?LegacyTransport:StreamableHTTPClientTransport;
  await c.connect(new Transport(new URL(base+'/mcp'),{requestInit:{headers:{Authorization:`Bearer ${agent}`}}}));
  return c;
}
test('unauthenticated MCP and device connections are refused',async()=>{
  assert.equal((await fetch(base+'/mcp',{method:'POST',body:'{}'})).status,401);
  assert.equal((await fetch(base+'/device/test-device')).status,401);
});
test('official v1 client initializes 2025-era, lists and calls structured tools',async()=>{
  const c=await client(true);
  try { assert.equal((await c.listTools()).tools[0].name,'test_echo'); const r=await c.callTool({name:'test_echo',arguments:{value:'legacy'}});assert.equal(r.structuredContent.result.output.value,'legacy'); }
  finally {await c.close()}
});
test('official v2 client discovers statelessly and handles concurrent calls',async()=>{
  const c=await client();
  try {
    assert.equal((await c.listTools()).tools.length,1);
    const before=executions;
    const values=await Promise.all([1,2,3,4].map(n=>c.callTool({name:'test_echo',arguments:{value:String(n)}})));
    assert.equal(executions-before,4);assert.equal(values[2].structuredContent.result.output.value,'3');
  } finally {await c.close()}
});
test('SDK validates arguments and unknown tools without device execution',async()=>{
  const c=await client();const before=executions;
  try {
    const bad=await c.callTool({name:'test_echo',arguments:{value:42}});assert.equal(bad.isError,true);
    await assert.rejects(c.callTool({name:'unknown',arguments:{}}),{code:-32602});
    assert.equal(executions,before);
  } finally {await c.close()}
});
test('device disconnect is reported as offline rather than synthetic success',async()=>{
  socket.close();await new Promise(r=>setTimeout(r,100));
  const c=await client();
  try {const r=await c.callTool({name:'test_echo',arguments:{value:'offline'}});assert.equal(r.isError,true);assert.equal(r.structuredContent.error,'DEVICE_OFFLINE');}
  finally {await c.close()}
});
