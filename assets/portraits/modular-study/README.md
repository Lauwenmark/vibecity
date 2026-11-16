# Étude de portraits par copier-coller exact

Les deux fichiers joints de 1280 × 1280 sont d'abord réduits à 256 × 256 avec
Pillow `Resampling.NEAREST` (plus proche voisin). Ces deux images réduites
constituent les références de toutes les comparaisons. Aucune génération,
recoloration, interpolation, déformation ou retouche n'est ensuite appliquée.

## Livrables

- `planche-pieces.png` : 24 pièces, soit 12 par référence, présentées à leur
  position originale sur un canevas de 256 × 256. Le damier est uniquement un
  fond de présentation ; les fichiers des pièces sont transparents.
- `pieces/` : les 24 PNG RGBA séparés, avec alpha strictement égal à 0 ou 255.
- `reference-1-256.png`, `reference-2-256.png` : références réduites.
- `recomposition-1.png`, `recomposition-2.png` : images assemblées à partir
  des pièces seules, strictement identiques aux références réduites.
- `planche-recompositions.png` : comparaison côte à côte.
- `planche-essais.png` : deux témoins et dix combinaisons de traits.
- `essais/` : chaque essai en PNG 256 × 256.
- `manifest.json` : provenance, coordonnées, surfaces et recettes des essais.
- `verification.json` : résultat des contrôles automatiques.

## Découpage et compatibilité

Chaque pixel de chaque référence appartient à exactement une pièce. Les fonds
sont isolés par connexité depuis les bords dans les teintes claires neutres.
Les limites des pièces structurelles sont des masques polygonaux explicites.
Ces masques découpent des pixels existants : ils ne dessinent aucun pixel.

Les sourcils, yeux, nez et bouches utilisent des fenêtres rectangulaires de
même taille entre donneurs. Ils incluent leur peau de raccord. Les essais
échangent ces fenêtres par translation entière, sans mise à l'échelle. Les
mentions gauche/droite désignent les côtés de l'image.

Les pièces « chevelure et raccords », visage, oreilles et cou permettent la
reconstruction exacte de leur référence, mais leurs frontières sont propres
à celle-ci. Elles ne constituent pas des éléments structurels librement
interchangeables. La chevelure conserve des pixels de raccord ; le support
du visage peut conserver des fragments de frange. Aucun front ni contour
masqué dans les sources n'a été inventé. Les essais conservent donc ces
éléments structurels ensemble et combinent les six fenêtres de traits.

Des différences de carnation et d'ombre sont visibles aux limites de certains
collages. Elles sont conservées conformément à la contrainte de copie exacte.
Ce livrable est une étude d'assemblage fidèle, pas un atlas aux raccords retouchés.

## Reproduction et vérification

Depuis la racine du dépôt, avec Pillow et NumPy disponibles :

```sh
python3 tools/BuildPortraitStudy.py
```

Les fichiers joints d'origine doivent être présents aux chemins indiqués dans
le script. Celui-ci vérifie la partition sans trous ni recouvrements, l'opacité
finale, l'identité RGB des deux recompositions et l'égalité de chaque essai avec
un copier-coller indépendant depuis les références réduites.

Résultat : **0 pixel différent sur 65 536 pour chacune des deux recompositions**.
Les 12 essais sont également vérifiés. Les dimensions des portraits sur les
planches sont natives ; seul leur affichage dans une interface peut les réduire.
