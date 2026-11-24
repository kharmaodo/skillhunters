# Modèle métier logique

`schema.dbml` propose la topologie avant migrations PostgreSQL. Aucun SQL n'est exécuté au lot L0. Les règles ci-dessous complètent les relations : un schéma relationnel seul ne suffit pas à garantir les autorisations.

| Agrégat | Propriété principale |
|---|---|
| Vivier et appartenance | Un utilisateur peut être habilité à plusieurs viviers ; rôle et permissions par contexte |
| Candidat et document | Identité séparée des versions immuables ; plusieurs CV peuvent se rapporter à une personne après validation |
| Révision et preuve | Chaque assertion est attribuée à une version précise et à un localisateur vérifiable |
| Compétence et expérience | N–N ; autoévaluation distincte de durée sourcée ; dates et précision conservées |
| Recherche et score | Snapshot des critères, de la révision et de la politique ; permissions revérifiées à lecture |
| Contact | Brouillon versionné, approbation hashée, états de livraison non ambigus |
| Gouvernance | Provenance, finalité, droits, audit et tombstone de suppression |

## Contraintes à matérialiser dans les migrations

- Clefs uniques d'appartenance, d'alias et d'idempotence dans leur portée. Les alias conservent une forme originale et une forme normalisée. « Java » n'est pas « JavaScript ».
- Toute référence evidence/revision/document/experience doit être compatible avec le même candidat et la même organisation. Les références croisées sont rejetées côté métier et, autant que possible, par clefs composites.
- Les scores, notes, documents et résultats héritent des permissions du candidat, sans créer un accès implicite par partage de liste.
- Les colonnes d'état utilisent des contraintes de domaine ; seules les transitions autorisées sont possibles. Les écritures ont un numéro de version pour verrou optimiste.
- Révision validée immuable : une correction publie une révision suivante et conserve la filiation tant que la conservation l'autorise.
- Une compétence sans dates a une durée inconnue, pas zéro. Calcul calendaire par union des mois pour les dates mensuelles exactes ; année seule = intervalle incertain. Pas d'équivalent temps plein sans donnée explicite.
- pgvector : dimension et modèle connus avant migration ; index exact initial puis décision de HNSW après benchmark. `embedding_payload` dans le DBML est un emplacement logique, pas le choix définitif d'un type SQL.
- Effacement : tombstone transactionnel avant purge des objets et dérivés ; contrôle du tombstone lors du retour worker. Conserver seulement la preuve minimisée autorisée après effacement.
- Les journalisations et snapshots suivent des durées définies ; l'historique ne justifie pas la conservation éternelle d'un CV.

Le DBML ne constitue pas encore un schéma complet de production : index de performances, politiques RLS éventuelles, chiffrement, transactions et cascades feront l'objet des migrations et tests d'intégration de L1/L2.

## Schéma physique SH-03

La migration V2 ajoute `import_policy`, `document_import` et `document_audit`. Chaque réception appartient à un vivier et un acteur ; contrainte unique acteur/vivier/clé d'idempotence, clé S3 opaque unique, empreinte, taille, origine, politique et échéance figées. Les états physiques sont pour l'instant `RECEIVING` et `QUARANTINED`. Le modèle logique du cycle complet n'est pas intégralement matérialisé. Aucun candidat ou job métier n'est créé avant antivirus.

## Schéma physique SH-04

V3 ajoute `import_batch` (portée, idempotence et politique figée), `import_batch_item` (ordre, empreinte, état, tentative/bail) et `import_batch_event` (audit minimisé). Les réceptions `document_import` sont rapprochées par leur clé interne `batch-{itemId}` et leur portée acteur/vivier. Une réception durable acceptée prime sur une projection interrompue. Le calcul de quota cumule réservations et dépôts individuels, sans compter deux fois les documents du lot.

## SH-05 : comparaison documentaire

V4 ajoute un index `document_import(pool_id, sha256, byte_size, created_at DESC, id DESC)`. Aucun hash global ni lien inter-viviers n'est exposé. La comparaison est calculée à la lecture sur les réceptions QUARANTINED non expirées ; elle ne modifie ni original, ni provenance, ni échéance. Aucun modèle candidat/version active n'est encore implémenté.

## Schéma physique SH-07

V5 ajoute `document_analysis` (une tâche par import, demandeur, état, tentatives, bail/token, échéance de reprise, versions antivirus/parseur et JSON de preuves) et `document_analysis_event` (transitions sans texte CV). Les clés étrangères suppriment les dérivés lorsque la source est effacée. La rétention complète et sa tâche de purge sont décrites sous SH-32 ci-dessous ; les résultats expirés sont déjà inaccessibles et les workers ne peuvent pas les republier. Les accès au résultat et relances humaines sont tracés dans `document_audit`.

