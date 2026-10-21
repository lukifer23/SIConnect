// Test-only device peer. Physical phone evidence is collected separately by device-client.mjs.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes, createHash, createCipheriv } from 'node:crypto';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import WebSocket from 'ws';
import { Client, StreamableHTTPClientTransport, auth } from '@modelcontextprotocol/client';
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
  const config={name:'siconnect-protocol-test',main:resolve('src/index.ts'),compatibility_date:'2026-10-20',compatibility_flags:['nodejs_compat','global_fetch_strictly_public'],
    kv_namespaces:[{binding:'OAUTH_KV',id:'00000000000000000000000000000000'}],
    durable_objects:{bindings:[{name:'DEVICES',class_name:'DeviceBridge'}]},migrations:[{tag:'v1',new_sqlite_classes:['DeviceBridge']}],
    vars:{PUBLIC_ORIGIN:base,DEVICE_ENROLL_TOKEN:enrollment,AGENTS_JSON:JSON.stringify([{id:'test-agent',deviceId:'test-device',tokenSha256:hash(agent),expiresAt:Date.now()+3600000}])}};
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
test('OAuth SDK authorization, browser consent, single-use pairing, PKCE, refresh and tool invocation',async()=>{
  const code='test-device.pair_'+randomBytes(18).toString('base64url');
  const expiresAt=Date.now()+600000,agentId='oauth-test-agent';
  const iv=randomBytes(12), cipher=createCipheriv('aes-256-gcm',createHash('sha256').update(code).digest(),iv);
  cipher.setAAD(Buffer.from(`test-device|${agentId}|${expiresAt}`));
  const payload=Buffer.from(JSON.stringify({agentId,deviceId:'test-device',agentToken:agent}));
  const encrypted=Buffer.concat([cipher.update(payload),cipher.final(),cipher.getAuthTag()]);
  const saved=new Promise((resolve,reject)=>{
    const timer=setTimeout(()=>reject(new Error('Pairing acknowledgement missing')),3000);
    const listener=raw=>{const m=JSON.parse(raw);if(m.type==='pairing_saved' && m.hash===hash(code)){clearTimeout(timer);socket.off('message',listener);resolve()}};
    socket.on('message',listener);
  });
  socket.send(JSON.stringify({type:'pairing',invitation:{hash:hash(code),agentId,expiresAt,sealed:Buffer.concat([iv,encrypted]).toString('base64url')}}));
  await saved;
  let registration,tokens,verifier,authorizationUrl,discovery;
  const provider={redirectUrl:'http://127.0.0.1:49152/callback',clientMetadata:{client_name:'SIConnect OAuth integration',redirect_uris:['http://127.0.0.1:49152/callback'],grant_types:['authorization_code','refresh_token'],response_types:['code'],token_endpoint_auth_method:'none'},
    clientInformation:()=>registration,saveClientInformation:v=>{registration=v},tokens:()=>tokens,saveTokens:v=>{tokens=v},saveCodeVerifier:v=>{verifier=v},codeVerifier:()=>verifier,
    redirectToAuthorization:v=>{authorizationUrl=v},state:()=> 'integration-state',saveDiscoveryState:v=>{discovery=v},discoveryState:()=>discovery,
    validateResourceURL:async(server,resource)=>{if(resource) assert.equal(resource,base+'/mcp');return new URL(base+'/mcp')}};
  assert.equal(await auth(provider,{serverUrl:base+'/mcp',scope:'phone:control offline_access'}),'REDIRECT');
  assert.equal(authorizationUrl.searchParams.get('code_challenge_method'),'S256');
  const page=await fetch(authorizationUrl);assert.equal(page.status,200);
  const html=await page.text();assert.ok(html.includes('SIConnect OAuth integration'));
  const handle=/name="handle" value="([^"]+)"/.exec(html)[1];
  const cookie=page.headers.getSetCookie().map(x=>x.split(';')[0]).join('; ');
  const form=new URLSearchParams({handle,code,decision:'approve'});
  // A forged browser cannot consume the valid phone invitation.
  assert.equal((await fetch(base+'/authorize',{method:'POST',body:form,redirect:'manual'})).status,400);
  const approved=await fetch(base+'/authorize',{method:'POST',headers:{Cookie:cookie},body:form,redirect:'manual'});
  assert.equal(approved.status,302,await approved.text());
  const callback=new URL(approved.headers.get('Location'));assert.equal(callback.searchParams.get('state'),'integration-state');
  assert.equal(await auth(provider,{serverUrl:base+'/mcp',authorizationCode:callback.searchParams.get('code'),iss:callback.searchParams.get('iss')}),'AUTHORIZED');
  assert.ok(tokens.access_token);assert.ok(tokens.refresh_token);
  const c=new Client({name:'OAuth SDK integration',version:'1'});
  try {await c.connect(new StreamableHTTPClientTransport(new URL(base+'/mcp'),{authProvider:provider}));assert.equal((await c.callTool({name:'test_echo',arguments:{value:'oauth'}})).structuredContent.result.output.value,'oauth');}
  finally {await c.close()}
  assert.equal((await fetch(base+'/authorize',{method:'POST',headers:{Cookie:cookie},body:form,redirect:'manual'})).status,400);
  const old=tokens.access_token;assert.equal(await auth(provider,{serverUrl:base+'/mcp'}),'AUTHORIZED');assert.notEqual(tokens.access_token,old);
});
test('authenticated socket replacement rejects old in-flight ownership and requires hello',async()=>{
  const old=socket;
  const c=await client();
  // Temporarily suppress the test peer response, leaving one real SDK call in flight.
  old.removeAllListeners('message');
  const received=new Promise(resolve=>old.on('message',raw=>{const m=JSON.parse(raw);if(m.type==='invoke') resolve(m)}));
  const call=c.callTool({name:'test_echo',arguments:{value:'replacement-test'}});
  await received;
  const next=new WebSocket(base.replace('http:','ws:')+'/device/test-device',{headers:{Authorization:`Bearer ${device}`}});
  await new Promise((resolve,reject)=>{next.once('open',resolve);next.once('error',reject)});
  const result=await call;
  assert.equal(result.isError,true);assert.equal(result.structuredContent.error,'DEVICE_REPLACED_UNKNOWN_OUTCOME');
  assert.equal(result.structuredContent.recovery.doNotResubmitMutation,true);
  assert.equal((await (await fetch(base+'/connection',{headers:{Authorization:`Bearer ${agent}`}})).json()).online,false);
  const ack=new Promise(resolve=>next.on('message',raw=>{if(JSON.parse(raw).type==='hello_ack') resolve()}));
  next.send(JSON.stringify({type:'hello',capabilities:[{descriptor:{id:'test.echo',version:1,title:'Fixture echo',description:'Test-only peer',risk:'READ_ONLY',inputSchema:{type:'object',properties:{value:{type:'string'}},required:['value'],additionalProperties:false}},availability:'IMPLEMENTED'}]}));
  await ack;
  next.on('message',raw=>{const m=JSON.parse(raw);if(m.type==='invoke') next.send(JSON.stringify({type:'result',id:m.id,record:{requestId:m.id,state:'SUCCEEDED',result:{output:{value:m.request.arguments.value}}}}))});
  socket=next;
  assert.equal((await c.callTool({name:'test_echo',arguments:{value:'new-session'}})).structuredContent.result.output.value,'new-session');
  await c.close();
});
test('device disconnect is reported as offline rather than synthetic success',async()=>{
  socket.close();await new Promise(r=>setTimeout(r,100));
  const c=await client();
  try {const r=await c.callTool({name:'test_echo',arguments:{value:'offline'}});assert.equal(r.isError,true);assert.equal(r.structuredContent.error,'DEVICE_OFFLINE');}
  finally {await c.close()}
});
