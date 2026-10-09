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
