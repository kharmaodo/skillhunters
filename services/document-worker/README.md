# Worker documentaire SH-07

Worker HTTP privé Python, appelé par le backend propriétaire des tâches PostgreSQL. Entrée binaire bornée avec empreinte et jeton de service ; aucun accès S3 ou base de données donné au worker. Antivirus ClamAV obligatoire avant parsing dans un enfant Linux seccomp. Voir [validation SH-07](../../docs/validation/sh-07.md) et [installation](../../docs/development.md).

Dépendances : pypdf 6.19.0 (BSD-3-Clause), olefile 0.47 (BSD-2-Clause), defusedxml 0.7.1 (PSF), antiword 0.37 (GPL-2.0-or-later), libseccomp (LGPL-2.1), ClamAV 1.5.3 (GPL-2.0). Les composants GPL restent des programmes séparés ; les obligations de notices et de fourniture du code source correspondant s'appliquent lors d'une redistribution d'images. Aucun changement de licence de l'application n'est décidé ici. Notices Python installées dans les dist-info ; notices système dans `/usr/share/doc` ; conserver ces notices dans les distributions.

Sources éditeurs consultées le 11 octobre 2026 :
- https://pypi.org/project/pypdf/6.19.0/
- https://pypi.org/project/olefile/0.47/
- https://pypi.org/project/defusedxml/0.7.1/
- https://docs.clamav.net/manual/Usage/ClamdProtocol.html
- https://www.clamav.net/downloads

L'OCR et l'extraction de compétences ne sont pas implémentés ici. Un résultat EXTRACTED ne permet pas une indexation automatique ni une décision de recrutement.
