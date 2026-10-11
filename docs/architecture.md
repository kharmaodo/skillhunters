# Architecture du MVP

Le backend Java est l'autorité métier. Le worker documentaire produit des propositions, jamais une sélection ou un envoi. Les CV et les sorties de modèle sont des données non fiables.

## Frontières

| Module | Responsabilité | Interdictions |
|---|---|---|
| Identity | Session OIDC, rôles et appartenance aux viviers | Aucun token OIDC en localStorage |
| Talent | Candidats, versions, revue et permissions | Aucune mutation métier par le worker |
| Documents | Quarantaine, validation, jobs et preuves | Aucun parsing avant antivirus concluant |
| Search | Critères, index et récupération des profils | Pas de données de viviers interdits |
| Scoring | Contributions déterministes, versions et incertitudes | Ni âge ni nom ni photo comme facteur |
| Selection | Listes, notes, choix humains | Pas de rejet automatique |
| Communication | Brouillon, approbation et livraison unitaire | Pas d'envoi autonome ni retry SMTP aveugle |
| Governance | Audit, base légale, droits et effacement | Pas de conservation illimitée par défaut |

Monolithe Java organisé par domaines, ports/adaptateurs aux frontières externes. Aucun besoin de Spring Modulith. Le worker Python est un déploiement distinct pour isoler parsing, OCR et ressources des modèles ; aucune prolifération de microservices métier.

## Cycle documentaire

Réception en quarantaine → antivirus → extraction/OCR → normalisation → revue → révision validée → indexation. Rejet de format, blocage sécurité et échec reprenable sont des états distincts.

Outbox PostgreSQL transactionnelle et jobs avec bail, compteur de tentatives et clef d'idempotence. Le worker reçoit uniquement la tâche et l'accès temporaire à son objet. Un résultat tardif d'une révision remplacée ou d'un candidat supprimé ne peut pas être publié.

## Données et recherche

S3 privé pour les originaux. PostgreSQL pour métadonnées, autorisations et révisions. Recherche plein texte et pgvector dans la même base. Les embeddings utilisent seulement le texte professionnel autorisé. Les permissions s'appliquent avant récupération et sont revérifiées avant restitution.

La similarité sémantique sert au rappel ; elle n'atteste pas une compétence. Le score métier distingue correspondance documentée et qualité d'extraction. Toute contribution dispose d'une preuve et d'une version de règle. Les snapshots personnels suivent les mêmes droits et effacements que les fiches.

## Contact

DRAFT → APPROVED → SENDING → ACCEPTED, FAILED ou UNKNOWN. Toute modification du destinataire, objet, corps ou version de template invalide l'approbation. Avant livraison, recontrôler permission et opposition. ACCEPTED signifie accepté par le serveur SMTP, pas délivré ou lu. UNKNOWN nécessite une investigation avant nouvelle émission.

## Exécution cible

Deux modes à livrer au L1 : processus applicatifs locaux avec dépendances conteneurisées ; puis ensemble Docker Compose. PostgreSQL, stockage privé, OIDC, antivirus et Mailpit sont internes. Le réseau des parseurs et modèles interdit les sorties arbitraires. Pas de Kubernetes ni Redis imposé.

Un premier incrément exécutable livre identité, session et habilitations : voir `development.md`. Les opérations restantes du contrat sont marquées comme planifiées. Les sessions sont actuellement en mémoire serveur, sans promesse de haute disponibilité.

## Réception SH-03

Le module `documents` sépare contrôleur HTTP, service transactionnel, contrôle d'enveloppe borné et port `QuarantineStorage` avec adaptateur S3. Une intention durable précède l'écriture objet ; le commit final signifie uniquement réception en quarantaine. Aucun parseur documentaire ni worker n'est lancé. Voir `validation/sh-03.md` pour les contrôles différés.

## Lots SH-04

Le manifeste du lot est réservé avant les contenus. Chaque fichier possède une empreinte et un emplacement immuable, puis réutilise le service de réception individuelle. Les métadonnées de tentative ont un bail et un jeton de génération ; la réception S3/SQL durable fait autorité en cas de projection de lot interrompue. Pas de nouveau service ou broker. Les envois se font par fichier et restent synchrones, avec admission bornée ; aucun worker documentaire n'est implicitement activé.

## Doublons documentaires SH-05

Lecture des métadonnées SHA-256 et taille, sans accès S3 ni parsing. Le périmètre est toujours le vivier de la source, même si le lecteur possède plusieurs viviers. La transaction partage le verrou utilisateur avec la révocation des habilitations. Pagination de 20 correspondances, sans compteur inter-viviers. Les imports individuels et les fichiers de lots utilisent la même table de réception. La comparaison n'est pas une validation sanitaire ni un rapprochement de candidats.
