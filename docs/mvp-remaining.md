# Recensement MVP — 24 novembre 2026

Référence : backlog du dossier de conception, documentation de validation et état de `develop` après fusion de la PR #37 (SH-36). Les 41 tickets comprennent 33 Must/MVP, 5 Should/R1 et 3 Could/R2. Une PR fusionnée portant un incrément ne vaut pas clôture de tous les critères du ticket.

## Tickets livrés dans develop

33 tickets avec livraison identifiée, sous réserve des qualifications et limites ci-dessous.

| Ticket | État |
|---|---|
| SH-01 — Connexion et session | Livré dans develop ; limites des fiches de validation applicables |
| SH-02 — Habilitations par vivier | Livré dans develop ; limites des fiches de validation applicables |
| SH-03 — Import individuel sécurisé | Livré dans develop ; limites des fiches de validation applicables |
| SH-04 — Import en masse et reprise | Livré dans develop ; limites des fiches de validation applicables |
| SH-05 — Doublons, rapprochement et versions | Fusionné — PR #29 ; rapprochement vérifié, lignage et version active |
| SH-06 — Fiche candidat et disponibilité | Livré dans develop ; limites des fiches de validation applicables |
| SH-07 — Analyse antivirus et extraction texte | Livré dans develop ; limites des fiches de validation applicables |
| SH-08 — OCR des pages scannées | Livré dans develop ; limites des fiches de validation applicables |
| SH-09 — Extraction structurée avec preuves | Livré ; qualification restant avec SH-35 |
| SH-10 — Validation et correction humaine | Livré dans develop ; limites des fiches de validation applicables |
| SH-11 — Export Word normalisé | Livré dans develop ; limites des fiches de validation applicables |
| SH-12 — Taxonomie et synonymes | Livré dans develop ; limites des fiches de validation applicables |
| SH-13 — Durées et récence par compétence | Livré dans develop ; limites des fiches de validation applicables |
| SH-14 — Recherche structurée | Livré dans develop ; limites des fiches de validation applicables |
| SH-15 — Recherche en langage naturel | Livré dans develop ; limites des fiches de validation applicables |
| SH-16 — Recherche hybride | Livré ; qualification restant avec SH-35 |
| SH-18 — Score décomposé et reproductible | Livré ; qualification restant avec SH-35 |
| SH-19 — Configuration du scoring | Livré ; qualification restant avec SH-35 |
| SH-20 — Contestation et dérogation | Livré dans develop ; limites des fiches de validation applicables |
| SH-21 — Listes notes tags et statuts | Livré dans develop ; limites des fiches de validation applicables |
| SH-22 — Comparaison de profils | Livré dans develop ; limites des fiches de validation applicables |
| SH-24 — Modèles et brouillons personnalisés | Livré dans develop ; limites des fiches de validation applicables |
| SH-25 — Validation humaine et SMTP | Livré dans develop ; limites des fiches de validation applicables |
| SH-26 — Historique des contacts | Fusionné — PR #28, 173 tests Java et 12 parcours navigateur réussis |
| SH-30 — Audit des accès et mutations | Livré dans develop ; limites des fiches de validation applicables |
| SH-31 — Provenance base légale et opposition | Livré dans develop ; limites des fiches de validation applicables |
| SH-32 — Conservation et suppression complète | Fusionné — PR #30 ; gels, purge et rejeu signés ; recette de restauration complétée par SH-29 |

| SH-33 — Accès, rectification et recours | Fusionné — PR #31 ; build et parcours corrigés, CI verte |

| SH-28 — Paramètres et template versionné | Fusionné — PR #32 ; quotas versionnés et références réservées, CI verte |

| SH-29 — Exploitation locale et Docker | Fusionné — PR #33 et correctif #34 ; restauration sur volumes neufs, rejeu et deux modes validés en CI |

| SH-34 — Export autorisé | Fusionné — PR #35 ; CSV borné, permissions, quotas, liens à usage unique et CI verte |

| SH-35 — Évaluation des biais et modèles | Fusionné — PR #36 ; suite technique multi-format, qualification de production encore ouverte |

| SH-36 — Suivi opérationnel des imports | Fusionné — PR #37 ; métriques par vivier, file paginée, relances contrôlées et reprise de bail |

## Recette globale restant ouverte

**0 ticket MVP sans livraison identifiée ; 33 tickets livrés avec leurs limites documentées.** Cela ne signifie pas que tous les critères globaux sont acceptés. La [matrice de recette globale](validation/mvp-acceptance.md) suit les 14 critères du dossier de conception, les preuves techniques et les qualifications manquantes.

La suite technique SH-35 ne clôt pas les qualifications sur corpus représentatif ni la décision de mise en production.

## Compléments de tickets déjà fusionnés

- **SH-05** : complément fusionné dans la PR #29 ; limites documentées dans `validation/sh-05.md`.
- **SH-09** fournit une extraction déterministe sourcée, pas une qualification complète sur CV libres multi-formats ; précision/rappel et corpus restent à qualifier avec SH-35.
- **SH-16 / SH-18 / SH-19** : recherche et scoring livrés ; benchmark global Recall@20/nDCG@10, qualification multi-format et cible 10 000 profils demeurent des gates MVP non validés.
- Les limites documentées dans les fiches `docs/validation/sh-*.md` restent applicables ; ce recensement ne constitue pas une nouvelle recette d'acceptation globale.

## Ordre proposé

Recette globale, puis qualification de production selon la matrice dédiée. Les tests de qualité et d'exploitation peuvent être préparés en continu ; la clôture dépend de leurs preuves, pas de l'ordre numérique.

Hors MVP : SH-17, SH-23, SH-27, SH-37 et SH-38 (R1) ; SH-39, SH-40 et SH-41 (R2).
