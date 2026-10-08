# Skill Hunters

Application de recrutement permettant de rechercher des développeurs dans un vivier de CV, de vérifier les preuves et de préparer des contacts validés par un recruteur.

## État du projet

**Lot L0 en cours : fondations et contrats.** La maquette MVP et la stack ont été validées le 8 octobre 2026. Ce dépôt ne contient pas encore une application exécutable. Aucun service OCR, modèle IA, mécanisme d'authentification ou envoi SMTP n'est implémenté ici.

- Frontend : React, TypeScript, Vite.
- Métier et API : Java 21, Spring Boot, Spring Security.
- Documents et IA : worker Python isolé ; moteur et versions à qualifier.
- Données : PostgreSQL, pgvector et stockage privé compatible S3.
- Identité : OpenID Connect ; Keycloak pour l'installation autonome proposée.
- Recette : application locale avec infrastructure Docker, puis stack Docker Compose complète.

## Documents de référence

- [Consignes de contribution](AGENTS.md)
- [Dossier de conception et backlog](Skill-Hunter-Dossier-Conception.md)

- [Périmètre et décisions](docs/product-scope.md)
- [Architecture et frontières](docs/architecture.md)
- [Décision de stack](docs/adr/0001-stack-and-boundaries.md)
- [Contrat OpenAPI initial](docs/api/openapi.json) et [règles API](docs/api/README.md)
- [Modèle métier](docs/data/model.md) et [schéma logique](docs/data/schema.dbml)
- [Modèle CV sans données personnelles](templates/cv/README.md)
- [Cas de validation synthétiques](tests/fixtures/cv/cases.json)
- [Gates de livraison et benchmark](docs/validation/l0-gates.md)
- [Exigences de sécurité](docs/security.md)

## Vérifier les fondations

Avec Python 3.11 ou supérieur, sans bibliothèque tierce :

```sh
python3 scripts/check_foundation.py
```

Le contrôle valide la cohérence interne du contrat, les références de schéma, les invariants des fixtures et l'absence des données d'exemple du CV source dans le modèle livré. Ce n'est pas un test du futur backend ni une certification complète OpenAPI.

## Structure proposée

```text
apps/web/                  frontend React TypeScript Vite
services/api/              backend Java 21 Spring Boot
services/document-worker/  extraction et OCR isolés
infrastructure/            profils Docker et exploitation
contracts via docs/api/    contrat REST initial
schemas via docs/data/     modèle logique avant migrations
schemas et corpus tests/   données synthétiques de validation
```

Les dossiers applicatifs sont des emplacements documentés, sans squelette de framework trompeur. Versions, images et modèles seront épinglés après leurs vérifications au lot L0/L1.

## Workflow de contribution

Créer une branche `feature/…`, `fix/…` ou `docs/…` depuis `origin/develop` à jour, puis ouvrir une PR vers `develop`. La fusion reste soumise à revue. Pas de push direct sur `main` ni de fusion automatique. Voir `AGENTS.md` pour les consignes détaillées.

Les documents privés et les CV réels ne doivent pas être ajoutés à ce dépôt public. Les exemples utilisent exclusivement des données synthétiques. Aucune licence d'application n'est déduite du caractère public du dépôt : une décision de licence reste nécessaire.
