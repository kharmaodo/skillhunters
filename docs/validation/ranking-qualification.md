# Qualification du classement — outil de mesure

`scripts/evaluate_ranking.py` calcule Recall@20 et nDCG@10 à partir d'un classement ordonné et d'annotations exhaustives. Il ne lance pas de recherche, n'invente pas de résultats et n'est pas un benchmark de capacité. Le corpus SH-16 mesurait une récupération exhaustive ; son ordre UUID ne peut pas être réutilisé comme classement métier SH-18/19.

## Entrées versionnées

Le fichier de jugements comporte `schemaVersion: 1`, `split: "validation"`, `documents` et `queries`. Chaque document a un `id` opaque unique et un `format` parmi DOCX_TEMPLATE, DOCX_FREE, DOC, PDF_TEXT, PDF_SCAN, PDF_MIXED et MD. Chaque requête a un `id` unique et un objet `grades` contenant **tous** les identifiants de documents, y compris les non pertinents de grade 0. Grades entiers 0 à 3 : pertinence croissante selon un barème métier fixé avant mesure. Le nom du split est une déclaration ; le script ne peut pas vérifier l'indépendance humaine des annotations.

Le fichier de résultats contient `schemaVersion: 1`, `commit` (40 caractères hexadécimaux), `configurationSha256` (64 caractères), `judgmentsSha256` (empreinte des octets exacts du fichier de jugements) et `rankings`. Cet objet associe chaque identifiant de requête à une liste ordonnée d'identifiants de documents. Une liste vide est un résultat valide, une requête absente ne l'est pas. Doublons, documents non jugés, requêtes supplémentaires et clés JSON dupliquées sont refusés.

Capturer l'ordre réel SH-18/19 dans le même vivier, à configuration figée, avec les filtres confirmés et la date de référence conservés dans le dossier dont `configurationSha256` est l'empreinte. Conserver au moins les 20 premiers résultats disponibles, sans réordonner ni filtrer après collecte. Un document absent du top 20 compte comme manquant. Si moins de 20 résultats existent, conserver la liste complète. Le script ne peut pas attester la fidélité de la collecte : cette étape doit être contrôlée et n'est pas encore automatisée ici.

Le corpus privé, les requêtes en clair et les jugements restent hors dépôt public. Les rapports ne recopient que les identifiants opaques, formats, métriques et empreintes ; ils restent à conserver dans l'espace de recette selon la politique applicable.

## Exécution

Depuis la racine du dépôt, avec Python standard :

```sh
python3 scripts/evaluate_ranking.py /chemin/judgments.json /chemin/results.json /chemin/report-nouveau.json
python3 -m unittest discover -s scripts/tests -v
```

Le rapport doit être un nouveau fichier et ne peut écraser une entrée. Code de sortie 0 : objectifs proposés atteints sur les entrées ; 1 : mesures valides mais objectifs non atteints ou effectif insuffisant ; 2 : entrées invalides ou écriture impossible. Toujours vérifier le code de sortie et les empreintes, sans réutiliser un ancien rapport après erreur.

## Définitions et limites

- Recall@20 : pertinents de grade strictement positif dans les 20 premiers résultats / tous les pertinents annotés.
- nDCG@10 : somme des gains `(2^grade - 1) / log2(rang + 1)`, rang commençant à 1, divisée par l'ordre idéal des jugements à 10. Les égalités utilisent l'ordre fourni par l'application.
- Moyennes macro : moyenne par requête ayant au moins un pertinent. Une requête sans pertinent a des métriques nulles, pas 100 % ; ses résultats inattendus sont comptés séparément. Une collection entièrement sans pertinents ne passe jamais.
- Rappel par format : pertinents de ce format présents dans le **top 20 global**, sans recalculer un top 20 favorable par format. Effectifs de requêtes scorables affichés.
- Objectifs proposés du dossier : ≥100 documents, ≥30 requêtes, Recall@20 ≥0,90 et nDCG@10 ≥0,80. L'outil ajoute l'absence de résultats inattendus pour les requêtes sans pertinent. Ces seuils restent à contractualiser ; il ne vérifie ni représentativité, ni indépendance du jeu, ni diversité suffisante des formats.

