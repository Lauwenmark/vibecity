# Variantes des tunnels de métro — v2

16 sources PNG générées avec l’outil intégré `image_gen`, avec le premier tunnel comme référence de style. Tous les fichiers possèdent un canal alpha. Les prompts exacts sont conservés dans [tunnel-variants-v2.json](tunnel-variants-v2.json).

## Convention des raccordements

Les directions désignent les côtés de la case **à l’écran** : NE = haut droite, SE = bas droite, SW = bas gauche, NW = haut gauche. Bits : NE=1, SE=2, SW=4, NW=8. Le masque est la somme des côtés ouverts.

À rotation zéro dans CityProjection : voisin (x,y-1) → NE, (x+1,y) → SE, (x,y+1) → SW, (x-1,y) → NW. Une rotation caméra de 90° décale chaque bit d’une position : `((mask << 1) | (mask >> 3)) & 15`.

## État

Les originaux ci-dessous sont conservés comme sources. Le jeu utilise désormais des dérivés normalisés dans `client/src/main/resources/city/client/metro/` : empreinte commune de 256 × 128 pixels, suppression des marges et du halo diffus, voies reconstruites à écartement constant et raccords identiques aux sorties. Les 16 topologies sont représentées : 1 isolé, 4 extrémités, 2 lignes droites, 4 virages, 4 T et 1 croisement.

Le chargeur `MetroSprites` choisit le masque après rotation et applique la texture aux deux triangles du terrain. Conduites d’eau et marqueur de station sont dessinés au-dessus. Le croisement à quatre branches reste une représentation simplifiée des aiguillages. Voir [README.md](README.md) pour la régénération et les tests.

## Galerie

### 00 — isolated — aucun voisin

![isolated 0](tunnel-00-isolated-v2.png)

### 01 — end — NE

![end 1](tunnel-01-end-v2.png)

### 02 — end — SE

![end 2](tunnel-02-end-v2.png)

### 03 — corner — NE / SE

![corner 3](tunnel-03-corner-v2.png)

### 04 — end — SW

![end 4](tunnel-04-end-v2.png)

### 05 — straight — NE / SW

![straight 5](tunnel-05-straight-v2.png)

### 06 — corner — SE / SW

![corner 6](tunnel-06-corner-v2.png)

### 07 — tee — NE / SE / SW

![tee 7](tunnel-07-tee-v2.png)

### 08 — end — NW

![end 8](tunnel-08-end-v2.png)

### 09 — corner — NE / NW

![corner 9](tunnel-09-corner-v2.png)

### 10 — straight — SE / NW

![straight 10](tunnel-10-straight-v2.png)

### 11 — tee — NE / SE / NW

![tee 11](tunnel-11-tee-v2.png)

### 12 — corner — SW / NW

![corner 12](tunnel-12-corner-v2.png)

### 13 — tee — NE / SW / NW

![tee 13](tunnel-13-tee-v2.png)

### 14 — tee — SE / SW / NW

![tee 14](tunnel-14-tee-v2.png)

### 15 — cross — NE / SE / SW / NW

![cross 15](tunnel-15-cross-v2.png)

