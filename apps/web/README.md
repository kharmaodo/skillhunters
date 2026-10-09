# Interface React

TypeScript, React et Vite. Écrans de connexion, viviers autorisés et administration des affectations. Aucun compte ni résultat fictif dans le frontend. La navigation masque les actions non pertinentes ; l'API vérifie indépendamment toutes les autorisations.

```sh
npm ci
npm run dev
npm run build
```

Voir [le mode local](../../docs/development.md) pour l'API, OIDC et les callbacks. Les tokens OIDC restent côté serveur ; seul le jeton CSRF de la session est conservé en mémoire React.
