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

## Analyse SH-07

`document_analysis` est la file transactionnelle PostgreSQL : créée à l'acceptation de la réception, elle porte un bail avec token de génération et trois tentatives maximum. L'API lit l'objet privé borné, vérifie son empreinte et appelle un worker HTTP interne authentifié. Le worker n'a aucun accès S3/SQL ; ClamAV précède tout parsing. Le parseur enfant utilise seccomp sans sockets et des limites CPU/mémoire/temps. La publication recontrôle bail, existence, échéance et habilitation du demandeur. Le texte reste un dérivé non validé, accessible uniquement dans son vivier, jamais automatiquement indexé. Voir `validation/sh-07.md` pour les limites DOC, PDF et OCR.

## OCR SH-08

Le parseur antivirus-gaté délègue les pages PDF sans texte à Poppler puis Tesseract FR/EN, dans le même sandbox sans réseau. Les preuves OCR sont enregistrées dans le résultat JSON de la tâche avec indices techniques et drapeaux de revue ; elles ne sont ni indexées ni transformées en compétences. V6 reprogramme les anciens résultats OCR_REQUIRED non expirés. Le détail des limites est dans `validation/sh-08.md`.

## Propositions SH-09

Un adaptateur déterministe Java sans I/O interprète les libellés du texte déjà extrait et contrôlé par le worker. Il ne parse aucun fichier et n'appelle aucun modèle. Le service transactionnel conserve une révision immuable et son snapshot exact, contrôle preuves, droits et expiration, puis renvoie des propositions non validées. Cette première voie bornée évite de qualifier implicitement un modèle local ; un futur adaptateur de modèle restera dans le worker isolé. Voir `validation/sh-09.md` pour la couverture et les limites.

## Revue SH-10

Le module Talent conserve les révisions humaines immuables de la source SH-09 attachée à la fiche. Il publie de façon synchrone, sous verrous acteur/fiche et If-Match, une révision et sa projection d’assertions dans une même transaction PostgreSQL. Aucun nouveau worker, modèle ni service d’indexation n’est nécessaire pour cette projection structurée bornée. La publication exclut identité, contacts et narratif brut ; elle ne remplace pas la recherche hybride à venir. Un brouillon ou une extraction ultérieure n’écrase jamais la publication/correction précédente. Voir `validation/sh-10.md`.

## Export Word SH-11

Le module `exports` consomme une révision humaine publiée et le modèle propre versionné. Un adaptateur OOXML JDK borne les parties du package, injecte exclusivement du texte et retire les métadonnées. Aucune nouvelle dépendance Office, aucun worker ni service externe en production. La génération courte est synchrone et idempotente ; le DOCX privé et sa provenance sont stockés transactionnellement en PostgreSQL, sous la rétention source. L’installation locale et l’image Docker embarquent le même fichier de modèle. Le rendu LibreOffice sert uniquement à la recette visuelle, pas au runtime.

## Périodes SH-13

Le module Talent conserve les associations humaines dans des snapshots immuables
sous le verrou/version de fiche existant. `PeriodCalculator` est une fonction pure
sans I/O : union des bornes calendaires, exposition et récence, séparation par type.
Le rapport est recalculé à la référence demandée, sans worker ni service externe.

## Recherche structurée SH-14

Le module Search expose un POST BFF protégé par CSRF. Le backend filtre vivier,
habilitations, expiration et publication avant récupération, puis réutilise les
projections et périodes de Talent. Un évaluateur pur distingue preuve, manque de
documentation et inconnus. Pagination keyset live bornée, aucun cache candidat,
aucun nouveau moteur ou worker. L'audit minimal utilise `identity_audit` existant.

## Recherche française SH-15

Un interpréteur Java pur applique une grammaire bornée et le dictionnaire versionné.
Aucun modèle ni appel externe. La proposition ne lit aucun candidat ; une seconde
requête, après relecture explicite, utilise SH-14 sous les mêmes contrôles de droits.
V13 garde uniquement la proposition et la trace confirmée pendant 30 minutes, sans
texte original ni copie de résultats. Voir `validation/sh-15.md` pour la grammaire,
les ambiguïtés et les limites de cette première version.

## Recherche hybride SH-16

Le service Java applique les permissions et filtres SH-14 avant de projeter le
vocabulaire du dictionnaire depuis les compétences et sections professionnelles
publiées. Un worker ONNX CPU encode uniquement les libellés canoniques.
PostgreSQL conserve leurs vecteurs par version du dictionnaire et du modèle,
avec plein texte `tsvector` et cosinus pgvector exact. Les références candidat
et citations sont assemblées depuis la publication courante sous verrou ; aucune
copie vectorielle du CV n'est persistée. Une proximité reste distincte des preuves.
Voir `validation/sh-16-index.md` pour le cycle de vie, les limites et le corpus.

## Score documenté SH-18

Le module `scoring` sépare le calcul pur `EvidenceScore` (liste blanche de critères structurés) et l’orchestration transactionnelle `ScoredSearchService`. L’orchestrateur parcourt la récupération structurée/hybride autorisée, refuse un corpus dépassant la borne explicite, puis fige et classe les contributions. V15 conserve un snapshot privé et court des critères/preuves/règles ; les données candidat sont dans des lignes cascadées depuis leur source. Le recalcul réutilise le snapshot et vérifie l’empreinte, sans nouveau modèle ni lecture d’une publication plus récente. Qualité et similarité restent hors du total et du départage. Voir `validation/sh-18.md`.

## Configuration du scoring SH-19

`ScoringConfigurations` contrôle le rôle RECRUITMENT_LEAD du vivier et alloue les versions sous verrou du vivier avec If-Match. Le classement résout explicitement une version autorisée, puis fige sa politique dans les métadonnées. Le rejeu dépend exclusivement de cette politique figée ; les snapshots historiques sans politique utilisent les constantes SH-18. Voir [validation SH-19](validation/sh-19.md).

## Contestation et décision SH-20

`ScoreReviews` utilise le rejeu SH-18 pour contrôler auteur, vivier, source et empreinte. Le verrou acteur sérialise les décisions avec les révocations ; If-Match protège la saisie concurrente. La comparaison crée une recherche avec les paramètres figés et exige une correction publiée. Les décisions ne modifient ni score ni rang.

## Organisation personnelle SH-21

CandidateTracking contrôle le compte, le vivier et la source via CandidateService. Le verrou acteur sérialise suivi et révocation ; If-Match évite les écrasements. GET retourne NEW/version 0 sans écrire ; recherche et scoring ne dépendent pas du suivi. Filtres SQL paramétrés, pagination 25, maximum 1 000 suivis par compte et 500 versions par fiche. La collaboration/partage est SH-23.
