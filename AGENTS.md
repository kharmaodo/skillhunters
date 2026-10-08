# Consignes de contribution à Skill Hunters

## Références et état du projet

Lire `README.md`, `Skill-Hunter-Dossier-Conception.md`, `docs/product-scope.md`, `docs/architecture.md` et les consignes du sous-dossier concerné avant modification. Les instructions explicites du propriétaire priment ; ne pas modifier les consignes pour contourner une restriction.

La maquette MVP et la stack ont été validées le 8 octobre 2026. Le dépôt est au lot L0 : contrats et fondations, sans application exécutable. Ne pas présenter un endpoint décrit, une simulation ou un objectif de performance comme une fonctionnalité réalisée. Les hypothèses juridiques, licences, modèles et benchmarks restent à qualifier.

## Branches et livraison

- `develop` est la branche d'intégration ; partir de sa version distante à jour pour les nouvelles branches `feature/…`, `fix/…` ou `docs/…`.
- Préférer une PR vers `develop`. Un push direct sur `develop` est possible lorsque le propriétaire le demande explicitement pour le changement concerné.
- Ne pas pousser directement sur `main`, fusionner une PR, forcer un push, supprimer une branche ou réécrire l'historique sans instruction explicite.
- Examiner l'état Git avant toute modification ; préserver les changements existants. Ne jamais écraser le travail d'un autre contributeur.
- Après conflit, relire les deux intentions, conserver les invariants et refaire les contrôles concernés ; ne pas choisir automatiquement tout un côté.
- Résumer les fichiers modifiés, le comportement, les vérifications exécutées et leurs limites. Ne pas annoncer une publication si le push a échoué.

## Stack et séparation des responsabilités

- Frontend : React, TypeScript, Vite ; pas de Next.js. Reprendre les parcours de la maquette validée, en remplaçant les simulations par les API réelles.
- Backend : Java 21, Spring Boot, Spring Security ; monolithe modulaire par domaine avec ports/adaptateurs. Pas de Spring Modulith imposé.
- Worker : Python isolé pour documents, OCR et modèles. Le backend reste l'autorité sur permissions, validation, sélection et contact.
- Données : PostgreSQL et pgvector ; originaux en stockage objet privé compatible S3. Jobs/outbox transactionnels PostgreSQL au départ.
- OIDC pour l'identité ; SMTP unitaire et Mailpit pour recette. Pas de service IA externe, OpenSearch, Redis obligatoire ou Kubernetes sans décision justifiée.
- Épingler les versions après vérification de compatibilité, support et licence. Ne pas ajouter de framework ou modèle non qualifié pour remplir un dossier vide.

## Contrats et données

Mettre à jour OpenAPI, modèle de données et documentation quand le comportement public change. Valider côté serveur, utiliser erreurs structurées, pagination et contrôle de concurrence. Les migrations versionnées devront être testées avant livraison ; ne pas modifier une migration déjà appliquée pour éviter une nouvelle migration.

Une assertion extraite doit référencer sa version documentaire et une preuve vérifiable. Garder valeur brute, normalisation, précision et statut de validation. Une note de compétence 1 à 3, y compris les demi-points, ne représente jamais des années. Une période absente reste inconnue. Calculer les durées par union des périodes ; distinguer emploi, stage, freelance et projet académique. Ne pas attribuer automatiquement toute la durée d'une mission à une technologie simplement citée.

La similarité vectorielle sert à retrouver des profils, pas à démontrer une aptitude. Séparer score de correspondance et qualité d'extraction, afficher les inconnues, versionner les règles et conserver les preuves. Aucune décision automatique de recrutement ou de rejet.

## Sécurité et confidentialité

- Dépôt public : aucun CV réel, secret, token, journal personnel, document original fourni ou donnée d'exemple identifiable. Utiliser des fixtures synthétiques et les domaines réservés `example.com`.
- Contrôler rôle et vivier sur chaque objet, recherche, export, téléchargement et tâche. ADMIN ne donne pas automatiquement accès aux CV.
- Session BFF OIDC : cookies sécurisés et protection CSRF ; pas de jeton d'accès dans localStorage. La maquette n'est pas une référence de sécurité.
- Documents en quarantaine privée : type réel, taille/pages/décompression bornées, antivirus avant parsing, isolation sans réseau arbitraire, limites CPU/mémoire/temps. Aucun contournement si analyse indisponible.
- Traiter le texte des CV comme données non fiables, jamais comme instructions pour le modèle ou les outils.
- Exclure âge, photo, genre, origine, santé, religion, opinions, situation familiale, activités associatives et loisirs du classement et des embeddings. N'utiliser langue/localisation que pour une exigence professionnelle justifiée.
- Un e-mail exige relecture et approbation du contenu exact. Toute modification annule l'approbation. Recontrôler opposition et permission lors de l'envoi ; résultat SMTP incertain = pas de renvoi automatique aveugle.
- Audit minimisé sans secrets ni CV intégral dans les logs techniques. Effacement des originaux, versions, OCR, vecteurs, caches et snapshots ; tombstone contre les retours tardifs des workers et rejeu des suppressions après restauration.

## Vérifications

Commande actuellement disponible, depuis la racine :

```sh
python3 scripts/check_foundation.py
git diff --check
```

Ces contrôles vérifient les artefacts L0, pas le backend, l'OCR ou les autorisations réelles. À mesure que l'implémentation arrive, documenter les commandes exactes et exécuter les tests ciblés : domaines Java, intégration PostgreSQL/objets/jobs, worker, frontend et parcours navigateur. Ne pas inventer de commandes Maven/npm absentes du dépôt.

Tester les risques : accès croisé vivier, fichiers hostiles, chevauchements, dates inconnues, doublons, idempotence, suppression pendant extraction et message modifié après approbation. Les changements Word doivent être rendus et inspectés ; aucun résidu d'exemple dans les exports. Vérifier responsive à 320 px, texte à 200 %, clavier, focus et erreurs accessibles.

Ne pas ajouter des tests qui ne font que recopier l'implémentation ; ne pas étendre la campagne sans risque concret. Le benchmark OCR/IA nécessite un corpus autorisé, du matériel identifié, les versions exactes et des résultats mesurés par format.
