#!/usr/bin/env python3
"""Real local Keycloak authorization-code flow and API isolation smoke test (synthetic users)."""
import http.cookiejar
import json
import os
from html.parser import HTMLParser
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from urllib.request import build_opener, HTTPCookieProcessor, Request

ORIGIN = 'http://localhost:8080'
A = '20000000-0000-0000-0000-000000000001'
B = '20000000-0000-0000-0000-000000000002'
ALICE = '10000000-0000-0000-0000-000000000001'

class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__(); self.action = None; self.values = {}; self.inside = False
    def handle_starttag(self, tag, attrs):
        fields = dict(attrs)
        if tag == 'form' and fields.get('id') == 'kc-form-login':
            self.action = fields['action']; self.inside = True
        if self.inside and tag == 'input' and fields.get('name'):
            self.values[fields['name']] = fields.get('value', '')
    def handle_endtag(self, tag):
        if tag == 'form': self.inside = False

class LocalhostCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    def return_ok_secure(self, cookie, request):
        # Keycloak marks localhost cookies Secure, as browsers treat localhost as
        # trustworthy. urllib does not implement that browser exception.
        url = urlsplit(request.full_url)
        if url.scheme == 'http' and url.hostname == 'localhost' and url.port == 8081:
            return True
        return super().return_ok_secure(cookie, request)

def client():
    jar = http.cookiejar.CookieJar(policy=LocalhostCookiePolicy())
    return build_opener(HTTPCookieProcessor(jar)), jar

