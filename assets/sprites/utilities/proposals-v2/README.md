# Centrales — propositions v2

Neuf nouvelles propositions transparentes générées avec l’outil intégré `image_gen`, à partir du gabarit `../../civic/geometry-guide.png`. Les prompts exacts figurent dans `prompts.json`.

La cible est la projection dimétrique 2:1 de `CityProjection` : axes du sol de pente ±0,5, verticales verticales, vue orthographique symétrique. Les volumes et façades ont été redessinés, plutôt que de simplement déformer les anciens sprites. Les résultats restent des approximations artistiques ; aucune conformité géométrique exacte de tous les détails n’est revendiquée.

`gallery.png` présente les neuf illustrations sur fond neutre. Les PNG individuels conservent leur transparence et leurs marges originales. Le client utilise maintenant ces neuf versions, préparées avec `java -Djava.awt.headless=true tools/PrepareUtilitySprites.java`. Les coordonnées mesurées des coins latéraux sont enregistrées dans cet outil : la hauteur est calibrée pour placer la pointe avant 128 pixels sous ces coins à une largeur de 512 pixels, comme pour les bâtiments civiques.

Les versions v1 sont conservées comme sources historiques ; les neuf ressources électriques du jeu sont remplacées. La station de pompage, qui ne produit pas d’électricité, ne fait pas partie de cette série et conserve sa ressource actuelle.
