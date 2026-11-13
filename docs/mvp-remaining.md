# Recensement MVP — 13 novembre 2026

Référence : backlog du dossier de conception, documentation de validation et état de `develop` après fusion de la PR #27 (SH-25). Les 41 tickets comprennent 33 Must/MVP, 5 Should/R1 et 3 Could/R2. Une PR fusionnée portant un incrément ne vaut pas clôture de tous les critères du ticket.

## Tickets MVP sans livraison complète identifiée

| Ticket | État au début de cette livraison | Travail restant |
|---|---|---|
| SH-26 — Historique des contacts | Implémenté, validation dans cette branche | Historique partagé, transitions, pagination, accès et purge ciblée ; voir validation/sh-26.md |
| SH-28 — Paramètres et template versionné | Fondations présentes, ticket non livré | Administration versionnée des quotas/paramètres, règles appliquées aux nouveaux travaux et référence des anciennes règles ; modèle Word nettoyé déjà livré par SH-11 |
| SH-29 — Exploitation locale et Docker | Démarrage et CI présents, ticket non clos | Procédure et recette sauvegarde/restauration complète avec contrôle des empreintes, sécurisation des deux modes d'exploitation |
| SH-32 — Conservation et suppression complète | À livrer | Purge de toutes les catégories, gel motivé, tombstones workers et rejeu des suppressions après restauration ; dépend du complément SH-05 |
| SH-33 — Accès, rectification et recours | À livrer | Demandes candidat vérifiées, suivi des actions et clôture ; dépend de SH-32 |
| SH-34 — Export autorisé | À livrer | CSV de sélection avec champs autorisés, quotas, neutralisation des formules et lien bref ; l'export Word SH-11 ne clôt pas ce ticket |
| SH-35 — Évaluation des biais et modèles | À livrer | Tests contrefactuels et par format, rapport de qualification et décision de mise en service |
| SH-36 — Suivi opérationnel des imports | Mécanismes de traitement présents, ticket non livré | Métriques agrégées, file morte, relance contrôlée et recette de reprise après expiration de bail |

Soit **8 tickets** à l'ouverture de cette livraison, dont SH-26 en cours. Après fusion et validation de SH-26, les 7 autres restent à livrer/finaliser, ainsi que les compléments ci-dessous.

## Compléments de tickets déjà fusionnés

- **SH-05** reste explicitement partiel : rapprochement par contacts validés, fusion humaine auditée, lignage des versions et version active. La détection de documents identiques est livrée.
- **SH-09** fournit une extraction déterministe sourcée, pas une qualification complète sur CV libres multi-formats ; précision/rappel et corpus restent à qualifier avec SH-35.
- **SH-16 / SH-18 / SH-19** : recherche et scoring livrés ; benchmark global Recall@20/nDCG@10, qualification multi-format et cible 10 000 profils demeurent des gates MVP non validés.
- Les limites documentées dans les fiches `docs/validation/sh-*.md` restent applicables ; ce recensement ne constitue pas une nouvelle recette d'acceptation globale.

## Ordre proposé

SH-26 → complément SH-05 → SH-32 → SH-33, puis finalisation SH-28/SH-29, SH-34, SH-35 et SH-36 avant recette globale. Les tests de qualité et d'exploitation peuvent être préparés en continu ; la clôture dépend de leurs preuves, pas de l'ordre numérique.

Hors MVP : SH-17, SH-23, SH-27, SH-37 et SH-38 (R1) ; SH-39, SH-40 et SH-41 (R2).