## SH-08 : provenance OCR

Le JSON d'analyse accepte `ocr[]` : page, confiance technique nullable, revue requise, durée et versions moteur/renderer/modèles. Les blocs OCR portent `ocr:page:N`. Les anciens JSON restent lisibles sans `ocr`. V6 reprogramme exclusivement les résultats OCR_REQUIRED non expirés ; aucune nouvelle table candidat ni donnée structurée n'est créée.

## Schéma physique SH-09

V7 ajoute `extraction_revision` : identifiant, import, empreinte du snapshot d'analyse, version des règles, snapshot source et révision JSON, création. Contrainte unique `(import_id, analysis_sha256, rules_version)` ; FK vers l'import avec suppression en cascade. Le JSON contient les valeurs brutes/normalisées, statut, précision, confiance inconnue, preuves exactes, versions et auteur. Il ne remplace ni Candidate ni les révisions humaines futures. Les snapshots sont soumis aux mêmes droits et échéances que l'original.

## Schéma physique SH-06

V8 ajoute `candidate_profile` (une fiche par import, révision source immuable, version positive et champs professionnels JSON), `candidate_contact` (coordonnées séparées) et `candidate_edit` (audit minimisé unique par fiche/version). Les trois sont supprimées en cascade avec la source. Le vivier et la rétention sont hérités de `document_import`, sans duplication modifiable. Les mises à jour utilisent une comparaison atomique de version, et les droits sont revérifiés sous verrou acteur. Ce premier agrégat ne réalise pas encore CandidatePool multi-viviers, rapprochement humain de plusieurs CV, révisions validées ou assertions éditables SH-10. Politique de contacts et limites : `validation/sh-06.md`.

## Schéma physique SH-10

V9 ajoute `candidate_revision` : snapshots humains immuables DRAFT/PUBLISHED, source SH-09, parent, version unique par fiche, décisions, valeurs avant/après, auteur, date et justifications. La fiche garde sa source initiale ; toute nouvelle extraction exige une future réconciliation explicite. `candidate_assertion_index` matérialise exclusivement la dernière publication dans une transaction commune avec le numéro de version et l’audit. FK composite (fiche, révision) pour empêcher les croisements ; cascade lors de suppression de la fiche. Index B-tree sur champ/libellé et précision/type/dates de période. Les brouillons n’altèrent pas la projection active. Les droits et l’échéance viennent de l’import ; aucun accès global à cette projection. Détails et exclusions : `validation/sh-10.md`.

## Schéma physique SH-11

V10 ajoute `export_permission` (utilisateur/vivier, jamais implicite par rôle), `candidate_export` (binaire DOCX privé, révision publiée, empreintes et versions du modèle/moteur) et `candidate_export_event` (génération/préparation de téléchargement). FK composite export→fiche/révision ; cascade depuis l’import. Unicité fiche/révision/modèle/hash/moteur pour la génération idempotente. Les droits et l’expiration sont revérifiés à la lecture, non copiés comme autorisation permanente. `IdentityStore` supprime la permission lors du retrait de la dernière appartenance au vivier.

## Taxonomie SH-12

V11 ajoute `skill_dictionary` (snapshots immuables, auteur, action, justification,
version), `skill_dictionary_head` (verrou global de mutation) et
`candidate_skill_normalization` (libellé validé original, identifiant canonique,
nom canonique et version du dictionnaire par révision publiée/source_index).
Cette dernière suit la suppression en cascade de la révision et du candidat.
`candidate_assertion_index.taxonomy_json` projette la même normalisation de la
publication active. Les preuves et le libellé documentaire brut restent dans
la révision d'extraction liée ; aucune réécriture des révisions existantes.

## Périodes SH-13

V12 ajoute `candidate_period_links`, snapshots immuables par candidat/version de
fiche, liés à une publication (cascade). Chaque lien porte les indices de compétence
et mission de la source SH-09, période bornée, extrait exact et confirmation humaine.
Auteur, justification et date tracent chaque remplacement. Le calcul n'est pas une
valeur figée en base : le rapport expose la règle `calendar-union-v1`, la date de
référence, les intervalles retenus et les bornes. L'historique des liens permet de
retracer les entrées ; aucun champ personnel n'est ajouté à l'index.

## Recherche SH-14

