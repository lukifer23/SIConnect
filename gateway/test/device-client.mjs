/** Real MCP client against the real phone. Credentials come from a private external file. */
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';
const path=process.argv[2];
if(!path) throw new Error('Usage: npm run device-test -- /absolute/private/gateway-config.json');
const config=JSON.parse(await readFile(path,'utf8'));
const client=new Client({name:'SIConnect-physical-device-test',version:'0.2.0'});
const started=Date.now();
await client.connect(new StreamableHTTPClientTransport(new URL(config.url+'/mcp'),{requestInit:{headers:{Authorization:`Bearer ${config.agentToken}`}}}));
const report={endpoint:config.url,startedAt:new Date().toISOString(),tools:0,results:[]};
try {
 const tools=await client.listTools();report.tools=tools.tools.length;
 assert.ok(tools.tools.some(t=>t.name==='device_status'),'Actual device catalog is missing');
 for(const name of ['device_status','device_battery','device_memory','device_storage','device_network','device_thermal','shell_identity']) {
   const start=Date.now();const r=await client.callTool({name,arguments:{}});const record=r.structuredContent;
   report.results.push({tool:name,state:record?.state,requestId:record?.requestId,backend:record?.result?.backend,identity:record?.result?.identity,durationMs:Date.now()-start,error:record?.errorCode});
   assert.equal(record?.state,'SUCCEEDED',`${name}: ${JSON.stringify(record)}`);
   console.log(`${name}: ${record.state}, backend=${record.result.backend}, ${Date.now()-start} ms`);
 }
 if(process.argv.includes('--approval')) {
   const r=await client.callTool({name:'shell_execute',arguments:{command:'id'}});
   assert.equal(r.structuredContent?.state,'AWAITING_APPROVAL');
   report.pendingApproval=r.structuredContent.requestId;
   console.log(`OWNER APPROVAL REQUIRED on phone: ${r.structuredContent.requestId}`);
 }
 report.totalMs=Date.now()-started;
 await writeFile(path.replace(/\.json$/,'.physical-results.json'),JSON.stringify(report,null,2),{mode:0o600});
 console.log(`Real phone MCP checks passed; ${report.tools} advertised tools.`);
} finally {await client.close()}
