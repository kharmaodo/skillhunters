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
parser.add_argument('--upgrade-operations', action='store_true', help='Append missing backup authentication key; preserve all existing secrets')
parser.add_argument('--upgrade-retention', action='store_true', help='Append missing erasure ledger secret without changing existing secrets')
parser.add_argument('--upgrade-storage', action='store_true', help='Append only missing local S3 settings; preserve identity/database secrets')
parser.add_argument('--upgrade-analysis', action='store_true', help='Append missing worker configuration, preserving every existing secret')
args = parser.parse_args()
if args.upgrade_operations or args.upgrade_retention:
    if not env.exists(): raise SystemExit('Initialisez la configuration avec init_local.py sans option.')
    os.chmod(env, 0o600)
    if generated.exists(): os.chmod(generated, 0o700)
    current = {line.split('=',1)[0] for line in env.read_text().splitlines() if '=' in line}
    additions = {('BACKUP_SIGNING_KEY' if args.upgrade_operations else 'ERASURE_LEDGER_KEY'): secrets.token_hex(32)}
    with env.open('a') as out:
        out.write(''.join(f'{key}={value}\n' for key,value in additions.items() if key not in current))
    os.chmod(env, 0o600)
    print('Clé ajoutée sans remplacement ; conserver les clés durablement hors des snapshots.')
    raise SystemExit(0)
if args.upgrade_analysis:
    if not env.exists(): raise SystemExit('Initialisez la configuration avec init_local.py sans option.')
    os.chmod(env, 0o600)
    if generated.exists(): os.chmod(generated, 0o700)
    current = {line.split('=',1)[0] for line in env.read_text().splitlines() if '=' in line}
    additions = {'WORKER_TOKEN': secrets.token_hex(32), 'WORKER_ENDPOINT': 'http://localhost:8090/analyze', 'ANALYSIS_ENABLED': 'true'}
    with env.open('a') as out:
        out.write(''.join(f'{key}={value}\n' for key,value in additions.items() if key not in current))
    os.chmod(env, 0o600)
    print('Configuration documentaire ajoutée ; secrets existants conservés.')
    raise SystemExit(0)
if args.upgrade_storage:
    if not env.exists(): raise SystemExit('Initialisez la configuration avec init_local.py sans option.')
    os.chmod(env, 0o600)
    if generated.exists(): os.chmod(generated, 0o700)
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
values = {name: secrets.token_hex(24) for name in ('DB_PASSWORD','OIDC_CLIENT_SECRET','KEYCLOAK_ADMIN_PASSWORD','DEMO_PASSWORD','S3_SECRET_KEY','WORKER_TOKEN','ERASURE_LEDGER_KEY','BACKUP_SIGNING_KEY','ERASURE_LEDGER_SECRET_KEY')}
values.update({'ERASURE_LEDGER_ENDPOINT':'http://localhost:9002','ERASURE_LEDGER_INTERNAL_ENDPOINT':'http://ledger-store:9000','ERASURE_LEDGER_ACCESS_KEY':'skillhunters-ledger','WORKER_ENDPOINT':'http://localhost:8090/analyze','ANALYSIS_ENABLED':'true','S3_ACCESS_KEY':'skillhunters-local','S3_ENDPOINT':'http://localhost:9000','S3_CREATE_BUCKET':'true','DB_URL':'jdbc:postgresql://localhost:5432/skillhunters','DB_USER':'skillhunters',
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
generated.mkdir(exist_ok=True, mode=0o700)
os.chmod(generated, 0o700)
# Folder contains only generated development data. Set readable mode for the non-root Keycloak container.
with env.open('x') as out:
    os.chmod(env, 0o600)
    out.write(''.join(f'{key}={value}\n' for key,value in values.items()))
realm_path = generated / 'skillhunters-realm.json'
realm_path.write_text(json.dumps(realm, ensure_ascii=False, indent=2)+'\n')
os.chmod(realm_path, 0o644)
print('Configuration locale générée. Secrets dans .env ; ne commitez ni .env ni infrastructure/generated/.')