Aucune migration ni table de résultats : lecture des publications, index existant,
normalisations et snapshots de périodes. L'événement `STRUCTURED_SEARCH` dans
`identity_audit` porte l'ID rendu au client, l'acteur et le vivier. Critères normalisés,
version du dictionnaire, date, règle et empreinte sont restitués sans conservation
des résultats personnels. Le curseur identifie la dernière publication examinée,
avec empreinte des critères ; les autorisations restent revérifiées par le serveur.

## Proposition et confirmation de recherche (SH-15)

`natural_search_proposal` (V13) lie un UUID à l'acteur et au vivier, avec suppression
cascade, interprétation JSON professionnelle, création/expiration, confirmation,
trace SH-14 confirmée et ID recherche. Le texte original et les candidats ne sont
jamais copiés. Conservation 30 minutes, purge périodique, 50 propositions maximum
par acteur. Confirmation unique sous verrou ; dictionnaire figé, droits revérifiés.
Ce stockage temporaire ne constitue pas SH-17 (recherches sauvegardées).

## Recherche hybride SH-16 (premier incrément)

Aucune migration : la projection `candidate_assertion_index` publiée demeure la
source, avec dictionnaire figé pour la requête. Les vecteurs temporaires ne portent
que sur des libellés canoniques et disparaissent après la réponse. La trace renvoie
modèle, projection/dictionnaire, stratégie, seuil et citations. Le stockage pgvector
cible décrit plus haut n'est pas encore implémenté par cet incrément.

## Index professionnel SH-16 (V14)

`skill_vector_generation` identifie le couple dictionnaire/modèle et la dimension 384. `skill_vector_entry` stocke UUID de compétence, libellé canonique, vecteur et, sur PostgreSQL, tsvector généré. Unicité par version/modèle/compétence et version/modèle/libellé. Suppression en cascade depuis la version de dictionnaire. Aucune FK candidat et aucun texte de CV : les publications et citations restent lues à la demande. Voir `../validation/sh-16-index.md`.

## Schéma physique SH-18

V15 ajoute `scored_search` (créateur, vivier, calcul/expiration et métadonnées JSON) et `scored_search_item` (rang, candidat, publication et snapshot JSON de preuves/contributions/empreinte). Unicité recherche/candidat et recherche/rang ; FK composite candidat/publication, avec cascade à la suppression. Maximum 24 heures, accès sous les droits/rétention courants ; nettoyage horaire et suppression des lignes liées aux sources expirées. L’historique ne prolonge pas la conservation d’un CV. Les règles de score et les poids initiaux sont identifiés par version et ne sont pas modifiables via ce lot.

## Schéma physique SH-19

V16 ajoute `scoring_configuration(pool_id, version, revision_json)`, clé primaire composite et cascade du vivier. La révision contient nom, poids avant/après, parts globales normalisées, validateur, date et motif. Les versions sont immuables dans les API. `scored_search.metadata_json` contient désormais une politique figée facultative pour préserver la compatibilité des snapshots SH-18.

## Contestation et décision SH-20

V17 ajoute `score_review_event`, clé `(search_id,candidate_id,version)` et FK composite vers la ligne de snapshot avec cascade. La comparaison référence aussi son nouveau snapshot en cascade. Événements immuables via API ; aucun historique ne prolonge la rétention.

## Organisation personnelle SH-21

V18 ajoute candidate_tracking (compte/candidat, version indépendante et état), candidate_tracking_label (LIST/TAG normalisés, index de filtrage) et candidate_tracking_event (avant/après, auteur/date/motif). Cascade candidat/compte et purge horaire des sources expirées ; accès refusé dès expiration. Les tables conceptuelles shortlist/candidate_note sont remplacées par ce modèle personnel.

## Comparaisons SH-22

profile_comparison référence le classement privé et une date. profile_comparison_member référence les lignes comparées avec cascades vers scored_search_item. Auteur, vivier et échéance hérités du classement, aucune copie de texte. Maximum 100 comparaisons par classement.

## Brouillons SH-24 (V20)

`contact_draft_revision` : clef (candidate_id, actor_id, version), publication_id, created_at, payload_json. Révisions immuables, FK composite candidat/publication et cascades candidat/acteur/publication. Payload : version du modèle, variables, compétences publiées sourcées, destinataire et texte proposés, champs manquants. Accès privé et permission coordonnées ; purge à expiration de la source.

## Journal de conformité (SH-30)

