# Gare avec esplanade

`station-esplanade-v1.png` est le modèle accepté le 14 novembre 2026, créé
avec l’outil intégré `image_gen`. Le quai arrière et son auvent sont supprimés,
la gare est reculée et l’avant accueille une esplanade fleurie et une place de bus.
La marquise de l’entrée est conservée.

Reproduction de la ressource du jeu :

```sh
java -Djava.awt.headless=true tools/PrepareStationSprite.java
```

Cette commande produit `client/src/main/resources/city/client/civic/station.png`
en RGBA, largeur 512 pixels, pour l’emprise 3 × 3 de `station`. Elle recadre
l’alpha et calibre le terrain à la projection 2:1, avec huit pixels transparents
sous l’illustration pour contenir l’épaisseur irrégulière de la bordure. Les façades restent une
approximation artistique : leur parallélisme exact n’est pas garanti.
Après toute préparation des anciens sprites municipaux, relancer cette commande
pour conserver le modèle accepté de la gare.

Prompt d’aménagement : conserver le bâtiment de référence ; supprimer le quai
arrière et son auvent, garder la marquise d’entrée ; reculer la gare presque
contre le bord supérieur droit ; aménager devant une esplanade fleurie et une
seule place de bus vide, marquée BUS, accessible depuis le bord de la case.

Prompt final de cadrage :

> Keep this exact station and plaza sprite unchanged. ONLY fix framing: zoom out enough to add clear transparent margin around ALL sides, especially above the tallest rear roof finial which is currently clipped by top image boundary. Reconstruct the complete small ornamental finial at that roof peak matching the Victorian design. Preserve exact building, plaza, empty BUS parking bay, flower planters, trees, entrance canopy and layout. No rear platform or rear canopy. Entire sprite including tallest finial and all tile corners fully inside canvas with at least 5 percent margin. Transparent background.
