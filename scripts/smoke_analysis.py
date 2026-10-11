#!/usr/bin/env python3
"""Real OIDC -> private S3 -> ClamAV -> sandbox -> PostgreSQL smoke, synthetic only."""
import time
import uuid
from smoke_oidc import login, request, upload, A

alice,session=login('alice')
admin,_=login('admin-demo')

def wait_for(id, expected):
    deadline=time.monotonic()+240
    while time.monotonic()<deadline:
        status,analysis=request(alice,'/imports/'+id+'/analysis')
        assert status==200
        if analysis['state']==expected:return analysis
        assert analysis['state'] not in ('REJECTED','FAILED','CANCELLED'), 'Unexpected terminal state: '+analysis['state']+' '+str(analysis['code'])
        time.sleep(2)
    raise AssertionError('Analysis did not complete within 240 seconds')

status,receipt=upload(alice,session,A,str(uuid.uuid4()),b'# Synthetic analysis profile\nJava : 2.5\n<script>synthetic</script>')
assert status==202
id=receipt['id'];analysis=wait_for(id,'EXTRACTED')
assert analysis['avVersion'].startswith('ClamAV ')
assert analysis['parserVersion'].startswith('sh07-1;')
assert 'Java : 2.5' in analysis['result']['blocks'][0]['text']
assert request(admin,'/imports/'+id+'/analysis')[0]==404
assert request(alice,'/imports/'+id+'/analysis/retry','POST')[0]==403
# Official harmless antivirus test marker, not malware. Generated only in memory.
eicar=bytes.fromhex('58354f2150254041505b345c505a58353428505e2937434329377d2445494341522d5354414e444152442d414e544956495255532d544553542d46494c452124482b482a')
status,receipt=upload(alice,session,A,str(uuid.uuid4()),eicar)
assert status==202
blocked=wait_for(receipt['id'],'REJECTED')
assert blocked['code']=='ANTIVIRUS_BLOCKED' and blocked['result'] is None
assert request(alice,'/imports/'+receipt['id']+'/analysis/retry','POST',csrf=session['csrfToken'])[0]==409
print('Real ClamAV clean extraction, EICAR rejection, scoped access and CSRF passed.')