`governance_audit` (V21/V22) est un journal central append-only : séquence BIGINT, UUID unique, actor_id, action, object_id/pool_id facultatifs, purpose (200 caractères) et occurred_at. Aucun lien FK vers les objets supprimables ; aucune donnée de CV ou de brouillon. Index `(pool_id, sequence)` pour consultation filtrée. PostgreSQL refuse UPDATE/DELETE/TRUNCATE ; accès API réservé à COMPLIANCE, lui-même journalisé. Voir [les limites et la recette](../validation/sh-30.md).

## Dossier de traitement et opposition (SH-31)

V23 ajoute `candidate_governance` (état/version, politique et déclaration figées, provenance, justification, preuve de notice et opposition), `candidate_governance_revision` (historique privé avec auteur/date) et `contact_opposition_token` (empreinte SHA-256, échéance et consommation). Les trois tables sont rattachées au candidat avec suppression en cascade. Les oppositions et le garde du futur envoi sérialisent leurs accès sur la ligne candidat. Aucun jeton brut en base ni dans le journal central. Les détails de finalité, rétention et limites figurent dans [SH-31](../validation/sh-31.md).

## Approbation et livraison SMTP (SH-25)

V24 ajoute `contact_delivery` : identifiant, candidature/acteur/vivier, version de brouillon unique par acteur/candidat, empreinte SHA-256, enveloppe et texte exacts privés, Message-ID, approbation/échéance/tentative/résultat. États APPROVED, INVALIDATED, SENDING, ACCEPTED, UNCERTAIN, FAILED. Le marqueur de tentative est commité avant SMTP ; aucune reprise automatique. Suppression en cascade avec la candidature. Voir [SH-25](../validation/sh-25.md).

## Historique des contacts (SH-26)

V25 ajoute une échéance de conservation et un indicateur de purge sur contact_delivery, un indicateur sur les brouillons et contact_delivery_event (séquence, livraison, auteur facultatif, état, code borné et date). Index candidat/date/id pour pagination stable et livraison/séquence pour transitions. Les données antérieures reçoivent un snapshot MIGRATED_STATE daté de la migration, sans transitions inventées. Les nouveaux événements sont écrits dans la transaction de changement d’état.

Les messages approuvés sont consultables par les personnes ayant le droit contact du vivier ; les brouillons restent privés. Après échéance, le texte/enveloppe et les événements sont purgés ; identifiants, version, empreinte et marqueur de tentative restent jusqu’à suppression de la candidature pour empêcher un double envoi. Les brouillons expirés gardent leur numéro mais leur JSON est vidé. Voir [SH-26](../validation/sh-26.md) pour bornes et limites.

## Identité, rapprochement et versions (complément SH-05)

V26 initialise une identité indépendante pour chaque fiche existante ; active_version est vrai. candidate_identity conserve vivier, version de décision et opposition persistante ; candidate_identity_member rattache chaque fiche à une seule identité. Les sources restent distinctes et ne changent pas de rétention. candidate_contact_verification conserve une empreinte de l'e-mail explicitement confirmé, son auteur et sa date ; aucun ancien contact n'est automatiquement vérifié.

candidate_lineage_event fige pour chaque décision et source les références source/publication, version courante de fiche, acteur, motif, action et version active choisie. Les mutations de lignage sérialisent leurs accès par identité et candidat ; les lectures de recherche conservent l'ordre des UUID des candidats. Les événements et vérifications expirés sont purgés ; les liens et opposition restent utiles aux sources survivantes. Voir validation/sh-05.md pour le périmètre, les bornes et les garanties.


## SH-32 — conservation et effacement

V27 ajoute `retention_hold` (une exception par source, acteur, motif, fin, version), `erasure_tombstone` (UUID source/vivier, type IMPORT/BATCH, HMAC d’idempotence et signature, état, tentatives, prochain essai et date de fin) et `retention_mutex` (sérialisation des gels et des nettoyages). Aucun texte, nom de fichier, contact ni empreinte de CV dans le tombstone. Les motifs de gel sont supprimés en cascade avec la source ; l’audit conserve les actions minimisées, pas leur texte.

L’échéance de la politique par finalité est figée sur chaque import. Le gel ne la prolonge pas et ne rouvre aucun accès. Un tombstone expire immédiatement la source, puis un journal signé dans un bucket séparé est écrit avant la suppression physique. Les originaux et toutes leurs versions S3 sont supprimés avant les cascades SQL (extractions, versions humaines, index, exports, snapshots/comparaisons, suivi, messages et gouvernance). Les événements documentaires et métadonnées de lot sont retirés explicitement. Un groupe d’identité subsiste tant qu’il a des sources ; une opposition ne disparaît pas avec une source.

