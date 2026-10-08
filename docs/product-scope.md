# Périmètre de référence

## Décisions validées

Le 8 octobre 2026, le propriétaire a validé la maquette des écrans et parcours MVP, puis React/TypeScript, Java 21/Spring Boot, worker Python et PostgreSQL/pgvector. Le dépôt désigné est `kharmaodo/skillhunters`.

La validation de l'interface ne signifie pas que ses données fictives, scores, contrôles d'accès ou simulations sont du code de production. Le frontend final reproduira les parcours validés avec des contrats métier réels.

## MVP

Connexion, permissions par vivier, import Word/PDF/Markdown individuel et en lot, antivirus, OCR si nécessaire, versions, doublons proposés, extraction sourcée, revue humaine, fiches, compétences et périodes, recherche structurée et naturelle confirmée, recherche hybride, scoring décomposé, listes, comparaison, notes/tags/statuts, brouillon et contact SMTP unitaire validé, historique, export Word/CSV autorisé, audit, droits et suppression.

Une installation héberge une organisation et plusieurs viviers. Le portail candidat, Graph, nouveaux templates/langues et recherche à très grande échelle ne bloquent pas le MVP initial.

## Invariants issus du modèle CV

- Une note 1, 1,5, 2, 2,5 ou 3 est une autoévaluation, jamais une durée.
- Les cellules fusionnées et tableaux sont analysés ; les paragraphes seuls ne suffisent pas.
- Les périodes absentes restent inconnues ; pas de dates inventées.
- Les périodes professionnelles se chevauchant sont réunies, pas additionnées.
- Les projets académiques restent distincts des emplois et stages.
- L'âge, les loisirs, la photo et les activités associatives sont exclus des index et du classement.
- Un examen nommé n'est pas automatiquement une certification obtenue.

## Décisions encore ouvertes

Pays d'exploitation et finalités, conservation/base légale, licence du projet, modèles locaux et moteurs PDF, budget matériel, fournisseur S3, versions supportées des dépendances. Aucune de ces décisions n'est implicitement validée par le choix de la stack.
