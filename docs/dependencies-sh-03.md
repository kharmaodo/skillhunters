# Dépendances de l'incrément SH-03

- AWS SDK Java v2 S3 et URLConnection 2.55.3 : Apache-2.0, Java 21, versions fixées dans Maven. Usage limité à l'adaptateur S3 ; aucun service IA externe.
- Playwright 1.64.0 : Apache-2.0, dépendance de test fixée dans package-lock.json ; Chromium pour les scénarios synthétiques.
- MinIO community : release source RELEASE.2025-10-15T17-29-55Z, AGPL-3.0, licence incluse dans l'image de recette. Upstream archivé : choix de recette uniquement, pas engagement de maintenance de production. La release corrige l'escalade via politiques de session ; on ne reprend pas l'ancienne image binaire de septembre.
- L'image de compilation Go utilise la ligne de maintenance 1.25-bookworm, Debian bookworm-slim pour l'exécution. Les digests et l'audit complet de dépendances restent une gate de production.

Références :
- https://github.com/aws/aws-sdk-java-v2
- https://github.com/microsoft/playwright
- https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z
- https://github.com/minio/minio/blob/RELEASE.2025-10-15T17-29-55Z/LICENSE
