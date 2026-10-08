# Cas synthétiques de référence

`cases.json` décrit 18 invariants attendus. Les cas contiennent uniquement des exemples fabriqués, pas des personnes ou CV réels. Ils servent d'oracles fonctionnels pour les futures implémentations Java et Python.

Le contrôleur L0 vérifie leur structure et recalcule indépendamment les unions de mois connues. Il ne teste pas un OCR ou un LLM : ces services n'existent pas encore. Les cas de biais, sécurité, identité, livraison et suppression sont des scénarios à transformer en tests d'intégration lors des lots concernés.

Convention de calcul mensuel : mois de début et de fin inclus, union des mois, aucune équivalence temps plein. Une date absente ou à l'année ne passe pas par cette formule exacte.
