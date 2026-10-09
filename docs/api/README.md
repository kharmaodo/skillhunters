# Contrat REST initial

`openapi.json` est un contrat OpenAPI 3.1.0. Le champ `x-implementation-status` distingue les opérations implémentées de celles encore planifiées. Seuls session, viviers et habilitations sont actuellement livrés.

## Conventions

- Préfixe `/api/v1`, JSON UTF-8, UUID et dates UTC ISO 8601.
- Session BFF `SH_SESSION` ; contrôle CSRF sur chaque mutation. Le jeton CSRF est remis par `GET /session`, pas un jeton d'accès OIDC.
- Rôle et vivier contrôlés par le serveur pour chaque objet. Les réponses ne divulguent pas l'existence d'un objet interdit. Le rôle ADMIN seul n'accorde pas la lecture d'un CV.
- `If-Match` obligatoire sur les mutations versionnées : `428` absent, `412` périmé. Le client obtient la version via `ETag` sur la lecture correspondante. La validation de fiche utilise l'ETag de sa révision.
- `Idempotency-Key` pour créations sensibles, import et envoi. Même clé + contenu différent = `409`; même contenu = résultat initial. Portée acteur/opération, durée de conservation de clé à fixer ; elle ne garantit pas l'exactly-once SMTP.
- Pagination à curseur pour candidats, recherche et audit. Pagination des autres collections à ajouter avant volumétrie réelle.
- Les `202` créent une tâche ou un état en cours ; ils n'annoncent pas l'achèvement du traitement. Une fiche validée peut attendre l'indexation.
- Téléchargements avec disposition attachment, type réel et contrôle d'accès à chaque requête. Aucun bucket public.
- Les dates partielles sont conservées sans convertir une année en jour précis. Les intervalles et compétences doivent référencer les preuves de la même révision.
- Les sommes de poids, chronologies, unicité des alias et relations entre UUID sont des invariants métier supplémentaires aux schémas JSON.

## Autorisations cibles

| Famille | Permission |
|---|---|
| Session | Utilisateur connecté |
| Vivier, recherche, revue | Recruteur affecté au vivier |
| Documents, export | Lecture ou export explicitement autorisé |
| Listes et notes | Auteur ou partage autorisé, toujours borné au vivier |
| Contact | Permission contacter, recontrôlée lors de l'envoi |
| Audit, droits et opposition | Conformité ou habilitation dédiée |
| Habilitations | Administrateur |
| Scoring | Responsable recrutement habilité |
| Conservation | Conformité habilitée |

## Limites explicites de la version L0

À compléter avant implémentation : endpoints de login/callback OIDC, protocole worker interne et baux, rapprochement/réversibilité des doublons, listing/versionnement des templates et politiques, historique détaillé des contacts, points de suivi des exports/purges et workflow complet de demandes de droits. Ce contrat initial ne couvre donc pas encore toutes les opérations du backlog.

Le serveur expose les opérations identité/viviers sous `/api/v1`. La connexion démarre par `GET /oauth2/authorization/skillhunters` ; le callback `GET /login/oauth2/code/skillhunters` est géré par Spring Security avec contrôle state, nonce, PKCE et signature OIDC. Ces routes de protocole ne sont pas des API métier JSON. Voir `docs/development.md` pour leur configuration.

`PUT /admin/memberships` est naturellement idempotent : il remplace les rôles du couple utilisateur/vivier et répond 204. Il exige CSRF et ADMIN, sans clé d’idempotence ni attribution de rôle global. `GET /session` sépare rôles globaux et `memberships`. La session servlet est en mémoire pour cet incrément mono-instance.

SH-03 implémente `POST /imports` (un fichier), `GET /imports/{id}`, `GET /pools/{poolId}/imports` et `GET /pools/{poolId}/import-policies`. Envoyer `poolId` en champ texte, `basis` en partie JSON et `files` en partie fichier. Le succès 202 signifie quarantaine, jamais analyse réussie ; `RECEIVING` représente une réception incomplète reprenable avec sa clé initiale.

SH-04 ajoute les manifestes JSON via `POST /import-batches`, puis les contenus multipart par `PUT /import-batches/{batchId}/items/{itemId}/content`. Le suivi et l'historique sont séparés des anciennes réceptions individuelles, dont le contrat reste compatible. Le résultat de chaque fichier doit être lu dans `state`/`errorCode` : HTTP 200 peut représenter un rejet ou une panne reprenable. `PUT /imports/{id}/content` reprend un ancien dépôt individuel après vérification de l'original.
