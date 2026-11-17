#!/usr/bin/env python3
"""DESTRUCTIVE synthetic CI recipe only. Recreates exactly PostgreSQL/quarantine volumes, keeps live ledger."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request
import uuid
from operations_backup import Operations, ROOT
import smoke_oidc as oidc


def wait(check, seconds=180):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        try:
            result=check()
            if result:return result
        except Exception:
            pass
        time.sleep(3)
    raise AssertionError('OPERATIONS_CHECK_TIMEOUT')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--destroy-synthetic-volumes',action='store_true',required=True)
    parser.parse_args()
    if os.environ.get('GITHUB_ACTIONS')!='true':raise SystemExit('SYNTHETIC_CI_ONLY')
    ops=Operations(ROOT/'.env','infrastructure');os.environ.update(ops.values)
    if ops.ledger_s3 is ops.s3:raise SystemExit('SEPARATE_LIVE_LEDGER_REQUIRED')
    alice,session=oidc.login('alice')
    receipts=[]
    for text in (b'# Synthetic restoration survivor\nJava',b'# Synthetic erased after snapshot\nSQL'):
        status,receipt=oidc.upload(alice,session,oidc.A,str(uuid.uuid4()),text)
        assert status==202;receipts.append(receipt['id'])
    kept,erased=receipts
    ops.sql("INSERT INTO global_role(user_id,role) VALUES ('"+oidc.ALICE+"','COMPLIANCE') ON CONFLICT DO NOTHING",'skillhunters')
    ops.s3.put_bucket_versioning(Bucket=ops.bucket,VersioningConfiguration={'Status':'Enabled'})
    key='quarantine/'+oidc.A+'/'+kept
    original=ops.s3.get_object(Bucket=ops.bucket,Key=key)['Body'].read()
    ops.s3.put_object(Bucket=ops.bucket,Key=key,Body=b'Synthetic historical version')
    ops.s3.put_object(Bucket=ops.bucket,Key=key,Body=original)
    retired='quarantine/synthetic/retired'
    ops.s3.put_object(Bucket=ops.bucket,Key=retired,Body=b'Synthetic deleted version')
    ops.s3.delete_object(Bucket=ops.bucket,Key=retired)
    with tempfile.TemporaryDirectory(prefix='sh29-synthetic-') as directory:
        print('SH29: capturing coherent authenticated snapshot.',flush=True)
        archive=ops.backup(Path(directory),1)
        print('SH29: snapshot verified; requesting later erasure.',flush=True)
        ops.compose('up','-d','--wait','--wait-timeout','240','keycloak','api')
        alice,session=oidc.login('alice')
        assert oidc.request(alice,'/imports/'+erased+'/retention/erase','POST',{'confirmed':True},session['csrfToken'])[0]==202
        wait(lambda: ops.sql("SELECT state FROM erasure_tombstone WHERE source_id='"+erased+"'",'skillhunters')=='COMPLETE')
        ledger_before=ops.ledger_fingerprint()
        volumes=[]
        for service,destination in [('postgres','/var/lib/postgresql/data'),('s3','/data')]:
            container=ops.compose('ps','-q',service).decode().strip()
            mounts=json.loads(ops.run(['docker','inspect','--format','{{json .Mounts}}',container]))
            volume=next(m['Name'] for m in mounts if m['Destination']==destination and m['Type']=='volume')
            labels=json.loads(ops.run(['docker','volume','inspect','--format','{{json .Labels}}',volume]))
            assert labels['com.docker.compose.project']=='infrastructure'
            assert labels['com.docker.compose.volume'] in ('postgres-data','quarantine-data')
            volumes.append(volume)
        print('SH29: later erasure complete; recreating synthetic business volumes only.',flush=True)
        ops.quiesce();ops.compose('stop','postgres','s3')
        ops.compose('rm','-f','postgres','keycloak','s3')
        ops.run(['docker','volume','rm',*volumes])
        ops.compose('up','-d','--wait','--wait-timeout','120','postgres','s3','ledger-store')
        ops.s3.create_bucket(Bucket=ops.bucket)
        print('SH29: restoring into empty databases and quarantine, with live journal attached.',flush=True)
        staged=ops.restore(archive,True)
        assert staged['ledgerUnchanged'] and ops.ledger_fingerprint()==ledger_before
        # The old snapshot really contained the later-erased source, before signed replay.
        assert ops.sql("SELECT count(*) FROM document_import WHERE id='"+erased+"'",'skillhunters')=='1'
        ops.compose('up','-d','--wait','--wait-timeout','240','keycloak','api')
        result=wait(ops.verify)
        alice,_=oidc.login('alice')
        assert oidc.request(alice,'/imports/'+kept)[0]==200
        assert oidc.request(alice,'/imports/'+erased)[0] in (404,410)
        assert ops.sql("SELECT count(*) FROM document_import WHERE id='"+erased+"'",'skillhunters')=='0'
        assert len([o for o in ops.objects() if o['key']==key])==3
        assert next(o for o in ops.objects() if o['key']==retired and o['latest'])['delete']
        assert ops.ledger_fingerprint()==ledger_before
        result.update(sourceRevision=staged['sourceRevision'],migrations=staged['migrations'],imageIds=staged['imageIds'],emptyVolumes=True,laterErasureReplayed=True,versionedObjectsPreserved=True,oidc='passed')
        Path('artifacts').mkdir(exist_ok=True)
        ops.report(Path('artifacts/sh29-restoration.json'),result)
    # Same restored infrastructure, host Java + Vite, real OIDC callback through Vite.
    print('SH29: restore and replay verified; testing host Java/Vite mode.',flush=True)
    ops.compose('stop','api')
    env=dict(ops.env,APP_ORIGIN='http://localhost:5173')
    with tempfile.TemporaryDirectory(prefix='sh29-local-') as directory:
        with open(Path(directory)/'api.log','wb') as api_log,open(Path(directory)/'web.log','wb') as web_log:
            api=subprocess.Popen(['java','-jar','services/api/target/skillhunters-api-0.1.0-SNAPSHOT.jar'],cwd=ROOT,env=env,stdout=api_log,stderr=subprocess.STDOUT)
            web=subprocess.Popen(['npm','--prefix','apps/web','run','dev','--','--host','127.0.0.1','--strictPort'],cwd=ROOT,env=env,stdout=web_log,stderr=subprocess.STDOUT,start_new_session=True)
            try:
                def ready():
                    assert api.poll() is None and web.poll() is None
                    with urllib.request.urlopen('http://localhost:8080/actuator/health/readiness',timeout=3) as response:
                        return json.load(response)['status']=='UP'
                wait(ready)
                wait(lambda: urllib.request.urlopen('http://localhost:5173/',timeout=3).status==200)
                oidc.ORIGIN='http://localhost:5173'
                alice,session=oidc.login('alice')
                assert oidc.request(alice,'/imports/'+kept)[0]==200
                status,_=oidc.upload(alice,session,oidc.A,str(uuid.uuid4()),b'# Synthetic local Java and Vite')
                assert status==202
                wait(ops.verify)
                ops.report(Path('artifacts/sh29-local-mode.json'),{'operation':'local-mode','status':'VERIFIED','oidc':'passed','newImport':'passed'})
            finally:
                import signal
                api.terminate();os.killpg(web.pid,signal.SIGTERM)
                try:api.wait(timeout=30)
                except subprocess.TimeoutExpired:api.kill();api.wait()
                try:web.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(web.pid,signal.SIGKILL);web.wait()
                oidc.ORIGIN='http://localhost:8080'
    print('SH29: authenticated backup, empty-volume restore, live erasure replay, SHA-256, object versions and both runtime modes passed.')

if __name__=='__main__':main()
