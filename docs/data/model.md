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
