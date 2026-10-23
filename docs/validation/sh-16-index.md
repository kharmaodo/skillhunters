# SH-16 — Index persistant et recherche professionnelle

## Comportement livré

La recherche hybride combine les preuves structurées SH-14, le plein texte PostgreSQL et le cosinus pgvector exact. Le plein texte porte sur une **projection contrôlée du dictionnaire professionnel**, reconnue dans les compétences ainsi que les titres, expériences, formations et déclarations de certification validés de la dernière révision publiée. Les alias sont explicites ; la projection reconnaît des termes entiers et privilégie les expressions longues. Les autres mots du récit ne sont pas indexés. Les champs personnels, langues, disponibilité et niveaux sont exclus. Une clause détectée comme sensible ou contenant une adresse électronique est exclue de cette projection.

Le récit original n'est jamais transmis au moteur. Seuls les libellés canoniques professionnels passent dans ONNX. Les signaux exposent `sourceField`, l'indice source et les citations ; une mention dans une formation ou une déclaration d'examen ne devient pas une compétence validée ou une certification obtenue. Le mode strict continue d'exiger les preuves structurées des obligatoires. Les états, durées et récences restent calculés par SH-14.

Le plein texte utilise `to_tsvector('simple', canonical)` et la réunion des lexèmes demandés ; un contrôle supplémentaire des tokens préserve la distinction entre C++ et C#. Il n'applique pas de stemming linguistique. La proximité sémantique utilise `<=>` sur `vector(384)`, sans index approximatif ni top-K caché.

## Cycle de vie et cohérence

V14 crée `skill_vector_generation` et `skill_vector_entry`. La clé est `(dictionary_version, model, skill_id)` ; la contrainte de libellé est unique par génération. Une recherche complète les libellés absents dans une seule transaction, après validation du lot entier. Les demandes concurrentes d'une même version sont sérialisées par le verrou du dictionnaire. Les appels chauds réutilisent les vecteurs, y compris lorsque le worker est indisponible ; un manque nécessitant une inférence échouée produit une erreur 503 explicite.

Un nouveau dictionnaire ou modèle crée une nouvelle génération. Les anciennes générations restent utilisables par les recherches qui les référencent ; supprimer une version de dictionnaire supprime ses vecteurs en cascade. Ces tables ne contiennent ni identifiant candidat, ni texte candidat. Elles suivent l'historique du dictionnaire, pas la rétention d'un CV.

Les permissions, publications et expirations sont relues à chaque recherche, sous les verrous existants acteur/fiche. Une nouvelle publication remplace immédiatement les mentions utilisées ; un brouillon n'a aucun effet. L'expiration, la suppression ou le déplacement hors du vivier retire le candidat du prochain résultat, même avec des vecteurs en cache. Il n'existe aucun job différé pouvant recréer une projection candidat supprimée.

Pagination exhaustive par UUID, 25 candidats éligibles maximum et 100 examinés par page ; une page vide peut avoir une suite. 128 libellés distincts maximum par page, avec erreur 422 explicite au dépassement. Les curseurs sont liés aux critères/date/dictionnaire, au modèle, au seuil et à la stratégie `scoped-professional-union-v3` ; repartir de la première page après mise à jour.

## Modèle et installation

Les poids et leurs SHA-256 restent ceux du premier incrément. L'identifiant de pipeline devient `minilm-multilingual-e8f8c211-quint8-avx2-mean-l2-v2` : chaque libellé est encodé seul, car la quantification dynamique pouvait dépendre du padding d'autres libellés du lot. Le test réel vérifie l'égalité exacte entre encodage isolé et lot réordonné.

Compose et la CI utilisent `pgvector/pgvector:0.8.7-pg17-bookworm`, épinglé par digest. PostgreSQL reste en version majeure 17. Pour une installation existante, sauvegarder la base puis recréer le service postgres avec la nouvelle image ; conserver le volume. Ne pas exécuter `down -v` pour cette mise à jour. Flyway applique V14 au démarrage API. Hors Compose, installer l'extension pgvector 0.8.7 sur le serveur et accorder au rôle de migration la création de l'extension, ou la créer préalablement dans la base.

H2 utilise une migration séparée et un adaptateur de test JSON/cosinus. Il ne qualifie pas l'opérateur SQL natif : le job PostgreSQL 17 de la CI exerce V14, les opérateurs plein texte et vectoriels, et vérifie explicitement la version de l'extension et les 384 dimensions.

## Validation et corpus

- Tests d'intégration : réutilisation sans worker, versions isolées, concurrence, rollback d'un lot invalide, cascade, révocation après mise en cache, publication/brouillon/remplacement/suppression et preuves inchangées.
- Corpus `tests/fixtures/search/hybrid-corpus.json` : 42 profils synthétiques, 30 requêtes FR/EN/alias ; tous les couples requête/profil sont jugés par équivalence explicite du concept. Les profils non listés sont non pertinents. Annotations écrites avant les mesures, sans CV réel ; ce n'est pas une expertise indépendante du recrutement.
- `scripts/prepare_hybrid_benchmark.py` produit les vrais vecteurs ONNX. `HybridCorpusBenchmark` exécute les services applicatifs réels, la publication, la projection, le SQL et toutes les pages de taille 7. Le parsing documentaire est simulé ; tous les documents sont du Markdown synthétique normalisé.
- Le rapport donne rappel exhaustif, précision, pages, temps et provenance du corpus/modèle. Le test exige un rappel exhaustif moyen ≥ 90 %. La CI publie son rapport PostgreSQL comme artefact `sh16-corpus-report`.

```sh
MODEL_DIRECTORY=/tmp/embedding-model /tmp/embedding-venv/bin/python scripts/prepare_hybrid_benchmark.py /tmp/sh16-vectors.json
SH16_BENCHMARK_VECTORS=/tmp/sh16-vectors.json mvn -f services/api/pom.xml test
# Résultat : services/api/target/sh-16-corpus-report.json
```

## Limites de qualification

Le parcours de récupération SH-16 est livré pour ce vocabulaire professionnel contrôlé. Il ne vectorise pas le narratif libre intégral. Les termes absents du dictionnaire doivent être administrés explicitement ; la recherche structurée reste disponible pour les compétences libres.

L'ordre UUID n'est pas un classement de pertinence. Les objectifs globaux Recall@20/nDCG@10 du dossier doivent être évalués avec le classement SH-18/19 ; ils ne sont pas déclarés atteints par un rappel exhaustif. La qualification multi-format OCR, un corpus métier indépendant et la latence à 10 000 profils restent des gates MVP, sans promesse déduite de ce petit corpus. Les appels froids sont sérialisés par dictionnaire ; aucun gain de capacité à grande échelle n'est affirmé.

## Résultat local conservé

Le rapport `sh-16-corpus-report.json` mesure sur H2 et les vrais vecteurs ONNX un rappel exhaustif moyen de 100 % et une précision moyenne de 67,89 % (30 requêtes, 42 profils). Les faux positifs restent des rapprochements à vérifier. La migration et les opérateurs PostgreSQL natifs doivent être confirmés par la CI avant fusion. Les 128 tests hors corpus, les 4 tests ONNX, le build frontend et les contrôles L0 ont réussi ; le test de corpus a ensuite réussi séparément.
