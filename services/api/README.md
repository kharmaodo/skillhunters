# API Java

Java 21 / Spring Boot 3.5.16. Première implémentation : identité OIDC, session BFF et habilitations par vivier. Voir [la procédure de développement](../../docs/development.md).

```sh
mvn test
```

L'application exige une base PostgreSQL, un client OIDC confidentiel et des utilisateurs provisionnés. Pas d'authentification fictive en production. `application-test.yml` utilise une base éphémère et les tests MockMvc simulent uniquement les principals dans les tests.
