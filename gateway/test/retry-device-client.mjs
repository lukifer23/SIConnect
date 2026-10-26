/** Real hosted phone acceptance. Credentials/reports must stay outside the source tree. */
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { resolve, dirname, join, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { Client, StreamableHTTPClientTransport } from '@modelcontextprotocol/client';

const configPath=resolve(process.argv[2] ?? '');
const repo=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
assert.ok(process.argv[2] && configPath!==repo && !configPath.startsWith(repo+sep),'Supply a private external credential file');
const config=JSON.parse(await readFile(configPath,'utf8'));
const client=new Client({name:'SIConnect-retry-device-acceptance',version:'1'});
const report={startedAt:new Date().toISOString(),tools:0,calls:[],keyedRead:false,keyedMutation:false,mutationAttempted:false,cleanup:false};
const key=randomUUID();
const path='/data/local/tmp/siconnect-retry-'+randomUUID()+'.txt';
report.fixturePath=path;
let mutationRecord,ownsFile=false;
async function call(name,arguments_) {
  const start=performance.now();const response=await client.callTool({name,arguments:arguments_});
  const record=response.structuredContent;
  report.calls.push({tool:name,requestId:record?.requestId,state:record?.state,error:record?.error ?? record?.errorCode,
    backend:record?.result?.backend,roundTripMs:Math.round(performance.now()-start)});
  return {response,record};
}
function succeeded(value) {assert.equal(value.response.isError,false);assert.equal(value.record.state,'SUCCEEDED');return value.record}
try {
  await client.connect(new StreamableHTTPClientTransport(new URL(config.url.replace(/\/$/,'')+'/mcp'),{
    requestInit:{headers:{Authorization:'Bearer '+config.agentToken}}
  }));
  const tools=(await client.listTools()).tools;report.tools=tools.length;
  assert.ok(tools.find(t=>t.name==='device_battery')?.inputSchema.properties?._siconnectOperationKey,'Live gateway is missing retry identity');
  const args={_siconnectOperationKey:key};
  const first=succeeded(await call('device_battery',args));
  const second=succeeded(await call('device_battery',args));
  assert.equal(first.requestId,second.requestId);assert.deepEqual(second,first);report.keyedRead=true;
  const conflict=await call('device_status',args);
  assert.equal(conflict.response.isError,true);assert.equal(conflict.record.error,'IDEMPOTENCY_CONFLICT');
  const shell=succeeded(await call('shell_identity',{}));
  assert.equal(shell.result.backend,'ADB_SERVICE');
  const runtime=succeeded(await call('device_runtime',{checkDatabase:true}));
  report.installedVersion=runtime.result.output.appVersionCode;
  if(process.argv.includes('--allow-disposable-write')) {
    // Explicit opt-in, one unique file, no permission expansion or personal resource access.
    const mutation={command:`(set -C; printf x > ${path}) && wc -c < ${path}`,_siconnectOperationKey:key+'-write'};
    report.mutationAttempted=true;
    const attempted=await call('shell_execute',mutation);mutationRecord=attempted.record;
    report.mutationRequestId=mutationRecord?.requestId;
    succeeded(attempted);
    assert.equal(mutationRecord.result.output.exitCode,0);ownsFile=true;
    assert.equal(mutationRecord.result.output.stdout.trim(),'1');
    const repeat=succeeded(await call('shell_execute',mutation));
    assert.equal(repeat.requestId,mutationRecord.requestId);assert.deepEqual(repeat,mutationRecord);
    const observed=succeeded(await call('shell_execute',{command:`wc -c < ${path}`}));
    assert.equal(observed.result.output.exitCode,0);assert.equal(observed.result.output.stdout.trim(),'1');
    report.keyedMutation=true;
  }
} finally {
  try {
    if(ownsFile) {
      const removed=succeeded(await call('shell_execute',{command:`rm -- ${path} && test ! -e ${path}`}));
      assert.equal(removed.result.output.exitCode,0);report.cleanup=true;
    }
  } finally {
    report.cleanupRequired=report.mutationAttempted && !report.cleanup;
    await client.close();
    // Metadata only; never serialize the token or full native outputs to the report.
    await writeFile(join(dirname(configPath),'retry-device-results.json'),JSON.stringify(report,null,2)+'\n',{mode:0o600});
    console.log(JSON.stringify(report));
  }
}
