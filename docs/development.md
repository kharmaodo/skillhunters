# Développement et recette de SH 01 et SH 02

Cet incrément implémente les sessions OIDC et les habilitations par vivier. Il ne livre pas encore l'import de CV, les candidats, l'OCR, la recherche ni les contacts. La maquette reste la référence de ces écrans ultérieurs.

## Tout Docker en local

Prérequis : Docker Engine avec Compose v2 récent, ports 5432, 8080 et 8081 libres. Depuis la racine :

```sh
python3 scripts/init_local.py
docker compose --env-file .env -f infrastructure/compose.yml --profile app up -d --build postgres keycloak api
docker compose --env-file .env -f infrastructure/compose.yml --profile app up --wait --wait-timeout 240 postgres keycloak api
docker compose --env-file .env -f infrastructure/compose.yml --profile app run --rm seed
```

Ouvrir http://localhost:8080. Trois comptes synthétiques sont provisionnés : `alice` (Développement & IT), `benoit` (Ingénierie cloud) et `admin-demo` (administration sans accès implicite aux viviers). Leur mot de passe local est la valeur `DEMO_PASSWORD` du fichier `.env` généré sur votre machine. Ne pas copier ce fichier dans une conversation, une PR ou un log.

Le compte d'administration Keycloak est `admin` avec `KEYCLOAK_ADMIN_PASSWORD` depuis `.env`. L'application utilise le sujet OIDC et l'issuer exact ; elle ne transforme pas un attribut de nom, un email ou une claim de rôle en autorisation.

La recette est accessible uniquement sur loopback. Ne pas exposer ce Compose, `start-dev`, les comptes de démonstration ou les cookies HTTP à Internet. Le mode applicatif par défaut utilise un cookie Secure ; seul ce Compose local le désactive. Une production HTTPS nécessite une configuration et une validation distinctes. Les tags de dépendances sont fixés dans les manifests ; les images JRE restent sur une ligne de maintenance 21 à résoudre en digest lors de la qualification de production.

### Vérifier le parcours réel

```sh
set -a
. ./.env
set +a
python3 scripts/smoke_oidc.py
```

Ce script utilise le vrai formulaire Keycloak et le flux code d'autorisation avec PKCE. Il vérifie rotation de session, absence de tokens OIDC dans l'API, isolation A/B, retrait d'accès avec session existante, CSRF et logout. Il ne doit être lancé que sur le jeu local synthétique ; le retrait temporaire d'Alice est restauré dans un bloc `finally`.

`DELETE /api/v1/session` termine la session applicative. La session SSO du fournisseur n'est pas détruite ; la prochaine connexion demande une nouvelle authentification avec `prompt=login`. Ne pas présenter cela comme une déconnexion globale de toutes les applications.

### Arrêt et conservation

```sh
docker compose --env-file .env -f infrastructure/compose.yml --profile app down
```

Les données PostgreSQL persistent dans le volume. Ne pas ajouter `-v` pour un arrêt ordinaire. L'initialisation Keycloak importe le realm uniquement s'il n'existe pas : régénérer des secrets ne modifie pas un realm déjà importé. Conserver `.env` et `infrastructure/generated/` ensemble. `init_local.py` refuse d'écraser un `.env` existant.

Le seed ne restaure pas les droits déjà retirés ; il ne donne les rôles initiaux qu'aux utilisateurs nouvellement insérés. Après la création initiale, gérer les habilitations depuis l'écran Administration.

## Application locale avec infrastructure Docker

Prérequis supplémentaires : Java 21, Maven 3.9+, Node 22.12+ et npm.

```sh
python3 scripts/init_local.py # seulement si .env absent
docker compose --env-file .env -f infrastructure/compose.yml up -d --wait postgres keycloak
set -a
. ./.env
set +a
cd services/api
mvn spring-boot:run
```

Dans un second terminal, lorsque l'API a appliqué ses migrations :

```sh
set -a
. ./.env
set +a
docker compose --env-file .env -f infrastructure/compose.yml exec -T postgres psql -U skillhunters -d skillhunters -v ON_ERROR_STOP=1 < infrastructure/demo-seed.sql
cd apps/web
npm ci
npm run dev
```

Pour ce mode Vite, démarrer l'API avec `APP_ORIGIN=http://localhost:5173` (export après chargement de `.env`, avant `mvn spring-boot:run`). Ouvrir http://localhost:5173. Vite relaie `/api`, `/oauth2` et `/login` vers l'API, sans stocker de jeton. Les deux callbacks locaux sont explicitement autorisés dans le realm.

## Tests

```sh
python3 scripts/check_foundation.py
mvn -f services/api/pom.xml test
npm --prefix apps/web ci
npm --prefix apps/web run build
git diff --check
```

Les tests Java utilisent H2 en mode PostgreSQL par défaut pour une exécution sans Docker. En CI, le même ensemble est exécuté sur un vrai PostgreSQL avec `TEST_DB_URL`, `TEST_DB_USER` et `TEST_DB_PASSWORD`. Un second job construit le Compose et exécute le parcours OIDC réel. Ne pas assimiler les tests MockMvc à une validation de l'identité par le fournisseur : seuls les tests bout en bout couvrent cette frontière.

## Provisionnement hors jeu de démonstration

Aucun auto-enrôlement : un sujet OIDC inconnu est refusé. Un opérateur habilité crée l'utilisateur chez le fournisseur, puis une entrée `app_user` avec l'issuer exact et le `sub` renvoyé par le fournisseur. Les rôles globaux sont provisionnés séparément dans `global_role`, via une opération d'exploitation contrôlée. Le formulaire applicatif attribue seulement `RECRUITER` ou `RECRUITMENT_LEAD` sur un vivier existant. Une liste vide retire l'accès. Le provisionnement self-service et le CRUD complet d'administration ne sont pas dans cet incrément.

## Limites connues

Sessions en mémoire serveur, mono-instance : redémarrer l'API impose une reconnexion. Pas de haute disponibilité annoncée. Listes administratives limitées à 100 utilisateurs/viviers ; pagination à ajouter avant montée en charge. Audit d'identité stocké dans PostgreSQL et transactionnel avec les changements ; durcissement du rôle SQL d'audit append-only à venir avec SH-30. Le schéma physique livré ne couvre que l'identité et les viviers ; le DBML L0 décrit un périmètre plus large.
