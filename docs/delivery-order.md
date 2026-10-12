# Ordre de réalisation et statut des US

La numérotation du backlog n'est pas l'ordre de développement. Ce suivi ne remplace pas les critères d'acceptation du dossier de conception.

| US | Statut | Dépendances et reste |
|---|---|---|
| SH-01 / SH-02 | Livrées, PR #2 fusionnée | Connexion et habilitations |
| SH-03 | Réception livrée, PR #3 fusionnée | Les contrôles de contenu sont portés par SH-07 |
| SH-04 | Livrée, PR #4 fusionnée | Import par lots et reprise |
| SH-05 | Partielle, PR #5 fusionnée | Doublons documentaires livrés ; rapprochement candidat et versions attendent les fiches structurées |
| SH-06 | **Non développée** | Dépend explicitement de SH-09 : fiche candidat éditable, disponibilité inconnue si absente, verrou optimiste, contacts masqués et audit |
| SH-07 | Livrée, PR #6 fusionnée | Antivirus et extraction texte ; limites dans validation/sh-07.md |
| SH-08 | Incrément en revue | OCR local ; limites et preuves dans validation/sh-08.md |
| SH-09 | À développer après SH-08 | Extraction structurée et preuves ; dépend de SH-07 et SH-08 |
| SH-10 | À développer après SH-09 | Validation humaine des propositions |

**Ordre explicite : SH-07 → SH-08 → SH-09 → SH-06**, puis SH-10 et complément SH-05 selon les invariants de versions. SH-06 ne sera pas annoncée comme réalisée avant ses propres tests et sa livraison. Toutes les autres US restent au statut décrit dans le backlog, sans clôture implicite par la présence d'une maquette ou d'un contrat API.
