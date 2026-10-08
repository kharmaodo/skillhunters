# Contrôles de sécurité à implémenter

Ce document décrit des exigences ; aucun contrôle n'est prétendu opérationnel au L0.

1. Session BFF OIDC avec cookies HttpOnly, Secure sous HTTPS, SameSite, protection CSRF, rotation et révocation. Autorisations rôle et vivier sur chaque objet, export, résultat et tâche.
2. Import privé avec type réel, limites taille/pages/décompression, antivirus avant parsing, rejet DOCM/chiffrement non supporté, sandbox sans réseau et quotas. Proposition : 15 Mo/fichier, 100 fichiers et 300 Mo/lot.
3. Validation stricte des sorties de modèles et de leurs preuves ; aucun outil exécutable depuis un CV. Retrait des attributs protégés avant indexation.
4. Audit append-only et minimisé ; pas de texte CV, contact complet, cookie ou secret dans les logs techniques. Exports neutralisant les formules CSV.
5. Clefs d'idempotence par utilisateur et opération ; même clef avec un contenu différent = conflit. Contrôle optimiste via ETag/If-Match.
6. Approbation de message liée à l'empreinte exacte ; recontrôle des oppositions lors de l'envoi ; résultat SMTP incertain distinct d'un échec sûr.
7. Provenance, finalité et base légale documentées, droits d'accès/rectification/opposition et recours humain. Pays et durées à confirmer avant données réelles.
8. Effacement des versions, documents, textes, embeddings, caches et snapshots ; tombstone avant purge ; journal des suppressions rejoué avant réouverture après restauration.
9. Aucun secret, fichier CV réel ou modèle sous licence non vérifiée dans ce dépôt public. `.env` local ignoré ; secrets de CI en coffre.

La maquette est une référence UX, jamais un exemple d'autorisation serveur.
