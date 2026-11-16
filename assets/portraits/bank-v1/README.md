# Banque de portraits intégrée au jeu

La version finale utilise **180 portraits préparés**, soit trois familles de
peau × deux sexes × six âges × cinq modèles. Ils alimentent **1 260 calques**.

## Planches finales

- [Famille claire : tous les modèles et âges](family-0-all-ages.png).
- [Famille intermédiaire : tous les modèles et âges](family-1-all-ages.png).
- [Famille foncée : tous les modèles et âges](family-2-all-ages.png).
- [Assemblages croisés : mêmes choix de traits à travers six âges](mixed-aging-preview.png).
- [Recettes de ces assemblages](mixed-recipes.json).
- [Manifeste des identités, âges et repères](manifest.json).
- [Vérifications de la banque](verification.json).

Les colonnes des planches suivent l'ordre enfant, adolescent, adulte, âge mûr,
âgé et vieillard. Les fichiers individuels préparés sont dans `families/`.
Les ressources utilisées par le jeu sont dans
`client/src/main/resources/city/client/portraits/bank-v1/`, à la racine du dépôt.

## Provenance et reproduction

Les créations utilisent l'outil intégré `image_gen`. Les prompts complets
figurent dans `sources.json` (dix séries d'âge initiales) et
`family-sources.json` (trente séries finales, avec familles de peau propres).
Les originaux correspondants sont conservés dans `originals/` et
`family-originals/`. Les chemins de provenance dans ces JSON servent à tracer
la génération ; la reconstruction utilise les copies présentes dans le dépôt.

Le script `tools/BuildGamePortraits.py` prépare les images par découpage,
réduction au plus proche voisin et alignement des lignes de traits. Il copie
ensuite les pixels dans une partition de calques commune, sans recoloration.
Les familles proviennent de portraits complets distincts. Les essais antérieurs
de la banque restent dans leurs répertoires versionnés ; ils ne sont pas chargés
par le jeu.

L'assemblage de tous les éléments d'un même modèle restitue exactement son
portrait préparé. Les variantes ont les mêmes masques : un mélange ne laisse
aucun trou. Les différences de relief ou d'ombre entre modèles peuvent rester
visibles aux raccords ; aucun trait n'est cependant pris dans une autre famille
de peau. Le cadre conserve ensemble coiffure, silhouette extérieure et buste.

La documentation de l'identifiant, du vieillissement et de l'intégration Swing
se trouve dans [docs/PORTRAITS.md](../../../docs/PORTRAITS.md).
