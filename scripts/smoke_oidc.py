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

def client():
    jar = http.cookiejar.CookieJar()
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
                if tag == 'title' or 'kc-feedback-text' in dict(attrs).get('class', ''): self.capture = True
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
    revoke = {'userId':ALICE, 'poolId':A, 'roles':[]}
    assert request(admin, '/admin/memberships', 'PUT', revoke)[0] == 403
    try:
        assert request(admin, '/admin/memberships', 'PUT', revoke, admin_session['csrfToken'])[0] == 204
        assert request(alice, '/pools/'+A)[0] == 404, 'Existing session retained revoked access'
    finally:
        revoke['roles'] = ['RECRUITER']
        assert request(admin, '/admin/memberships', 'PUT', revoke, admin_session['csrfToken'])[0] == 204
    assert request(alice, '/session', 'DELETE')[0] == 403
    assert request(alice, '/session', 'DELETE', csrf=session['csrfToken'])[0] == 204
    assert request(alice, '/session')[0] == 401
    print('OIDC login, session rotation, pool isolation, CSRF, immediate revocation and logout passed.')