def request(browser, path, method='GET', body=None, csrf=None):
    headers = {'Accept':'application/json'}
    if body is not None: headers['Content-Type'] = 'application/json'
    if csrf: headers['X-CSRF-Token'] = csrf
    req = Request(ORIGIN + '/api/v1' + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
    try: response = browser.open(req, timeout=15)
    except HTTPError as error: response = error
    raw = response.read()
    return response.status, json.loads(raw) if raw else None

def upload(browser, session, pool, key, contents=b'# Synthetic CV\nJava developer'):
    boundary = 'skillhunters-smoke-boundary'
    basis = json.dumps({'source':'Synthetic smoke fixture','purpose':'Recette technique sur données synthétiques',
                        'basisCode':'TEST_ONLY','retentionPolicyId':'30000000-0000-0000-0000-000000000001'})
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="poolId"\r\n\r\n{pool}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="basis"\r\nContent-Type: application/json\r\n\r\n{basis}\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="synthetic.md"\r\nContent-Type: text/markdown\r\n\r\n').encode() + contents + f'\r\n--{boundary}--\r\n'.encode()
    req = Request(ORIGIN + '/api/v1/imports', data=body, headers={
        'Content-Type':f'multipart/form-data; boundary={boundary}', 'X-CSRF-Token':session['csrfToken'], 'Idempotency-Key':key})
    try: response = browser.open(req, timeout=60)
    except HTTPError as error: response = error
    return response.status, json.loads(response.read())

def login(username):
    browser, jar = client()
    parser = LoginForm()
    html = browser.open(ORIGIN + '/oauth2/authorization/skillhunters', timeout=20).read().decode()
    parser.feed(html)
    assert parser.action, 'Expected Keycloak login form'
    before = [c.value for c in jar if c.name == 'SH_SESSION']
    parser.values.update(username=username, password=os.environ['DEMO_PASSWORD'])
    try:
        response = browser.open(Request(parser.action, data=urlencode(parser.values).encode(), headers={'Content-Type':'application/x-www-form-urlencoded'}), timeout=20)
    except HTTPError as error:
        # Only render visible error text; never log URLs with authorization codes or cookies.
        class ErrorText(HTMLParser):
            def __init__(self): super().__init__(); self.capture = False; self.messages = []
            def handle_starttag(self, tag, attrs):
                if tag in ('title', 'p') or 'kc-feedback-text' in dict(attrs).get('class', ''): self.capture = True
            def handle_endtag(self, tag): self.capture = False
            def handle_data(self, data):
                if self.capture: self.messages.append(data.strip())
        details = ErrorText(); details.feed(error.read().decode())
        raise AssertionError(f'OIDC HTTP {error.code} at {urlsplit(error.url).path}: {details.messages}') from None
    assert response.geturl() == ORIGIN + '/', 'Login did not return to the application'
    after = [c.value for c in jar if c.name == 'SH_SESSION']
    assert before and after and before != after, 'Session id must rotate on login'
    status, session = request(browser, '/session')
    assert status == 200 and session['csrfToken']
    assert 'accessToken' not in session and 'idToken' not in session
    return browser, session

if __name__ == '__main__':
    anonymous, _ = client()
    assert request(anonymous, '/session')[0] == 401
    alice, session = login('alice')
    assert request(alice, '/pools')[1] == [{'id':A,'name':'Développement & IT'}]
    assert request(alice, '/pools/'+B)[0] == 404
    admin, admin_session = login('admin-demo')
    assert request(admin, '/pools')[1] == []
    assert request(admin, '/pools/'+A)[0] == 404
    import uuid
    key = str(uuid.uuid4())
    status, receipt = upload(alice, session, A, key)
    assert status == 202, f'Import failed: {status}, {receipt.get("code")}'
    assert receipt['items'][0]['state'] == 'QUARANTINED'
    assert upload(alice, session, A, key)[1]['id'] == receipt['id']
    assert upload(alice, session, A, key, b'Different contents')[0] == 409
    assert upload(alice, session, B, str(uuid.uuid4()))[0] == 404
    assert request(admin, '/imports/'+receipt['id'])[0] == 404
    import hashlib
    batch_file = b'# Synthetic batch profile'
    batch_request = {'poolId':A,'basis':{'source':'Synthetic batch smoke','purpose':'Recette technique sur données synthétiques',
                     'basisCode':'TEST_ONLY','retentionPolicyId':'30000000-0000-0000-0000-000000000001'},
                     'files':[{'fileName':'batch.md','byteSize':len(batch_file),'sha256':hashlib.sha256(batch_file).hexdigest()}]}
    # JSON manifest calls below keep their stable key across retries.
    manifest_key = str(uuid.uuid4())
    req = Request(ORIGIN+'/api/v1/import-batches', data=json.dumps(batch_request).encode(),
                  headers={'Content-Type':'application/json','X-CSRF-Token':session['csrfToken'],'Idempotency-Key':manifest_key})
    with alice.open(req,timeout=15) as response:
        assert response.status == 202; batch = json.load(response)
    with alice.open(req,timeout=15) as response: assert json.load(response)['id'] == batch['id']
    assert request(admin, '/import-batches/'+batch['id'])[0] == 404
    boundary='batch-smoke-boundary'
    content=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="batch.md"\r\nContent-Type: text/markdown\r\n\r\n').encode()+batch_file+f'\r\n--{boundary}--\r\n'.encode()
    req=Request(ORIGIN+'/api/v1/import-batches/'+batch['id']+'/items/'+batch['items'][0]['id']+'/content',data=content,method='PUT',
                headers={'Content-Type':f'multipart/form-data; boundary={boundary}','X-CSRF-Token':session['csrfToken']})
    with alice.open(req,timeout=60) as response:
        completed=json.load(response);assert completed['items'][0]['state']=='QUARANTINED'
    with alice.open(req,timeout=60) as response: assert json.load(response)['items'][0]['attempts']==1
    # Guessing the opaque object key cannot bypass the private bucket.
    try:
        build_opener().open('http://localhost:9000/skillhunters-quarantine/quarantine/'+A+'/'+receipt['id'], timeout=15)
        raise AssertionError('Quarantine object is publicly readable')
    except HTTPError as error: assert error.code == 403
    revoke = {'userId':ALICE, 'poolId':A, 'roles':[]}
    assert request(admin, '/admin/memberships', 'PUT', revoke)[0] == 403
    try:
        assert request(admin, '/admin/memberships', 'PUT', revoke, admin_session['csrfToken'])[0] == 204
        assert request(alice, '/pools/'+A)[0] == 404, 'Existing session retained revoked access'
        assert request(alice, '/imports/'+receipt['id'])[0] == 404
        assert request(alice, '/import-batches/'+batch['id'])[0] == 404
    finally:
        revoke['roles'] = ['RECRUITER']
        assert request(admin, '/admin/memberships', 'PUT', revoke, admin_session['csrfToken'])[0] == 204
    assert request(alice, '/session', 'DELETE')[0] == 403
    assert request(alice, '/session', 'DELETE', csrf=session['csrfToken'])[0] == 204
    assert request(alice, '/session')[0] == 401
    print('OIDC login, session rotation, pool isolation, CSRF, immediate revocation private S3 quarantine, import replay and logout passed.')
