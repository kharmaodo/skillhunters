# Recette globale du MVP — 24 novembre 2026

## Décision courante

Les 33 stories Must/MVP ont une livraison identifiée dans `develop` après la PR #37 (fusion `8b6c3fe84da8167b270e3d289098edc9f2fbbe63`). **Recette globale ouverte ; mise en production non approuvée par ce dossier.** Les cinq Should/R1 et trois Could/R2 restent hors périmètre. Cette revue documentaire ne constitue pas une nouvelle exécution de tests ni une acceptation métier.

Les objectifs de la section 22 du [dossier de conception](../../Skill-Hunter-Dossier-Conception.md) restent proposés, à contractualiser. Une preuve CI est liée à son commit et ne vaut pas mesure sur le matériel cible. « Partiel » ci-dessous signifie qu'une preuve bornée existe mais ne couvre pas le critère global entier.

## Preuves techniques de référence

Dernier commit de PR #37 vérifié : `ef9c9fc9a2bc72be6ba8afd7dd52432151e35bfe`.

- [CI branche 45342159546](https://github.com/kharmaodo/skillhunters/actions/runs/45342159546) et [CI PR 45342168557](https://github.com/kharmaodo/skillhunters/actions/runs/45342168557) : succès.
- Contrat : 126 opérations ; build frontend ; worker, OCR et embeddings réels.
- Java : 217 tests, sans échec ; les deux intégrations S3 ignorées dans `verify` passent séparément dans `compose-smoke`.
- Navigateur : 21 parcours, dont 320 px et texte 200 %. Certaines transitions SH-36 sont simulées dans le navigateur ; leurs mutations réelles sont testées côté Java.
- Restauration : sauvegarde authentifiée, volumes neufs, rejeu des effacements, versions objet et deux modes d'exécution.

Ces preuves concernent le commit de PR ci-dessus. La CI du commit de fusion doit aussi être consultée : [exécution develop](https://github.com/kharmaodo/skillhunters/actions/runs/45348269704). Ne pas lui attribuer le résultat d'une autre exécution. Les artefacts CI expirent : archiver les rapports retenus et leurs SHA-256 dans l'espace de recette privé avant leur expiration ; ne jamais commiter un corpus réel dans ce dépôt public.

## Matrice des 14 critères globaux

| # | Critère | État et preuves disponibles | Travail nécessaire pour clôturer |
|---|---|---|---|
| 1 | Parcours complet / 33 stories | Livraisons recensées ; tests API et navigateur par parcours | Relecture métier des limites et procès-verbal du scénario complet ci-dessous |
| 2 | Corpus ≥100 CV | Partiel : corpus synthétiques SH-16 et 16 variantes SH-35 | Corpus FR/EN annoté, DOCX modèle/libre, DOC, PDF texte/scan/mixte, MD ; séparation réglage/validation et arbitrage indépendant |
| 3 | Précision/rappel extraction | Partiel : invariants déterministes et formats SH-35 | Mesures par famille, effectifs, intervalles et corrections ; cibles texte 95 %/90 %, OCR 85 %/85 % à confirmer |
| 4 | Preuves des assertions | Partiel : citations, revue et scoring sourcés SH-09/10/18 | Vérifier chaque assertion scorée sur le corpus final ; cible 100 % sourcée, aucune assertion inventée acceptée |
| 5 | Durées et inconnues | Tests déterministes SH-13/18 ; CI verte | Rejouer sur le commit retenu et contrôler les cas litigieux du corpus ; aucune conversion niveau→année |
| 6 | Pertinence du classement | Partiel : SH-16 mesure le rappel exhaustif ; [collecte normalisée](ranking-qualification.md) de Recall@20/nDCG@10 en cours de livraison | ≥30 requêtes jugées exhaustivement ; classement SH-18/19, Recall@20 cible 90 %, nDCG@10 cible 0,80, résultats par format |
| 7 | Biais / projection | Partiel : SH-35 compare noms/âges et formats, projection professionnelle contrôlée | Étendre aux variations photo/genre prévues au dossier, examiner corpus indépendant et limites ; décision conformité explicite |
| 8 | Performance | Non qualifiée à la charge cible | Matériel identifié, cible proposée 8 vCPU/16 Go/SSD, 10 000 fiches/10 utilisateurs ; p95 structurée <2 s, naturelle <10 s, texte <30 s, scan 10 pages <120 s hors attente |
| 9 | Résilience | Tests SH-07/16/25/36 : erreurs, bail expiré, résultat tardif, SMTP incertain | Rejouer sur la configuration cible ; conserver preuves de non-duplication et absence de renvoi aveugle |
| 10 | Sécurité | Isolation, révocation, CSRF, fichiers hostiles, minimisation testés | Revue de configuration cible, dépendances et vulnérabilités exploitables ; arbitrages tracés avant production |
| 11 | Droits / purge | SH-32/33 et restauration avec rejeu testés | Exercice de demande et purge chronométrée sur données de test ; objectif interne <1 jour, cycle des sauvegardes à valider |
| 12 | Exploitabilité | SH-29 : deux modes et restauration vérifiés | Répéter installation sur environnement cible vierge ; revue licences, modèles, versions, clés et procédures |
| 13 | Interface / export | Responsive CI, exports Word et CSV testés | Recette clavier et appareils réels ; ouvrir profils courts/longs dans Word/LibreOffice, vérifier mise en page et absence d'exemples |
| 14 | Mise en service | Décisions produit encore ouvertes | Responsables métier/sécurité/conformité, pays, finalités, conservation, recours et conditions de réévaluation documentés |

Les fiches [SH-35](sh-35.md), [SH-16](sh-16-index.md), [SH-29](sh-29.md) et [SH-36](sh-36.md) restent les références pour leurs limites. La validation technique des invariants ne remplace pas les mesures de qualité ni l'acceptation métier.

## Scénario de recette métier à exécuter

Utiliser uniquement des profils synthétiques ou un corpus autorisé conservé hors dépôt, dans une installation isolée ; Mailpit reçoit tous les messages. Suivre les commandes documentées dans [développement](../development.md) et [exploitation](../operations.md), sans envoyer de mail réel.

1. Noter commit, configuration, matériel et versions ; créer deux viviers et des comptes aux droits distincts. Vérifier le refus d'accès direct et l'absence de décomptes croisés.
2. Importer DOCX, PDF texte, scan et Markdown ; vérifier antivirus, états et preuves. Réimporter un doublon et vérifier le rapprochement explicite. Reprendre une tâche après interruption sans seconde fiche active.
3. Corriger puis publier une révision ; vérifier niveaux, chevauchements et dates inconnues. Rechercher par critères structurés puis phrase confirmée ; relire chaque contribution et comparer deux profils.
4. Exporter Word et CSV avec permission, ouvrir les résultats, révoquer la permission et vérifier les refus. Consulter le suivi des imports avec un administrateur membre du vivier.
5. Préparer un brouillon, l'approuver, le modifier et vérifier l'invalidation. Réapprouver le contenu final puis contrôler la réception unique dans Mailpit et l'historique.
6. Enregistrer opposition et demande de rectification ; vérifier les effets sur contact et recherche. Effacer une source, tester un retour worker tardif et restaurer une sauvegarde antérieure selon SH-29 ; aucun accès rétabli avant rejeu.
7. Refaire les actions principales au clavier, à 320 px/200 % et sur les appareils retenus ; enregistrer écarts et captures synthétiques.

Pour chaque étape : résultat attendu/observé, commit, référence de preuve, anomalie et personne ayant exécuté. Une étape non exécutée reste NON_EXÉCUTÉE ; une simulation doit être nommée comme telle.

## Ordre de qualification

1. Fixer le corpus de validation et ses annotations indépendantes ; conserver les autorisations et versions hors dépôt si nécessaire.
2. Mesurer extraction et classement avec rapports par format, puis corriger les écarts avant de réévaluer sur un jeu tenu à l'écart du réglage.
3. Mesurer charge et latences sur le matériel retenu ; ne pas extrapoler une mesure CI aux 10 000 profils.
4. Réaliser la recette métier, appareils et exports, puis la revue de sécurité/exploitation.
5. Renseigner la décision ci-dessous avec les rapports archivés ; tout changement de modèle, corpus, parseur, règles ou pondération déclenche une réévaluation appropriée.

## Enregistrement de décision à compléter

| Champ | Valeur actuelle |
|---|---|
| Statut | NON_APPROUVÉ — qualifications en cours |
| Commit de livraison candidat | 68c1eb1 (develop après PR #38/#39 ; qualification en cours) |
| Corpus, modèles et rapports SHA-256 | À renseigner avec les mesures retenues |
| Matériel / configuration cible | À confirmer |
| Pays / finalités / conservation / recours | À confirmer par les responsables |
| Responsable métier / sécurité / conformité | À désigner |
| Écarts, périmètre accepté/refusé et conditions | À documenter après recette |
| Date et validations nominatives | Non recueillies |

Aucune valeur manquante ni succès CI ne doit être interprété comme une approbation implicite de mise en service.
