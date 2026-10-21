#!/usr/bin/env python3
"""Opt-in real semantic fixture checks in the normal Android process, through authenticated loopback."""
import argparse
import json
import subprocess
import sys
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--serial',required=True)
    p.add_argument('--allow-disposable-ui-fixture',action='store_true',required=True)
    args=p.parse_args()
    def adb(*words):
        return subprocess.run(['adb','-s',args.serial,*words],capture_output=True,check=True,timeout=60).stdout
    port=None;lease=None;reports=[]
    session=str(uuid.uuid4())
    session_path='no_backup/semantic-harness-'+session+'.json'
    started=time.monotonic()
    try:
        adb('shell','am','start','-W','-f','0x18000000','-n','dev.siconnect.app/.accessibility.SemanticUiFixtureActivity','--ez','semanticHarness','true','--es','semanticSessionId',session)
        deadline=time.monotonic()+5
        while True:
            try:
                config=json.loads(adb('exec-out','run-as','dev.siconnect.app','cat',session_path))
                if config.get('sessionId')!=session:raise RuntimeError('Disposable session identity mismatch')
                break
            except (subprocess.CalledProcessError,json.JSONDecodeError):
                if time.monotonic()>deadline:raise RuntimeError('Disposable debug fixture did not create its session')
                time.sleep(.1)
        port=adb('forward','tcp:0','tcp:8765').decode().strip()
        def request(path,data=None):
            payload=None if data is None else json.dumps(data).encode()
            r=urllib.request.Request('http://127.0.0.1:'+port+path,data=payload,headers={'Authorization':'Bearer '+config['token'],'Content-Type':'application/json'})
            with urllib.request.urlopen(r,timeout=15) as response:return json.load(response)
        def call(cap,args=None,request_id=None,success=True):
            body={'capabilityId':cap,'arguments':args or {}}
            if request_id:body['requestId']=request_id
            call_started=time.monotonic()
            r=request('/invoke',body)
            reports.append({'capability':cap,'state':r['state'],'requestId':r['requestId'],'error':r.get('errorCode'),'roundTripMs':round((time.monotonic()-call_started)*1000,2),'resultBytes':len(json.dumps(r).encode())})
            if success and r['state']!='SUCCEEDED':raise AssertionError(cap+': '+r['state']+' '+str(r.get('errorCode')))
            return r.get('result',{}).get('output',{}) if success else r
        deadline=time.monotonic()+20
        while True:
            try:
                status=call('ui.status')
                if status.get('connected'):break
            except (OSError,urllib.error.URLError):pass
            if time.monotonic()>deadline:raise RuntimeError('Owner-enabled service did not bind in normal app process')
            time.sleep(.2)
        # Exercise a genuinely separate app/process; the SIConnect debug Activity only provisions credentials.
        adb('shell','am','start','-W','-f','0x18000000','-n','dev.siconnect.notificationfixture/.SemanticFixtureActivity')
        target='dev.siconnect.notificationfixture'
        lease=call('ui.lease_acquire',{'targetPackage':target,'ttlMs':120000})['leaseId']
        def find(**selector):return call('ui.find',dict(packageName=target,**selector))
        def fresh_action(cap,selector,**values):
            for attempt in range(3):
                snapshot=find(**selector)
                assert len(snapshot['nodes'])==1, 'Fixture selector must identify one control'
                response=call(cap,dict(packageName=target,leaseId=lease,windowId=snapshot['windowId'],snapshotId=snapshot['snapshotId'],nodeRef=snapshot['nodes'][0]['nodeRef'],**values),success=False)
                if response['state']=='SUCCEEDED':return response['result']['output'],snapshot
                # These are explicit pre-action refusals. Never retry an unknown/accepted mutation.
                if response['state']=='FAILED' and response.get('errorCode')=='STALE_NODE_REFERENCE' and attempt<2:
                    time.sleep(.15)
                    continue
                raise AssertionError(cap+': '+response['state']+' '+str(response.get('errorCode')))
        def wait(**selector):return call('ui.wait_for',dict(packageName=target,timeoutMs=5000,**selector))
        assert wait(text='SIConnect semantic fixture')['matched']
        # Let initial focus/layout accessibility events settle before testing a stable snapshot.
        time.sleep(.3)
        observed=call('ui.inspect',{'packageName':target,'limit':200})
        assert not any('fixture-secret-value' in row.get('text','') or 'fixture-protected-descendant' in row.get('text','') for row in observed['nodes'])
        edited,field=fresh_action('ui.set_text',dict(description='Fixture entry',editable=True),text='Disposable semantic content')
        assert edited['textReadbackVerified']
        stale=call('ui.set_text',dict(packageName=target,leaseId=lease,windowId=field['windowId'],snapshotId=field['snapshotId'],nodeRef=field['nodes'][0]['nodeRef'],text='Never replace newer state'),success=False)
        assert stale.get('errorCode')=='STALE_NODE_REFERENCE'
        assert wait(text='Disposable semantic content')['matched']
        fresh_action('ui.click',dict(text='Complete fixture',clickable=True))
        assert wait(text='Fixture complete')['matched']
        fresh_action('ui.click',dict(description='Fixture checkbox',checkable=True,checked=False))
        assert wait(description='Fixture checkbox',checked=True)['matched']
        rows=find(text='Disposable fixture row ',contains=True,limit=100)['nodes']
        visible=[int(row['text'].removeprefix('Disposable fixture row ')) for row in rows if row.get('text','').removeprefix('Disposable fixture row ').isdigit()]
        assert visible, 'Disposable scroll viewport must be visible'
        fresh_action('ui.scroll',dict(description='Fixture scroll region',scrollable=True),direction='forward')
        assert wait(text='Disposable fixture row '+str(max(visible)+1))['matched']
        protected=call('ui.find',{'packageName':'com.android.systemui','text':'Not inspected'},success=False)
        assert protected.get('errorCode')=='PROTECTED_UI'
        bounded=call('ui.inspect',{'packageName':target,'maxScanNodes':1})
        assert bounded['scannedNodes']==1 and bounded['scanIncomplete']
        unique='semantic-harness-wait:'+str(time.time_ns())
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(call,'ui.wait_for',{'packageName':target,'text':'Impossible fixture state','timeoutMs':10000},unique,False)
            time.sleep(.2);request('/cancel/'+unique,{})
            pending.result(timeout=15)
        end=request('/requests/'+unique)
        assert end['state']=='CANCELLED'
    finally:
        primary_failure=sys.exc_info()[0] is not None
        cleanup_errors=[]
        if lease and port:
            try:call('ui.lease_release',{'leaseId':lease})
            except Exception as error:cleanup_errors.append('lease_release:'+type(error).__name__)
        if port:
            try:adb('forward','--remove','tcp:'+port)
            except Exception as error:cleanup_errors.append('forward_remove:'+type(error).__name__)
        try:
            adb('shell','am','start','-W','-f','0x18000000','-n','dev.siconnect.app/.accessibility.SemanticUiFixtureActivity','--ez','semanticHarnessCleanup','true','--es','semanticSessionId',session)
            cleanup_deadline=time.monotonic()+10
            while True:
                probe=subprocess.run(['adb','-s',args.serial,'exec-out','run-as','dev.siconnect.app','ls',session_path],capture_output=True,timeout=10)
                if probe.returncode!=0:
                    if b'No such file or directory' in probe.stderr:break
                    raise RuntimeError('Cannot verify disposable session removal')
                if time.monotonic()>cleanup_deadline:raise RuntimeError('Disposable session cleanup did not complete')
                time.sleep(.1)
        except Exception as error:cleanup_errors.append('session_cleanup:'+type(error).__name__)
        try:adb('shell','am','start','-W','--activity-clear-top','-n','dev.siconnect.app/.ui.MainActivity')
        except Exception as error:cleanup_errors.append('return_home:'+type(error).__name__)
        if cleanup_errors:
            # Preserve the actual test failure (for example SCREEN_LOCKED) instead of masking it.
            if primary_failure:print(json.dumps({'cleanupErrors':cleanup_errors}),file=sys.stderr)
            else:raise RuntimeError('Disposable cleanup failed: '+','.join(cleanup_errors))
    print(json.dumps({'classification':'PHYSICAL_DEVICE_AUTHENTICATED_LOOPBACK','checks':'text/click/checkbox/scroll/wait/cancel/stale/protected/password/bounds','seconds':round(time.monotonic()-started,3),'results':reports,'disposableSessionCleaned':True}))

if __name__=='__main__':main()
