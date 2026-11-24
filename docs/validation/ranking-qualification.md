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
