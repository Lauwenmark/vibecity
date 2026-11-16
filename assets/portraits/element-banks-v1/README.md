# Banques d'éléments de portraits — version 1

**Dix banques : cinq classes d'âge × deux sexes**, chacune issue d'un portrait
256 × 256. Chaque banque contient un exemplaire de chaque élément.

Toutes les pièces sont copiées depuis les portraits réduits, sans redessin,
lissage, recoloration, mise à l'échelle ou génération pendant l'extraction.
La réduction initiale des images générées utilise le plus proche voisin.

## Consulter les banques

- [Catalogue comparatif des traits](catalogue-traits.png) : sourcils, yeux, nez et bouches.
- [Les dix portraits](planche-portraits.png).
- [Les dix recompositions](planche-recompositions.png).
- [Index exploitable par programme](index.json), classé par banque et par type de pièce.
- [Rapport des vérifications](verification.json).

| Classe graphique | Féminin | Masculin |
|---|---|---|
| Enfant, environ 8 ans | [Planche](enfant/feminin/planche-pieces.png) | [Planche](enfant/masculin/planche-pieces.png) |
| Adolescent, environ 16 ans | [Planche](adolescent/feminin/planche-pieces.png) | [Planche](adolescent/masculin/planche-pieces.png) |
| Adulte, environ 30 ans | [Planche](adulte/feminin/planche-pieces.png) | [Planche](adulte/masculin/planche-pieces.png) |
| Âge mûr, environ 52–55 ans | [Planche](age-mur/feminin/planche-pieces.png) | [Planche](age-mur/masculin/planche-pieces.png) |
| Âgé, environ 80–82 ans | [Planche](age/feminin/planche-pieces.png) | [Planche](age/masculin/planche-pieces.png) |

## Contenu de chaque banque

Les **13 pièces de partition** sont le fond, la chevelure, le support du visage,
le cou et buste, les deux oreilles, les deux sourcils, les deux yeux, le nez,
la bouche et les raccords de contour. Soit **130 pièces sources** au total.

Les groupes `paire-yeux` et `paire-sourcils` ajoutent 20 compositions pratiques.
Ils ne doivent pas être superposés en plus des yeux et sourcils individuels
lors de la reconstruction. Chaque banque fournit aussi `support-traits.png`,
qui regroupe les éléments structurels en laissant les six fenêtres de traits
transparentes, pour faciliter l'assemblage avec des donneurs.

- `portrait.png` : référence 256 × 256 autonome.
- `calques/` : PNG RGBA 256 × 256 conservant les positions originales.
- `elements/` : les mêmes pièces recadrées, sans changement d'échelle.
- `masques/` : masques de partition binaires, de dimensions 256 × 256.
- `manifest.json` : coordonnées, dimensions, points d'ancrage et ordre d'assemblage.
- `recomposition.png` : résultat exact de la reconstruction depuis les découpes.
- `planche-pieces.png` : vue d'ensemble des 13 pièces sur damier de présentation.

Les images de pièces n'ont pas de damier incorporé. Leur alpha vaut uniquement
0 ou 255. Les côtés gauche/droit sont ceux de l'image, pas ceux du personnage.

## Combiner les traits

Les six fenêtres de traits ont des dimensions fixes dans toutes les banques :

| Trait | Dimensions natives |
|---|---|
| Sourcil, gauche ou droit | 44 × 14 |
| Œil, gauche ou droit | 40 × 24 |
| Nez | 36 × 24 |
| Bouche | 52 × 24 |

Leurs positions sont adaptées au portrait source. Chaque entrée de manifeste
donne `source_box` (borne supérieure exclue), `paste_at`, `size`, `local_anchor`
et `source_anchor`. Les ancres sont des centres de fenêtres de copie, pas des
repères anatomiques mesurés de pupille ou de commissure.

Pour un échange, placer la découpe du **donneur** aux coordonnées `paste_at`
du **receveur** pour le même trait. Il ne faut pas coller le calque 256 × 256
du donneur tel quel, puisque les positions verticales diffèrent légèrement.
Pour reconstruire depuis le support, placer les six traits, donneurs ou
originaux, dans les six fenêtres du receveur.

Les fenêtres conservent la peau et les ombres autour des traits. Les différences
de carnation et certains raccords rectangulaires sont donc visibles lors des
échanges. Les cheveux, oreilles, contours et volumes du visage ont des masques
propres à chaque source : ils sont extraits, mais leur interchangeabilité sans
raccord n'est pas garantie. Le calque `raccords` conserve les pixels aux frontières
des pièces structurelles. Aucun morceau caché sous une coiffure n'a été inventé.

## Vérification et reproduction

Pour chaque banque, le script relit les PNG recadrés et recompose l'image à
partir des coordonnées enregistrées : **zéro pixel différent sur 65 536**,
pour chacun des dix portraits. Il vérifie aussi la partition sans trous ni
recouvrement, l'alpha binaire et la conservation RGB des pixels opaques.

Les six traits font l'objet de **600 contrôles d'échange** (10 donneurs ×
10 receveurs × 6 traits), vérifiant taille, opacité et égalité avec une copie
directe depuis le portrait donneur. Ces contrôles vérifient la géométrie et
la fidélité des copies ; ils ne garantissent pas l'harmonie des carnations.

Depuis le dépôt, avec Pillow et NumPy installés :

```sh
python3 tools/BuildPortraitBanks.py
```

Le script utilise les références livrées dans `../generations-v1/portraits/`.
Il reconstruit également l'archive `../element-banks-v1.zip`. L'archive contient
les banques, leurs références 256 × 256 et leurs manifestes ; elle peut être
consultée indépendamment du dépôt.
