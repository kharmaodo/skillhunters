#!/usr/bin/env python3
"""Generate local-only secrets and a synthetic Keycloak realm. No secret is printed."""
import json
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
env = root / '.env'
generated = root / 'infrastructure/generated'
if env.exists():
    raise SystemExit('.env existe déjà : conservez les secrets existants. Voir docs/development.md.')
values = {name: secrets.token_hex(24) for name in ('DB_PASSWORD','OIDC_CLIENT_SECRET','KEYCLOAK_ADMIN_PASSWORD','DEMO_PASSWORD')}
values.update({'DB_URL':'jdbc:postgresql://localhost:5432/skillhunters','DB_USER':'skillhunters',
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