Même si ces objectifs passent, `productionDecision` reste `NOT_APPROVED_BY_THIS_REPORT`. Les tests unitaires sont des oracles mathématiques synthétiques, pas une mesure de la pertinence de l'application. Aucun résultat global MVP n'est déclaré atteint par cette livraison. Prochaine étape : corpus de validation annoté indépendamment, collecte des classements réels et revue métier des résultats et des écarts.

## Première collecte applicative : corpus normalisé

Le [corpus normalisé v1](../../tests/fixtures/ranking/README.md) contient 100 profils et 30 requêtes, avec jugements exhaustifs figés. `RankingCorpusBenchmark`, exécuté via `HumanReviewTest`, collecte l'ordre réellement produit par SH-18/19 sur la recherche structurée après import et publication. Le transport du parseur est simulé ; aucun modèle vectoriel n'est invoqué. Cette mesure bornée ne couvre pas le corpus multi-format indépendant demandé pour la recette globale.

Pour reproduire sur une révision propre (Java 21/Maven) :

```sh
RANKING_CORPUS=true RANKING_COMMIT=$(git rev-parse HEAD) \
  mvn -f services/api/pom.xml -Dtest=HumanReviewTest#normalizedCorpusCollectsProductionRankings test
python3 scripts/evaluate_ranking.py tests/fixtures/ranking/judgments.json \
  services/api/target/ranking-results.json services/api/target/ranking-report-nouveau.json
```

La CI active ce test sur PostgreSQL, calcule les métriques et conserve l'artefact `ranking-qualification` pendant 14 jours (configuration, entrées, résultats, rapport). Elle échoue si les objectifs proposés ne passent pas sur ce corpus technique. Sans `RANKING_CORPUS=true`, le benchmark est explicitement ignoré. Les mesures locales utilisent H2 par défaut et doivent être distinguées du rapport PostgreSQL CI. Ne pas substituer un commit propre aux mesures d'un arbre modifié ; relancer après commit.

## Extension hybride avec modèle local réel

Le même corpus et les mêmes jugements sont également mesurés en mode hybride. Le dictionnaire des deux modes inclut les vingt compétences documentaires et les deux compétences des requêtes négatives : le mode hybride exige un terme reconnu même lorsqu'aucun profil ne le documente. Les jugements ne sont ni recalculés ni ajustés selon les résultats.

`scripts/prepare_ranking_vectors.py` utilise l'Engine ONNX de production avec son manifeste épinglé pour encoder ces 22 libellés. Le benchmark refuse un corpus/modèle incohérent, des dimensions ou normes invalides et tout libellé hors catalogue. Les textes des profils et les identités n'entrent pas dans le port d'encodage. L'inférence est réelle ; seul son transport vers le test Java est un adaptateur de fixture. Les requêtes, publications, indexation et scoring utilisent les services applicatifs. H2 est une mesure locale distincte ; la CI exécute PostgreSQL/pgvector.

Après installation du worker d'embeddings et de son modèle selon SH-16 :

```sh
MODEL_DIRECTORY=/chemin/modele /chemin/venv/bin/python scripts/prepare_ranking_vectors.py /tmp/ranking-vectors.json
RANKING_VECTORS=/tmp/ranking-vectors.json RANKING_COMMIT=$(git rev-parse HEAD) \
  mvn -f services/api/pom.xml -Dtest=HumanReviewTest#normalizedCorpusCollectsRealHybridRankings test
python3 scripts/evaluate_ranking.py tests/fixtures/ranking/judgments.json \
  services/api/target/ranking-hybrid-results.json services/api/target/ranking-hybrid-report-nouveau.json
```

`ranking-hybrid-configuration.json` conserve modèle/manifeste, empreinte des vecteurs, runtime d'inférence, projection, stratégie, seuil 0,65, libellés effectivement encodés et nombres de signaux par canal. Les deux rapports et les vecteurs figurent dans l'artefact CI `ranking-qualification`. Sans `RANKING_VECTORS`, ce second benchmark est explicitement ignoré ; la CI le fournit obligatoirement.

Les requêtes restent des compétences exactes et leurs combinaisons : cette extension vérifie que l'union des canaux préserve le classement documenté. Elle ne prouve pas un gain sémantique sur des paraphrases libres. Les annotations indépendantes, formats complexes, latences et qualification de production restent ouverts. L'ordre UUID entre profils de même pertinence peut différer entre exécutions ; comparer les métriques et le périmètre, pas une égalité arbitraire des listes.
