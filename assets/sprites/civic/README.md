# Sprites des services publics

## Stade

Le stade (`stadium`, 4 × 4 cases) utilise `stadium-v1.png`, créé avec l’outil intégré `image_gen`. Le prompt complet est conservé dans `stadium-v1.prompt.txt`. `PrepareCivicSprites.java` recadre le canal alpha et calibre les coins latéraux du terrain (y = 501 dans la source) pour produire `client/src/main/resources/city/client/civic/stadium.png`, en 512 × 295 pixels transparents. Relancer cette préparation reproduit la ressource utilisée par `BuildingSprites`.

L’emprise suit la projection 2:1 du jeu ; les tests `UtilitySpritesTest` vérifient les débordements et le rendu découpé aux quatre rotations. Les détails de cette illustration restent approximatifs : deux arêtes de toiture mesurées ont des pentes de −0,480 et −0,508, et un bandeau avant de +0,460, contre ±0,5 pour la grille. La calibration du terrain ne garantit pas l’alignement exact de tous les détails architecturaux.

Trois sprites transparents créés avec l’outil intégré `image_gen` : caserne de pompiers (`fire`), commissariat (`police`) et école (`school`). Les versions utilisées sont `fire-v5.png`, `school-v5.png` et `police-v2.png`. Les versions précédentes sont conservées pour historique. Les prompts sont conservés dans `prompts-v2.json`, `prompts-v3.json` et `prompts-v4.json`.

La caserne et l’école ont été redessinées à partir de [geometry-guide.png](geometry-guide.png), un gabarit dont les pieds des murs sont parallèles aux bords du terrain. Le gabarit se reproduit avec `java -Djava.awt.headless=true tools/CivicGuide.java`. Les variantes v3 utilisent ce gabarit ; les variantes v4 appliquent une retouche ciblée des façades. Les toits plats facilitent la lecture de leurs axes. Le dessin généré reste une approximation artistique du gabarit : le test du contour du terrain ne constitue pas une mesure du parallélisme des murs.

Depuis la racine du dépôt, lancer `java -Djava.awt.headless=true tools/PrepareCivicSprites.java` pour reproduire les ressources dans `client/src/main/resources/city/client/civic/`. Cette préparation recadre les marges transparentes et réduit la largeur à 512 pixels en conservant le canal alpha. La hauteur est calibrée à partir des coins latéraux mesurés dans chaque original : la distance verticale entre ces coins et la pointe avant vaut exactement un quart de la largeur, conformément à la projection 2:1. Cette normalisation concerne toute l’image et ne découpe pas la cour ou le bâtiment. Les coordonnées de calibration sont enregistrées dans le script.

`BuildingSprites` partage le chargement et le placement avec les installations électriques et hydrauliques. La façade principale et ses portes regardent vers le sud-ouest (bas à gauche du sprite). Les bases sont des losanges réguliers et les aménagements restent dans leur emprise. Chaque bâtiment est dessiné une seule fois sur son emprise du catalogue, en surface. Comme les autres sprites, son illustration reste orientée vers l’écran lors des rotations ; les calques analytiques conservent leur rendu symbolique.

`./gradlew :client:test` vérifie notamment que les pixels opaques restent au-dessus des deux bords avant de l’emprise (tolérance de deux pixels source pour l’anticrénelage), la transparence et le rendu complet ou découpé pour les quatre rotations. La galerie est produite dans `client/build/previews/utility-sprites.png` et inclut les trois services publics.

## Correction géométrique v5

Après les retouches générées, `python3 tools/CorrectCivicFacades.py` (Pillow et NumPy) corrige les détails des façades gauches à partir des sources v4. Les traverses de fenêtres sont mesurées dans les pixels et redressées vers la pente du soubassement (environ 0,465 dans ces sources). Les raccords préservent la maçonnerie environnante. Les bandeaux exposés de l’école et ses marches sont reconstruits avec des arêtes parallèles ; les contremarches restent verticales. Exécuter ensuite `java -Djava.awt.headless=true tools/PrepareCivicSprites.java`.

`python3 tools/CheckCivicFacades.py` mesure les traverses dans les images corrigées et vérifie la conservation du canal alpha et de la façade droite. Ce contrôle complète le test d’emprise du client ; il ne prétend pas vérifier automatiquement tous les détails architecturaux.

## Aéroport

`airport` (6 × 6 cases) utilise `airport-geometry-v1.png`, réduit uniformément en `client/src/main/resources/city/client/civic/airport.png` (512 × 256, RGBA). La texture `textures/airport-front-v1.png` a été créée avec l’outil intégré `image_gen` ; son prompt complet est dans `airport-v1.prompt.txt`.

`BuildAirportSprite.java` contient un rasteriseur autonome issu de `BuildMunicipalSprites.java` : `x = 256 + u - v`, `y = 220 + (u + v) / 2 - z`. Piste, taxiway, marquages, terminal, hangar, tour et avions sont construits dans les mêmes coordonnées. Les arêtes parallèles aux axes du terrain ont exactement des pentes de ±0,5 avant rastérisation ; les verticales restent verticales. La texture de façade est une illustration orthographique appliquée sur ces volumes. Les ailes en flèche des avions ont leurs propres directions dans ce même espace.

Reproduction depuis la racine :

```sh
javac -d /tmp/sc2000-airport tools/BuildAirportSprite.java
java -Djava.awt.headless=true -cp /tmp/sc2000-airport BuildAirportSprite
./gradlew :client:test :client:ktlintCheck --offline
```

Le sprite est enregistré dans `BuildingSprites`, donc les tests existants de transparence, d’emprise et d’égalité des rendus complets/découpés aux quatre rotations le couvrent. Comme les autres bâtiments illustrés, il conserve son orientation à l’écran lorsque la carte tourne.

## Bâtiments municipaux retenus

Les quatre fichiers fournis et approuvés par l’utilisateur sont conservés sans modification :

| Bâtiment | Source approuvée | Ressource du client | Emprise |
| --- | --- | --- | --- |
| Maison du maire | `mayor-approved-v1.png` | `city/client/civic/mayor.png` | 2 × 2 |
| Hôtel de ville | `cityhall-approved-v1.png` | `city/client/civic/cityhall.png` | 3 × 3 |
| Hôpital | `hospital-approved-v1.png` | `city/client/civic/hospital.png` | 3 × 3 |
| Prison | `prison-approved-v1.png` | `city/client/civic/prison.png` | 4 × 4 |

`java -Djava.awt.headless=true tools/PrepareCivicSprites.java` reproduit ces ressources en PNG RGBA de 512 pixels de large. La préparation recadre les marges transparentes et applique une réduction **uniforme** aux deux axes. Elle ajoute au besoin une marge transparente inférieure pour contenir le contour réel de l’illustration dans l’emprise 2:1 du moteur. Elle ne retouche ni les bâtiments ni leurs terrains et n’applique aucun étirement vertical aux quatre images approuvées. Leurs entrées restent tournées vers le bas gauche.

Ces sources remplacent les reconstructions géométriques expérimentales. La préparation ne charge aucun rendu `*-geometry-v1.png` pour ces quatre bâtiments. L’hôtel de ville conserve son inspiration de Charleroi. La gare utilise la ressource approuvée séparément, préparée par `PrepareStationSprite.java`.

Les tests du client couvrent la transparence, les emprises et le rendu complet/découpé aux quatre rotations. La galerie `client/build/previews/utility-sprites.png` inclut les quatre bâtiments municipaux.
