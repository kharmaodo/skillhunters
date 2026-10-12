# Corpus synthétique SH-07

`synthetic.doc` a été généré avec LibreOffice (export Word 97) depuis un RTF créé pour ce test. Il contient uniquement « Synthetic candidate », « Java declared level 2.5 », « No dates supplied » puis huit répétitions de « Synthetic project: backend development, integration tests and application documentation. ». Aucun auteur, dernier éditeur, entreprise ou titre dans les propriétés OLE. Il ne provient pas du CV fourni par l'utilisateur.

Les PDF et DOCX des autres tests sont générés en mémoire. Le test antivirus réel utilise la chaîne de test inoffensive EICAR, construite en mémoire dans `scripts/smoke_analysis.py` ; les tests unitaires contrôlent le comportement face aux verdicts et ne se substituent pas à cette recette ClamAV.

`scanned.pdf` est une page image synthétique générée avec Pillow et DejaVu Sans : titre « PROFIL SYNTHETIQUE », Java/Spring Boot, dates inconnues, niveau déclaré 2.5 et une phrase anglaise sur les tests d’intégration. Aucune couche texte et aucun auteur. Les tests construisent un PDF mixte en ajoutant une page native, sans CV réel.
