# Gates du lot L0

| Gate | État dans cette livraison | Preuve ou travail restant |
|---|---|---|
| Stack et maquette | Validées | Décisions du 8 octobre 2026 |
| Frontières et structure | Documentées | Architecture, ADR et dossiers |
| Contrat REST | Initial, à relire | OpenAPI 3.1 ; pas de serveur |
| Modèle logique | Initial, à relire | DBML ; pas de migration exécutée |
| Template propre | Livré | DOCX sans données du CV d'exemple ; rubriques et placeholders |
| Corpus d'invariants | Synthétique, initial | Cas JSON annotés ; pas un corpus de performance |
| Benchmark CPU OCR/IA | Non exécuté | Matériel, modèles, licences et corpus à choisir |
| Installation locale/Docker | À livrer en L1 | Aucune stack exécutable au L0 |

## Protocole de benchmark à exécuter

Inventorier CPU/RAM/OS et versions des moteurs, modèles, poids et licences. Constituer au moins 100 CV synthétiques ou explicitement autorisés, annotés, répartis par format (Word modèle et libre, DOC, PDF texte et scan, Markdown). Séparer réglage et validation. Mesurer latence p50/p95, mémoire maximale, précision/rappel compétences, exactitude des périodes, taux de correction et erreurs par format. Mesurer charge et attente indépendamment.

Comparer extraction déterministe au template, extraction assistée et OCR ciblé. Documenter chaque modèle évalué avec empreinte et licence ; ne pas sélectionner un moteur à partir de performances supposées. Sans modèle local disponible, le gate reste ouvert et n'empêche pas de construire les contrats.

## Recette ultérieure

Couverture complète des invariants, isolation vivier, suppression pendant extraction, reprise après crash et restauration avec rejeu des suppressions. Évaluation de la recherche sur 30 requêtes annotées avec corpus exhaustivement jugé. Les seuils de performance du dossier de conception restent des objectifs proposés, pas des résultats.
