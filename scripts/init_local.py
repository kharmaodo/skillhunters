#!/usr/bin/env python3
"""Generate local-only secrets and a synthetic Keycloak realm. No secret is printed."""
import argparse
import json
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
env = root / '.env'
generated = root / 'infrastructure/generated'
parser = argparse.ArgumentParser()
parser.add_argument('--upgrade-storage', action='store_true', help='Append only missing local S3 settings; preserve identity/database secrets')
args = parser.parse_args()
if args.upgrade_storage:
    if not env.exists(): raise SystemExit('Initialisez la configuration avec init_local.py sans option.')
    current = {line.split('=',1)[0] for line in env.read_text().splitlines() if '=' in line}
    additions = {'S3_ACCESS_KEY': 'skillhunters-local', 'S3_SECRET_KEY': secrets.token_hex(24),
                 'S3_ENDPOINT': 'http://localhost:9000', 'S3_CREATE_BUCKET': 'true'}
    with env.open('a') as out:
        out.write(''.join(f'{key}={value}\n' for key,value in additions.items() if key not in current))
    os.chmod(env, 0o600)
    print('Configuration S3 locale ajoutée ; secrets existants conservés.')
    raise SystemExit(0)
if env.exists():
    raise SystemExit('.env existe déjà : conservez les secrets existants. Voir docs/development.md.')
values = {name: secrets.token_hex(24) for name in ('DB_PASSWORD','OIDC_CLIENT_SECRET','KEYCLOAK_ADMIN_PASSWORD','DEMO_PASSWORD','S3_SECRET_KEY')}
values.update({'S3_ACCESS_KEY':'skillhunters-local','S3_ENDPOINT':'http://localhost:9000','S3_CREATE_BUCKET':'true','DB_URL':'jdbc:postgresql://localhost:5432/skillhunters','DB_USER':'skillhunters',
               'OIDC_ISSUER':'http://localhost:8081/realms/skillhunters','OIDC_BACKCHANNEL':'http://localhost:8081/realms/skillhunters',
               'APP_ORIGIN':'http://localhost:8080','COOKIE_SECURE':'false'})
realm = {'realm':'skillhunters','enabled':True,'registrationAllowed':False,'resetPasswordAllowed':False,
         'bruteForceProtected':True,'failureFactor':5,'waitIncrementSeconds':60,'maxFailureWaitSeconds':900,
         'sslRequired':'none','accessTokenLifespan':300,
         'clients':[{'clientId':'skillhunters-web','enabled':True,'protocol':'openid-connect','publicClient':False,
                     'secret':values['OIDC_CLIENT_SECRET'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,
                     'redirectUris':['http://localhost:8080/login/oauth2/code/skillhunters','http://localhost:5173/login/oauth2/code/skillhunters'],
                     'webOrigins':[],'attributes':{'pkce.code.challenge.method':'S256'}}],
         'users':[]}
for suffix, username, first in [('1','alice','Alice'),('2','admin-demo','Admin'),('3','benoit','Benoît')]:
    realm['users'].append({'id':'10000000-0000-0000-0000-00000000000'+suffix,'username':username,
                          'enabled':True,'firstName':first,'lastName':'Exemple',
                          'email':username+'@example.com','emailVerified':True,
                          'credentials':[{'type':'password','value':values['DEMO_PASSWORD'],'temporary':False}]})
generated.mkdir(exist_ok=True)
# Folder contains only generated development data. Set readable mode for the non-root Keycloak container.
with env.open('x') as out:
    os.chmod(env, 0o600)
    out.write(''.join(f'{key}={value}\n' for key,value in values.items()))
realm_path = generated / 'skillhunters-realm.json'
realm_path.write_text(json.dumps(realm, ensure_ascii=False, indent=2)+'\n')
os.chmod(realm_path, 0o644)
print('Configuration locale générée. Secrets dans .env ; ne commitez ni .env ni infrastructure/generated/.')
