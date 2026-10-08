# ADR 0001 — Stack et frontières du MVP

Statut : stack validée par le propriétaire le 8 octobre 2026 ; détails d'exploitation proposés.

## Décision

React/TypeScript/Vite pour l'interface. Java 21/Spring Boot pour API, domaine et autorisations. Python pour un worker documentaire isolé. PostgreSQL/pgvector pour les données et la recherche. Ports pour stockage S3, OIDC, SMTP et modèles locaux.

## Motivation

Le métier et les transactions demeurent dans Java. Python permet de qualifier les outils documentaires sans lier les parseurs à l'API publique. PostgreSQL évite un deuxième index distribué au démarrage.

## Conséquences

Deux écosystèmes à maintenir ; contrat worker versionné ; propagation des corrélations de traces ; déploiement indépendant du worker. Les modèles et licences sont évalués avant inclusion. Aucune dépendance au cloud IA n'est introduite par défaut. Une alternative Java/Tika dominante reste possible pour un adaptateur, sans déplacer l'autorité métier.

## Alternatives reportées

OpenSearch après benchmark ; Redis si les jobs PostgreSQL deviennent insuffisants ; Graph après SMTP ; portail candidat après validation du besoin. Le projet ne choisit pas une licence de logiciel ou une version de modèle par cette ADR.
