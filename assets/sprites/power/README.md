# Poteaux de distribution belges

`proposal-v1.png` conserve le modèle approuvé : béton patiné, petite traverse métallique et isolateurs bruns, sans pylône haute tension ni transformateur américain.

Le sprite transparent utilisé par le client est `client/src/main/resources/city/client/power/concrete-pole.png`, généré avec l'outil intégré `image_gen`. Le fichier original est conservé sans transformation. `PowerLineSprites` sélectionne son rectangle utile `(372, 26, 280, 1483)` et le dessine à 30 pixels de haut au zoom 1. Les câbles sont des courbes indépendantes du sprite, raccordées entre les hauteurs des centres des cases. Chaque case dessine une demi-portée pour préserver les raccords, les rotations et le rendu découpé. Les calques analytiques gardent leur représentation symbolique.

Prompt exact du sprite (référence : `proposal-v1.png`) :

> Extract/recreate ONLY the large isolated concrete electricity pole at the LEFT of the reference proposal as one production game sprite on a genuinely transparent background. Preserve approved design: slender slightly tapered rectangular warm gray concrete shaft, subtle painted patina, small short dark metal crossarm with two triangular supports and two brown ceramic insulators. Remove ALL wires, house, street, ground, shadow, text and other objects. Single pole centered, full shaft and base visible with tight reasonable margins. Orthographic dimetric game art, vertical shaft, same soft painted style. Pole is slender, crossarm width about 18 percent of total pole height. Transparent background. No platform. This image is for a small city building game sprite.

Validation : `./gradlew :client:test :client:ktlintCheck`. Le test `PowerLineSpritesTest` produit `client/build/previews/power-lines.png` et compare le rendu complet au rendu découpé sur un réseau en pente, aux quatre rotations et à trois zooms.
