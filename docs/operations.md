# Exploitation locale et restauration — SH-29

## Périmètre et prérequis

Deux modes sont pris en charge sur une machine locale : Java/Vite sur l’hôte avec infrastructure Docker, ou tout Docker. Sous Windows, exécuter les commandes dans WSL2 avec Docker accessible. Java 21, Maven 3.9+, Node 22.12+ et Python 3.11+ sont nécessaires au mode hôte ; Docker Engine et Compose v2 aux deux modes. Prévoir au moins 8 Gio disponibles pour la stack (ClamAV seul peut consommer 4 Gio), de l’espace pour les images, et environ trois fois la taille du snapshot pendant sauvegarde/restauration. Adapter la mémoire WSL avant la recette si nécessaire.

Les ports 5432, 8080, 8081, 9000, 9002, 8090, 8091, 1025 et 8025 sont exclusivement liés à loopback ; Vite utilise 5173. Ce profil est une recette locale sur données synthétiques, pas un déploiement public HTTPS. Les bases d’images sont épinglées par digest, les modèles par empreinte et la source MinIO par commit. Les paquets système installés lors du build peuvent encore évoluer : conserver les images construites avec leurs identifiants pour reproduire exactement une livraison.

## Première installation — tout Docker

```sh
python3 scripts/init_local.py
docker compose --env-file .env -f infrastructure/compose.yml --profile app up -d --build postgres keycloak api
docker compose --env-file .env -f infrastructure/compose.yml --profile app up --wait --wait-timeout 240 postgres keycloak api
docker compose --env-file .env -f infrastructure/compose.yml --profile app run --rm seed
```

Ouvrir `http://localhost:8080`. Les comptes synthétiques alice, benoit et admin-demo utilisent le mot de passe **généré aléatoirement pour cette installation**, dans `.env`, jamais une valeur par défaut versionnée. Le compte admin Keycloak a un autre secret aléatoire. Le générateur refuse tout remplacement d’un `.env` existant. Renouveler les mots de passe depuis la console Keycloak `http://localhost:8081` si leur confidentialité est perdue ; mettre à jour les secrets de recette conservés localement. Régénérer le fichier ne change pas les mots de passe d’un realm déjà importé. Désactiver les comptes de démonstration avant tout usage hors recette et provisionner nominativement les utilisateurs.

La migration Flyway est automatique au démarrage : validation des checksums puis application des migrations manquantes. Une migration appliquée n’est jamais éditée. Le seed est une action explicite, séparée des migrations ; ne pas le relancer pour une restauration. L’API n’est considérée prête que lorsque `/actuator/health/readiness` répond UP, journal d’effacement rejoué et base disponible.

## Mode Java/Vite sur l’hôte

```sh
docker compose --env-file .env -f infrastructure/compose.yml --profile app stop api
docker compose --env-file .env -f infrastructure/compose.yml up -d --build --wait postgres keycloak s3 ledger-store document-worker embedding-worker mailpit
set -a
. ./.env
set +a
export APP_ORIGIN=http://localhost:5173
mvn -f services/api/pom.xml spring-boot:run
```

Dans un second terminal, après les migrations et seulement sur une première installation :

```sh
docker compose --env-file .env -f infrastructure/compose.yml exec -T postgres psql -U skillhunters -d skillhunters -v ON_ERROR_STOP=1 < infrastructure/demo-seed.sql
npm --prefix apps/web ci
npm --prefix apps/web run dev -- --host 127.0.0.1 --strictPort
```

Ouvrir `http://localhost:5173`. Le callback OIDC Vite est autorisé explicitement et les appels sont relayés vers Java sur 8080. Les workers continuent dans Docker. Les paramètres `.env` utilisent leurs endpoints loopback ; les variables `*_INTERNAL_ENDPOINT` ne concernent que l’API conteneurisée. Aucun jeton n’est stocké dans le navigateur.

## Secrets et journal courant

