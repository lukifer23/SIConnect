/** Real sparse read-only hosted MCP observations. No simulated device, UI wakes or mutations. */
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
import { readFile, open, realpath } from 'node:fs/promises';
import { resolve, dirname, relative, isAbsolute } from 'node:path';
import { setTimeout as sleep } from 'node:timers/promises';
import { execFileSync } from 'node:child_process';

const [configPath, outputPath, hoursArg='24', intervalArg='900'] = process.argv.slice(2);
if (!configPath || !outputPath) throw Error('Usage: node test/soak-client.mjs /private/config.json /private/new-soak.jsonl [hours=24] [intervalSeconds=900]');
const hours=Number(hoursArg), interval=Number(intervalArg);
if (!Number.isFinite(hours) || hours<1 || hours>72 || !Number.isFinite(interval) || interval<300 || interval>3600) throw Error('Use 1–72 hours and 300–3600 second sampling intervals');
const repository=await realpath(resolve(import.meta.dirname,'../..'));
const outside=async path=>{
  const actual=resolve(await realpath(dirname(resolve(path))),resolve(path).split('/').at(-1));
  const rel=relative(repository,actual);
  if(!rel.startsWith('..'+ '/') && !isAbsolute(rel)) throw Error('Private config and observations must be outside the public repository');
  return actual;
};
const configFile=await outside(configPath), outputFile=await outside(outputPath);
const config=JSON.parse(await readFile(configFile,'utf8'));
if(!config.url?.startsWith('https://') || typeof config.agentToken!=='string') throw Error('HTTPS gateway and existing agent credential required');
const file=await open(outputFile,'wx',0o600);
const abort=new AbortController();
process.once('SIGTERM',()=>abort.abort());process.once('SIGINT',()=>abort.abort());
const started=Date.now(), finish=started+hours*3600000;
const commit=execFileSync('git',['rev-parse','HEAD'],{cwd:repository,encoding:'utf8'}).trim();
const write=async value=>{await file.write(JSON.stringify(value)+'\n');await file.sync()};
await write({type:'started',at:new Date().toISOString(),commit,hours,intervalSeconds:interval,classification:'REAL_HOSTED_PHONE',method:'Sparse outgoing MCP client. Normal owner/development activity may affect resource counters; no controlled battery inference.'});
try {
 while(!abort.signal.aborted && Date.now()<finish) {
  const sampleStart=Date.now();
  const client=new Client({name:'SIConnect-read-only-soak',version:'0.3.0'});
  const sample={type:'sample',at:new Date().toISOString(),results:[]};
  try {
   await client.connect(new StreamableHTTPClientTransport(new URL(config.url+'/mcp'),{requestInit:{headers:{Authorization:`Bearer ${config.agentToken}`}}}));
   const catalog=await client.listTools();sample.tools=catalog.tools.length;
   for(const name of ['device_status','device_runtime','shell_identity']) {
    if(!catalog.tools.some(t=>t.name===name)) {sample.results.push({tool:name,state:'NOT_ADVERTISED'});continue}
    const start=Date.now();
    const response=await client.callTool({name,arguments:{}},undefined,{timeout:30000});
    const record=response.structuredContent;
    sample.results.push({tool:name,durationMs:Date.now()-start,requestId:record?.requestId,state:record?.state,errorCode:record?.errorCode ?? record?.error,backend:record?.result?.backend,
      ...(name==='device_runtime' && record?.state==='SUCCEEDED' ? {measurements:record.result.output} : {})});
   }
  } catch(e) {sample.error={type:e.name,code:typeof e.code==='number'?e.code:undefined};}
  finally {await client.close().catch(()=>{});}
  sample.durationMs=Date.now()-sampleStart;await write(sample);
  await sleep(Math.min(interval*1000,finish-Date.now()),undefined,{signal:abort.signal}).catch(e=>{if(e.name!=='AbortError') throw e});
 }
 await write({type:abort.signal.aborted?'stopped':'completed',at:new Date().toISOString(),elapsedMs:Date.now()-started});
} finally {await file.close()}
