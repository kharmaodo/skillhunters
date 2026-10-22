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

V5 ajoute `document_analysis` (une tâche par import, demandeur, état, tentatives, bail/token, échéance de reprise, versions antivirus/parseur et JSON de preuves) et `document_analysis_event` (transitions sans texte CV). Les clés étrangères suppriment les dérivés lorsque la source est effacée. La rétention complète et sa tâche de purge restent SH-32 ; les résultats expirés sont déjà inaccessibles et les workers ne peuvent pas les republier. Les accès au résultat et relances humaines sont tracés dans `document_audit`.

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
