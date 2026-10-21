# Ordre de réalisation et statut des US

La numérotation du backlog n'est pas l'ordre de développement. Ce suivi ne remplace pas les critères d'acceptation du dossier de conception.

| US | Statut | Dépendances et reste |
|---|---|---|
| SH-01 / SH-02 | Livrées, PR #2 fusionnée | Connexion et habilitations |
| SH-03 | Réception livrée, PR #3 fusionnée | Les contrôles de contenu sont portés par SH-07 |
| SH-04 | Livrée, PR #4 fusionnée | Import par lots et reprise |
| SH-05 | Partielle, PR #5 fusionnée | Doublons documentaires livrés ; rapprochement candidat et versions attendent les fiches structurées |
| SH-06 | Incrément fusionné, PR #9 | Fiche éditable, disponibilité déclarée, verrou optimiste, masquage et audit ; périmètre dans validation/sh-06.md |
| SH-07 | Livrée, PR #6 fusionnée | Antivirus et extraction texte ; limites dans validation/sh-07.md |
| SH-08 | Livrée, PR #7 fusionnée | OCR local ; limites et preuves dans validation/sh-08.md |
| SH-09 | Incrément fusionné, PR #8 | Propositions sourcées ; couverture et qualification restantes explicites dans validation/sh-09.md |
| SH-10 | Incrément fusionné, PR #10 | Révisions humaines, corrections, publication et index structuré ; limites dans validation/sh-10.md |
| SH-11 | Incrément fusionné, PR #11 | Export DOCX d’une révision publiée ; permission explicite et modèle propre, voir validation/sh-11.md |

| SH-12 | Incrément fusionné, PR #12 | Dictionnaire versionné, alias, fusion explicite et normalisation publiée ; voir validation/sh-12.md |

| SH-13 | Fusionnée, PR #13 | Union des périodes, bornes et récence, rattachements humains ; voir validation/sh-13.md |

| SH-14 | Fusionnée, PR #14 | Recherche structurée, preuve par critère, mode strict réversible ; voir validation/sh-14.md |

| SH-15 | Implémentée, validation en cours | Français borné, ambiguïtés et confirmation avant recherche ; voir validation/sh-15.md |

**Ordre explicite : SH-07 → SH-08 → SH-09 → SH-06**, puis SH-10 et complément SH-05 selon les invariants de versions. SH-06 ne sera pas annoncée comme réalisée avant ses propres tests et sa livraison. Toutes les autres US restent au statut décrit dans le backlog, sans clôture implicite par la présence d'une maquette ou d'un contrat API.