Conserver `.env` (mode 0600), `infrastructure/generated/` et les clés dans un stockage privé distinct des archives métier. Le dump Keycloak contient des données d’authentification : les sauvegardes sont privées même lorsqu’elles ne contiennent pas le fichier `.env`. Le HMAC garantit leur authenticité et leur intégrité, **pas leur chiffrement**. Utiliser un disque/coffre chiffré et un contrôle d’accès opérateur ; ne pas uploader les archives en artefacts CI ou dans le dépôt.

Nouvelles installations : le journal est dans `ledger-store`, volume `erasure-ledger-data`, avec des identifiants distincts. `ERASURE_LEDGER_ENDPOINT` sert aux opérations/hôte ; `ERASURE_LEDGER_INTERNAL_ENDPOINT` sert à l’API Docker. Conserver le journal **courant** et `ERASURE_LEDGER_KEY` indépendamment de toute génération de sauvegarde. Répliquer ce stockage selon les procédures du fournisseur ; un volume sur la même machine ne protège pas contre sa perte complète et n’est pas WORM.

Installations existantes : `python3 scripts/init_local.py --upgrade-operations` ajoute uniquement `BACKUP_SIGNING_KEY`. Aucun journal n’est déplacé et aucune clé n’est remplacée. Si `ERASURE_LEDGER_ENDPOINT` est absent, le journal reste sur le fournisseur S3 historique, dans son bucket séparé. Ne pas supprimer ce volume. Le passage à un autre fournisseur exige une copie complète et vérifiée du journal courant en maintenance ; cette migration n’est pas effectuée automatiquement.

## Sauvegarder

Installer les outils dans un environnement dédié :

```sh
python3 -m venv .venv-operations
.venv-operations/bin/pip install -r scripts/operations-requirements.txt
```

Arrêter préalablement Java s’il tourne sur l’hôte (Ctrl+C dans son terminal). Choisir un répertoire privé sur stockage chiffré, puis :

```sh
.venv-operations/bin/python scripts/operations_backup.py backup /secure/skillhunters-backups --days 7
```

L’outil arrête l’API Docker et Keycloak, refuse un Java encore connecté ou d’autres clients de base, puis sauvegarde les bases applicative et Keycloak et toutes les versions/markers du bucket de quarantaine. L’infrastructure doit rester dédiée, sans autre écrivain S3/SQL pendant la maintenance. Les empreintes et tailles des documents reçus doivent correspondre aux objets courants ; sinon aucune archive valide n’est publiée. Les références de migrations, révision source, identifiants d’images et résultats sont tracés. Le journal d’effacement et les fichiers de secrets ne sont **jamais** inclus dans ce snapshot.

L’archive `.shbackup` est signée et bornée à 1–30 jours ; un rapport `.report.json` adjacent ne contient que des compteurs et versions. Les temporaires bruts sont supprimés en fin d’opération, même en cas d’échec ordinaire. Une coupure machine peut laisser un répertoire `.snapshot-*` privé : le traiter comme une copie sensible soumise à la même échéance. Après succès ou diagnostic d’échec, redémarrer explicitement le mode choisi ; l’outil ne remet pas une application en service à votre insu.

```sh
docker compose --env-file .env -f infrastructure/compose.yml --profile app up -d --wait --wait-timeout 240 keycloak api
python3 scripts/retained_backup.py expire /secure/skillhunters-backups
```

Planifier `expire` quotidiennement avec votre ordonnanceur local. Les copies externes, temporaires abandonnés et disques de staging suivent la même échéance. Conserver `BACKUP_SIGNING_KEY` au moins jusqu’à expiration des archives associées ; ne pas la remplacer sans conserver la capacité de vérifier les archives encore valides.

## Restaurer dans une installation vide

