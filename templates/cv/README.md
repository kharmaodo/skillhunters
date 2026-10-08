# Modèle CV propre

`skillhunters-cv-template-v1.docx` est une nouvelle version de travail sans données personnelles. Elle reprend les rubriques métier du template fourni et remplace le contenu par des champs `{{...}}`. Elle ne prétend pas reproduire à l'identique la mise en page historique à tableaux fusionnés.

Les rubriques âge, photo, vie associative et loisirs ne sont pas reprises. La grille conserve l'échelle déclarative 1–3 et les demi-points. Des sections résumé et expérience documentée sont proposées pour les besoins du MVP.

## Contrat d'export à implémenter

- Répéter les blocs formation, langue, compétence, expérience et certification pour chaque élément validé.
- Injecter seulement des valeurs de la révision sélectionnée ; aucun placeholder ne doit rester dans un export candidat final.
- Champ absent : omettre la rubrique ou afficher « Non renseigné » ; ne jamais inventer de qualification.
- Échapper le contenu, supprimer les métadonnées personnelles et contrôler les sauts de page sur CV court et long.
- Distinguer type d'expérience, dates imprécises, durée documentée et niveau déclaré.
- Renseigner la version du modèle dans l'événement d'export.

Ce fichier est un template éditable, pas un moteur de fusion déjà opérationnel. L'original fourni n'est pas ajouté au dépôt public. La vérification locale comprend un rendu et une inspection visuelle du modèle propre.