Au démarrage, le journal externe est vérifié et rejoué avant l’ouverture de l’API. Une restauration ancienne ne peut rétablir un gel ni une clé d’import déjà supprimée. Tous les tombstones rejoués repassent en attente afin de repurger aussi les objets restaurés. Voir `../validation/sh-32.md` pour les limites de sauvegarde, les fichiers temporaires et les copies externes.


## SH-33 — demandes d’accès, de rectification et de recours

V28 ajoute `candidate_rights_request`, liée à une source par `candidate_profile` et supprimée en cascade. Elle conserve le type, l’état, les références e-mail/politique, réception/échéance, le résumé borné, la vérification humaine, la version du dossier préparé, la révision de correction et la référence de réponse. Une empreinte interne de rattachement/coordonnées invalide la vérification si ces données changent ; elle n’est jamais exposée dans le contrat. Un e-mail ne peut être enregistré deux fois pour la même source. Limites : 100 demandes par source, 500 versions par demande.

`candidate_rights_event` trace réception, vérification, modification d’échéance, préparation du dossier, correction publiée, réexamen et clôture. Historique paginé par séquence, 25 événements ; demandes et file du vivier paginées par UUID, 25 lignes. Les textes et références suivent l’effacement de la source. `governance_audit` ne conserve que l’action, les identifiants pseudonymes et la date. Aucun texte de demande ou justificatif dans cet audit minimal.

La correction réutilise SH-10 : nouvelle révision humaine et remplacement de l’index courant dans la même transaction que l’action SH-33. L’extraction d’origine reste une preuve immuable. Les anciennes publications et snapshots restent historiques sous leur rétention ; les nouvelles recherches utilisent la correction. Aucun nouveau vecteur candidat n’est créé.

## SH-28 — paramètres versionnés

V29 ajoute `import_settings_head` (ligne unique, version courante, verrou de publication) et `import_settings_version` (cinq limites non secrètes, modèle/empreinte, auteur, date et motif). Les versions ne sont jamais réécrites. `document_import.settings_version` et `import_batch.settings_version` référencent la version réservée ; les fichiers d’un lot héritent de celle du manifeste. Les lignes préexistantes restent nulles pour ne pas inventer une référence. Les versions de configuration survivent à la purge des documents sans contenir leur contenu ou leurs coordonnées.

La version initiale est créée transactionnellement au premier accès ; son auteur nul désigne l’initialisation. Les publications suivantes exigent un administrateur actif, un motif et la version courante. Le journal SH-30 enregistre lectures/publications et téléchargement du modèle, sans recopier les valeurs ou le motif libre dans les événements.

## SH-34 — Sélections CSV (V30)

`csv_export_ticket` conserve seulement propriétaire, vivier, référence facultative au classement, noms de champs, finalité bornée, nombre de lignes et échéances. `csv_export_member` garde les identifiants, versions et ordre choisis. Aucun CSV ni valeur personnelle supplémentaire n'est persisté. Les FK physiques vers utilisateur, vivier, classement et candidat sont en cascade ; perdre un membre invalide toute la sélection. Nettoyage des tickets consommés/expirés chaque minute. Le téléchargement recontrôle les droits et les versions, puis consomme le ticket dans la même transaction que l'audit.

`governance_audit.csv_fields/csv_rows` complètent la finalité existante pour `CSV_EXPORT_CREATED` et `CSV_EXPORT_DOWNLOADED`. L'audit append-only porte les quotas glissants par acteur (20 préparations / 500 lignes / 24 h) : le nettoyage des liens ne réinitialise pas les quotas. Les références de l'audit survivent aux suppressions conformément à SH-30 ; aucun contenu exporté n'y figure.

## SH-36 — suivi des imports (V31)

`document_analysis` ajoute `version` pour les relances conditionnelles, `manual_retries` (trois cycles manuels au maximum, y compris via l'ancien endpoint) et `lease_recoveries`. Les anciennes relances HUMAN_RETRY sont reprises dans le compteur, plafonné à trois. Chaque prise de bail, transition terminale ou relance incrémente la version.

`document_analysis_event.duration_ms` mesure la durée des tentatives terminées avec un bail valide ; les événements antérieurs gardent une durée inconnue. L'index date/import sert la fenêtre de 24 h. Les événements suivent l'effacement de leur source : les agrégats ne constituent pas un historique exhaustif. Aucun texte documentaire supplémentaire n'est conservé. Voir [SH-36](../validation/sh-36.md).
