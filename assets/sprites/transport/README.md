# Routes et voies ferrées

Sprites modulaires intégrés au calque de surface du client : 16 variantes de route, 16 variantes de voie ferrée et 16 superpositions de rails pour les cases qui portent les deux réseaux. Les calques analytiques gardent leur représentation symbolique.

## Matières peintes v2

Le client utilise les matières `asphalt-v2.png` et `ballast-v2.png`, générées avec l’outil intégré `image_gen` en prenant la centrale à charbon et l’école comme références de style. L’asphalte gris ardoise présente seulement de douces variations de couleur ; le ballast taupe ne dessine plus chaque gravillon. Les joints des trottoirs sont plus discrets et les traverses légèrement renforcées. La géométrie, les largeurs, l’écartement des rails, les masques et la projection restent identiques.

Les prompts exacts sont conservés dans [prompts-v2.json](prompts-v2.json). [comparison-v2.png](comparison-v2.png) compare la première tentative granuleuse (rangée supérieure) et la version peinte (rangée inférieure). Les matières v1 et [gallery-v1.png](gallery-v1.png) sont conservées comme références de la première tentative.

## Perspective et raccordements

Chaque sprite fait **256 × 128 pixels**. Les sommets de la case sont `(128,0)`, `(256,64)`, `(128,128)`, `(0,64)`. Les prompts de la première tentative sont conservés dans [prompts.json](prompts.json) ; ceux des matières actuellement utilisées sont dans [prompts-v2.json](prompts-v2.json).

La géométrie n’est pas déduite d’une illustration en perspective : trottoirs, marquages, traverses et rails sont construits dans un plan carré, puis projetés par `x = 128 + 128u − 128v`, `y = 64u + 64v`. Les axes rectilignes ont donc exactement les pentes ±½ de la carte. Les virages sont des courbes dans ce même plan. Le résultat reste à plat : pas de soubassement flottant, d’ombre extérieure ni de terrain ajouté.

Masques à l’écran : NE=1, SE=2, SW=4, NW=8. Les 16 masques couvrent l’isolé, les quatre extrémités, les deux lignes droites, les quatre virages, les quatre T et le croisement. La rotation de la caméra change le masque. Les connexions interdites par la pente du terrain ne sont pas ouvertes. Les rails en T représentent des aiguillages simplifiés ; le croisement à quatre branches est à niveau.

Les ouvertures partagent une géométrie commune, une largeur de chaussée constante et un écartement de rails constant. Des bandes de raccordement normalisent les rails et marquages en bordure de chaque case. Les textures sont rendues périodiques par miroir. Les cases route–rail superposent des rails encastrés sans masquer l’asphalte avec du ballast.

## Régénération et vérification

Depuis la racine du dépôt :

```sh
java -Djava.awt.headless=true tools/PrepareTransportSprites.java
./gradlew :client:test :client:ktlintCheck :client:installDist
```

Les 48 PNG utilisés par le jeu sont dans `client/src/main/resources/city/client/transport/`. Le générateur produit aussi [gallery.png](gallery.png). Aucun appel de génération d’image n’est nécessaire pour reconstruire ces ressources à partir des deux matières conservées.

`TransportSprites` charge les ressources une fois et les applique aux deux triangles de la case, comme le terrain et les tunnels de métro. Un cache borné à 128 images projetées évite les recalculs et rend l’échantillonnage indépendant des frontières des blocs de carte. Les voitures animées restent dessinées au-dessus des routes.

Les tests vérifient les 16 masques, les limites de carte, les restrictions de pente, les quatre rotations, l’alpha hors du losange, les raccords communs, la visibilité des deux réseaux aux passages à niveau et le rendu découpé. L’aperçu dans le moteur est écrit dans `client/build/previews/transport-sprites.png`.
