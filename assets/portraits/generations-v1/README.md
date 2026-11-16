# Dix portraits en pixel art

Série réalisée avec l'outil intégré `image_gen`, puis réduite en PNG RGB
256 × 256 au plus proche voisin. Les fichiers générés intacts sont conservés
dans `originals/`, les livrables 256 × 256 dans `portraits/`.

`prompts.json` contient les dix prompts complets et la provenance des images.
`planche-portraits.png` présente les personnages féminins sur la première ligne
et masculins sur la seconde, de l'enfance au grand âge.

Les cinq classes graphiques sont : enfant (environ 8 ans), adolescent (16 ans),
adulte (30 ans), âge mûr (52–55 ans), âgé (80–82 ans). Un portrait est disponible
pour chaque combinaison de classe et de sexe.

Les positions demandées au générateur étaient communes, mais les positions
obtenues diffèrent de quelques pixels. Les banques associées utilisent les
positions constatées dans les images, consignées dans leurs manifestes.

Les extractions exactes se trouvent dans `../element-banks-v1/`.
