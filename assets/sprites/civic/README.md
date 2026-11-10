# Sprites des services publics

Trois sprites transparents créés avec l’outil intégré `image_gen` : caserne de pompiers (`fire`), commissariat (`police`) et école (`school`). Les versions utilisées sont `fire-v5.png`, `school-v5.png` et `police-v2.png`. Les versions précédentes sont conservées pour historique. Les prompts sont conservés dans `prompts-v2.json`, `prompts-v3.json` et `prompts-v4.json`.

La caserne et l’école ont été redessinées à partir de [geometry-guide.png](geometry-guide.png), un gabarit dont les pieds des murs sont parallèles aux bords du terrain. Le gabarit se reproduit avec `java -Djava.awt.headless=true tools/CivicGuide.java`. Les variantes v3 utilisent ce gabarit ; les variantes v4 appliquent une retouche ciblée des façades. Les toits plats facilitent la lecture de leurs axes. Le dessin généré reste une approximation artistique du gabarit : le test du contour du terrain ne constitue pas une mesure du parallélisme des murs.

Depuis la racine du dépôt, lancer `java -Djava.awt.headless=true tools/PrepareCivicSprites.java` pour reproduire les ressources dans `client/src/main/resources/city/client/civic/`. Cette préparation recadre les marges transparentes et réduit la largeur à 512 pixels en conservant le canal alpha. La hauteur est calibrée à partir des coins latéraux mesurés dans chaque original : la distance verticale entre ces coins et la pointe avant vaut exactement un quart de la largeur, conformément à la projection 2:1. Cette normalisation concerne toute l’image et ne découpe pas la cour ou le bâtiment. Les coordonnées de calibration sont enregistrées dans le script.

`BuildingSprites` partage le chargement et le placement avec les installations électriques et hydrauliques. La façade principale et ses portes regardent vers le sud-ouest (bas à gauche du sprite). Les bases sont des losanges réguliers et les aménagements restent dans leur emprise. Chaque bâtiment est dessiné une seule fois sur son emprise de 3 × 3 cases, en surface. Comme les autres sprites, son illustration reste orientée vers l’écran lors des rotations ; les calques analytiques conservent leur rendu symbolique.

`./gradlew :client:test` vérifie notamment que les pixels opaques restent au-dessus des deux bords avant de l’emprise (tolérance de deux pixels source pour l’anticrénelage), la transparence et le rendu complet ou découpé pour les quatre rotations. La galerie est produite dans `client/build/previews/utility-sprites.png` et inclut les trois services publics.

## Correction géométrique v5

Après les retouches générées, `python3 tools/CorrectCivicFacades.py` (Pillow et NumPy) corrige les détails des façades gauches à partir des sources v4. Les traverses de fenêtres sont mesurées dans les pixels et redressées vers la pente du soubassement (environ 0,465 dans ces sources). Les raccords préservent la maçonnerie environnante. Les bandeaux exposés de l’école et ses marches sont reconstruits avec des arêtes parallèles ; les contremarches restent verticales. Exécuter ensuite `java -Djava.awt.headless=true tools/PrepareCivicSprites.java`.

`python3 tools/CheckCivicFacades.py` mesure les traverses dans les images corrigées et vérifie la conservation du canal alpha et de la façade droite. Ce contrôle complète le test d’emprise du client ; il ne prétend pas vérifier automatiquement tous les détails architecturaux.
