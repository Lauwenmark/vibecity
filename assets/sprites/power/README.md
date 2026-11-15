# Poteaux de distribution belges

`proposal-v1.png` conserve le modèle approuvé : béton patiné, petite traverse métallique et isolateurs bruns, sans pylône haute tension ni transformateur américain.

Le sprite transparent utilisé par le client est `client/src/main/resources/city/client/power/concrete-pole.png`, généré avec l'outil intégré `image_gen`. Le fichier original est conservé sans transformation. `PowerLineSprites` sélectionne son rectangle utile `(372, 26, 280, 1483)` et le dessine à 12 pixels de haut au zoom 1. Les câbles sont des courbes indépendantes du sprite, raccordées entre les hauteurs des centres des cases. Chaque case dessine une demi-portée pour préserver les raccords, les rotations et le rendu découpé. Les calques analytiques gardent leur représentation symbolique.

Prompt exact du sprite (référence : `proposal-v1.png`) :

> Extract/recreate ONLY the large isolated concrete electricity pole at the LEFT of the reference proposal as one production game sprite on a genuinely transparent background. Preserve approved design: slender slightly tapered rectangular warm gray concrete shaft, subtle painted patina, small short dark metal crossarm with two triangular supports and two brown ceramic insulators. Remove ALL wires, house, street, ground, shadow, text and other objects. Single pole centered, full shaft and base visible with tight reasonable margins. Orthographic dimetric game art, vertical shaft, same soft painted style. Pole is slender, crossarm width about 18 percent of total pole height. Transparent background. No platform. This image is for a small city building game sprite.

Validation : `./gradlew :client:test :client:ktlintCheck`. Le test `PowerLineSpritesTest` produit `client/build/previews/power-lines.png` et compare le rendu complet au rendu découpé sur un réseau en pente, aux quatre rotations et à trois zooms.

Les poteaux sont composés après le décor, avec les pixels des sprites des bâtiments et les formes simples des arbres au premier plan comme masques. La comparaison des deux coordonnées de leur emprise projetée évite qu’un grand bâtiment masque un poteau situé devant son bord latéral.

Sur une case de route ou de rail, deux supports se font face de part et d’autre du corridor, à 8 % et 92 % de sa largeur. Leur portée est perpendiculaire à l’axe de la route ou du rail. Aux virages et croisements, la portée est décalée vers un bord libre, sans diagonale entre coins opposés. Une portée interne relie les deux supports ; les cases voisines partagent la portée la plus courte entre leurs supports. Un câble de branchement simple relie le support le plus proche à chaque bâtiment voisin alimenté (y compris les zones occupées). Les changements d’alimentation invalident le cache de surface des bâtiments pour faire apparaître ou disparaître ces branchements.

Le rendu prépare une seule petite image aux limites du dessin réel. Les bâtiments au premier plan effacent les pixels de cette image par composition alpha (`DstOut`), sans construire ni soustraire leurs silhouettes vectorielles. `PowerLinePerformanceTest` fournit une mesure reproductible (sans seuil dépendant de la machine) sur un réseau dense de 32 × 32 cases.
