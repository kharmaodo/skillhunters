# Corpus normalisé de classement v1

100 profils synthétiques Markdown : vingt compétences, cinq profils par compétence principale ; les deux premiers ajoutent la compétence suivante. Les textes sont stockés dans `corpus.json`, pas importés depuis de vrais CV. 30 requêtes : 18 simples, 10 couples obligatoire/souhaité et deux compétences absentes en mode strict. Les 28 autres requêtes sont non strictes. Les jugements exhaustifs sont figés dans `judgments.json` avant exécution de l'application.

Barème : une compétence unique demandée et documentée vaut 3, sinon 0. Pour un couple : les deux valent 3, obligatoire seul 2, souhaité seul 1, aucun 0. Exemple q18 (Java obligatoire, Python souhaité) : d000 = 3, d002 = 2, d010 = 1, d020 = 0. Les requêtes négatives q28/q29 n'ont aucun pertinent. Il s'agit d'un oracle technique écrit par le même contributeur, **pas d'une annotation métier indépendante** ni d'un jeu de validation représentatif tenu secret.

Le test importe chaque source par le contrôleur, remplace uniquement le transport du parseur par ses blocs normalisés, puis utilise extraction, revue publiée, recherche structurée, scoring et pagination réels. Aucun SQL ne préremplit les scores ou résultats. Le seed commun n'est pas publié. Entre requêtes, le fixture expire le snapshot précédent puis appelle le nettoyage applicatif pour respecter le quota de vingt snapshots actifs sans modifier la limite de production.

Le test ne lit pas les grades pour choisir ou trier les résultats. Il collecte toutes les pages dans leur ordre applicatif, vérifie l'unicité et la continuité des rangs et refuse un candidat étranger au corpus. Le calcul Python séparé compare ensuite les résultats aux grades. Configuration, corpus, jugements et résultats ont des empreintes ; base, Java, date de référence, règles et poids sont tracés.

Ce corpus vérifie le mécanisme de classement structuré, pas la compréhension de CV libres, le parsing multi-format, le mode hybride, l'équité en population ni la capacité à 10 000 profils. Ces qualifications restent ouvertes même avec 100 % sur ce jeu construit. Aucun parseur ou score applicatif n'a été ajusté en fonction de ces jugements dans cet incrément.

La première répétition locale, avec les deux requêtes négatives non strictes, a produit Recall@20 = 1, nDCG@10 = 1 et 200 résultats sans pertinence pour ces deux requêtes : le mode non strict conserve normalement les profils sans preuve. Le protocole a été corrigé pour demander explicitement le mode strict sur ces deux cas, sans modifier les grades ni le code applicatif. Il ne s’agit donc pas d’un jeu tenu à l’écart après une mesure exploratoire.

## Extension hybride

Les mêmes entrées sont désormais collectées dans un second benchmark avec les vecteurs ONNX réels préparés hors Java. Le dictionnaire commun comprend aussi COBOL et Fortran pour rendre les deux requêtes négatives valides en mode hybride. Aucun texte ni jugement du corpus n'est modifié. Les limites « pas de mode hybride » ci-dessus décrivent la première livraison structurée ; l'extension couvre maintenant l'union des canaux sur ces requêtes exactes, sans qualifier la sémantique libre. Voir le protocole dans `docs/validation/ranking-qualification.md`.
