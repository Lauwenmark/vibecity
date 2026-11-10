# Sprites des services publics

Trois sprites transparents créés avec l’outil intégré `image_gen` : caserne de pompiers (`fire`), commissariat (`police`) et école (`school`). Les originaux sont conservés dans les fichiers `*-v1.png` et les prompts exacts dans [prompts.json](prompts.json).

Depuis la racine du dépôt, lancer `java -Djava.awt.headless=true tools/PrepareCivicSprites.java` pour reproduire les ressources dans `client/src/main/resources/city/client/civic/`. Cette préparation recadre les marges transparentes et réduit la largeur à 512 pixels en conservant les proportions et le canal alpha.

`BuildingSprites` partage le chargement et le placement avec les installations électriques et hydrauliques. Chaque bâtiment est dessiné une seule fois sur son emprise de 3 × 3 cases, en surface. Comme les autres sprites, son illustration reste orientée vers l’écran lors des rotations ; les calques analytiques conservent leur rendu symbolique.

`./gradlew :client:test` vérifie notamment la transparence et le rendu complet ou découpé pour les quatre rotations. La galerie est produite dans `client/build/previews/utility-sprites.png` et inclut les trois services publics.