1. Garder l’application arrêtée et isolée des utilisateurs. Choisir la révision sauvegardée ou une version compatible dont toutes les anciennes migrations sont identiques. Conserver les images qualifiées ; ne pas remplacer une image par un tag flottant.
2. Approvisionner les secrets restaurés et le realm généré depuis leur stockage privé. Ne pas lancer `init_local.py` sans option, ni le seed. Les clés de sauvegarde et du journal doivent correspondre ; les secrets DB/OIDC doivent être cohérents avec les bases restaurées.
3. Réattacher le **journal courant indépendant**, jamais celui de la date du snapshot. Sur une nouvelle machine, il doit provenir de sa réplication opérationnelle courante, ou d’un service S3 toujours actif ; configurer ses endpoints hôte/Docker et ses identifiants. L’outil ne crée ni ne restaure ce journal.
4. Démarrer uniquement PostgreSQL, le stockage de quarantaine vide et le service de journal courant. Les bases `skillhunters` et `keycloak` doivent être vides ; le bucket de quarantaine doit exister et ne contenir **aucune version ni marker**. Préprovisionner le bucket privé avec le fournisseur S3. Les outils ne suppriment pas les données existantes pour forcer une restauration.

```sh
docker compose --env-file .env -f infrastructure/compose.yml up -d --build --wait postgres s3 ledger-store
.venv-operations/bin/python scripts/operations_backup.py restore /secure/UUID.shbackup --ledger-current --report /secure/restore-staging.json
```

`--ledger-current` atteste que l’opérateur a raccordé le journal courant. L’absence de bucket bloque l’opération ; sa seule existence ne prouve pas sa fraîcheur, qui relève du stockage indépendant. Signature, expiration, contenu ZIP, empreintes, migrations et cible vide sont contrôlés avant toute écriture. Aucun chemin du ZIP n’est extrait librement. Les historiques d’objets et l’état latest/delete sont restaurés ; les nouveaux IDs de version S3 ne sont pas ceux du fournisseur précédent (aucune référence applicative ne dépend de ces IDs).

Le résultat **STAGED_AWAITING_REPLAY n’est pas une restauration terminée**. Démarrer ensuite et contrôler le rejeu :

```sh
docker compose --env-file .env -f infrastructure/compose.yml --profile app up -d --build --wait --wait-timeout 240 keycloak api
.venv-operations/bin/python scripts/operations_backup.py verify --report /secure/restore-final.json
```

Attendre la fin des purges si `ERASURE_REPLAY_NOT_COMPLETE` est renvoyé, puis réexécuter avec un nouveau chemin de rapport. Le résultat final exige readiness UP, toutes les suppressions terminées, aucune version S3 des sources effacées et les empreintes des documents restants correctes. Faire ensuite une connexion OIDC et vérifier un document autorisé. Conserver les rapports privés, sans les archives ni les secrets dans les logs.

Chaque base est restaurée dans une transaction, mais les deux bases et S3 ne constituent pas une transaction distribuée. Si une restauration échoue après une écriture, garder la cible hors service, diagnostiquer puis repartir d’une nouvelle cible vide. Ne jamais relancer en contournant le contrôle « cible vide ». L’outil ne détruit pas une cible partiellement restaurée.

## Mise à niveau et arrêt

Avant une mise à niveau : maintenance, sauvegarde vérifiée, conservation des images/clé/journal, puis reconstruction de la nouvelle version. Flyway refuse un checksum modifié. Ne pas utiliser `repair` pour faire disparaître une divergence. Une erreur de migration ou de rejeu bloque la remise en service ; revenir par restauration cohérente sur une cible vide, pas en lançant un ancien binaire sur un schéma plus récent.

```sh
docker compose --env-file .env -f infrastructure/compose.yml --profile app down
```

Ne pas ajouter `-v` à un arrêt normal. Aucun script d’exploitation ne supprime des volumes. Seul `smoke_operations.py --destroy-synthetic-volumes`, réservé à GitHub Actions et au projet synthétique dédié, détruit explicitement les deux volumes de recette pour prouver la restauration ; ne pas l’utiliser sur une installation réelle.

## Preuves de recette

La CI publie uniquement `sh29-restoration.json` et `sh29-local-mode.json` : volumes métier neufs, empreintes, versions S3, journal inchangé, effacement demandé **après** le snapshot rejoué, OIDC après restauration, démarrage Java/Vite avec import réel. Elle conserve aussi les recettes existantes PostgreSQL, OCR, SMTP et navigateur. Aucun RPO/RTO de production n’est revendiqué ; la sauvegarde impose une fenêtre d’arrêt et l’espace disponible limite la taille exploitable.
