# Infrastructure locale Skillhunters

Compose de recette avec PostgreSQL/pgvector, Keycloak, quarantaine S3, journal d’effacement séparé, workers, ClamAV, Mailpit et API/frontend. Les services exposés sont liés à loopback et les images externes épinglées par digest.

[Démarrage, sauvegarde, restauration et mise à niveau](../docs/operations.md). Le seed synthétique est explicite, jamais chargé par les migrations. Ne pas supprimer les volumes pour un arrêt normal, et ne jamais restaurer un ancien journal d’effacement avec les données métier.
