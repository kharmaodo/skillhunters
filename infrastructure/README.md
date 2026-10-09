# Infrastructure locale identité

Compose de recette PostgreSQL, Keycloak et API avec frontend compilé. Le jeu synthétique est explicitement provisionné par `seed`, jamais par les migrations métier. Secrets locaux générés, non versionnés.

Procédures : [docs/development.md](../docs/development.md). Le worker, l'antivirus, le stockage S3 et SMTP seront ajoutés dans leurs incréments ; ce Compose ne prétend pas livrer l'ensemble du MVP.
