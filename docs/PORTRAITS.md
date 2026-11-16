# Portraits persistants et banque graphique v1

Le portrait d'un habitant est composé côté client à partir de son
`Citizen.portraitCode` et de sa tranche d'âge. Il est affiché en 256 × 256 dans
la fiche et en 64 × 64 dans la liste, avec interpolation au plus proche voisin.

## Banque

La banque possède trois familles de peau, chacune avec ses propres portraits
sources et ses propres éléments : claire (`family-0`), intermédiaire
(`family-1`) et foncée (`family-2`). Toutes couvrent les deux sexes et six âges.
Une composition choisit une seule famille ; elle ne peut pas prendre un nez
dans une famille et le reste du visage dans une autre.

| Tranche | Âge dans le jeu | Dossier |
|---|---|---|
| Enfant | 0–12 ans | `child` |
| Adolescent | 13–19 ans | `teen` |
| Adulte | 20–44 ans | `adult` |
| Âge mûr | 45–64 ans | `mature` |
| Âgé | 65–79 ans | `senior` |
| Vieillard | 80 ans et plus | `elder` |

La version enfant est également utilisée pour les nourrissons : cette première
banque ne comporte pas encore de tranche graphique distincte pour les bébés.

Les dix modèles initiaux sont déclinés à tous les âges : cinq féminins et cinq
masculins. Leurs indices restent fixes dans toutes les familles et toutes les
tranches. Par exemple, la variante masculine `1` conserve les boucles rousses
à travers les âges ; elles grisonnent progressivement. Les traits suivent la
même convention : choisir une variante ne choisit pas un âge.

Sept groupes de pièces sont sélectionnés indépendamment :

- `frame` : coiffure et cadre du portrait, avec les contours extérieurs,
  le fond et le cou/buste ; ces raccords restent ensemble ;
- `face` : volume central du visage ;
- `ears` : les détails internes des deux oreilles, leurs contours restant avec le cadre ;
- `brows` : les deux sourcils ;
- `eyes` : les deux yeux ;
- `nose` : le nez ;
- `mouth` : la bouche.

Cela représente **180 portraits sources préparés** et **1 260 calques**
(3 familles × 2 sexes × 6 âges × 5 variantes × 7 groupes). Les paires d'yeux
et de sourcils sont conservées ensemble pour préserver la cohérence du regard.
Tous les calques font 256 × 256 et partagent une partition binaire commune :
aucun assemblage ne peut produire de trou ou de double couverture. Les pixels
opaques sont copiés exactement depuis les portraits préparés.

Les trois familles évitent les mélanges de carnations incompatibles. Elles
ne constituent pas une garantie artistique d'absence de toute différence
d'ombre entre deux pièces : les sources ont des volumes et des rides distincts.

## Identifiant persistant

Le format v1 de `portraitCode` utilise les bits suivants :

| Bits | Contenu |
|---|---|
| 0–1 | Famille de peau, 0 à 2 |
| 2 | Sexe, féminin 0 / masculin 1 |
| 3–23 | Sept variantes sur 3 bits chacune, dans l'ordre ci-dessus, valeurs 0 à 4 |
| 24–27 | Version de banque, 1 |
| 28 et au-delà | Réservés, obligatoirement à zéro |

`PortraitCodes.create` utilise un tirage déterministe fondé sur l'identifiant
de l'habitant et des sels fixes. L'âge, le domicile, le nom et l'ordre des
coroutines n'interviennent pas. Les arrivées et les naissances reçoivent ce code
à la construction de `Citizen`. Les copies utilisées par les anniversaires,
les déplacements et les changements de ménage conservent le champ.

Le champ absent d'une ancienne sauvegarde reçoit la même valeur déterministe
à chaque décodage. Le code est sauvegardé au prochain enregistrement normal.
Le sexe du cycle de vie fait autorité pour les anciens habitants dont le cycle
de vie était absent ; la première initialisation met à jour le bit correspondant.
Le client respecte également le sexe explicite du cycle de vie, y compris pour
les habitants créés manuellement par un plugin.

Ne pas renuméroter les variantes ni changer le découpage des bits de la v1.
Une extension incompatible doit introduire une version et une migration.
Le protocole réseau passe à **15** ; serveur, clients et plugins utilisant
les modèles partagés doivent être recompilés ensemble.

## Affichage et coût

`ResidentPortraits` compose les sept PNG sans recherche de chemin ni appel de
génération d'image. Les lectures de ressources et assemblages sont exécutés
sur un worker dédié, hors du thread Swing. La file est bornée à 64 demandes,
les caches à 128 portraits et 128 calques. Les demandes identiques sont regroupées.
Une fiche ouverte reçoit une nouvelle clé d'affichage quand l'âge change de
tranche ; le code de l'habitant reste identique.

## Sources, génération et contrôle

Les portraits d'origine et les nouvelles séries d'âge sont conservés sous
[`assets/portraits`](../assets/portraits). La génération utilise l'outil intégré
`image_gen`. Les prompts et les fichiers d'origine sont conservés dans
`assets/portraits/bank-v1/sources.json` et `family-sources.json`.

Les trois familles sont issues de portraits complets distincts, pas d'une
recoloration partielle des nez ou des joues. Le script
[`tools/BuildGamePortraits.py`](../tools/BuildGamePortraits.py) découpe les séries,
les réduit au plus proche voisin, aligne leurs lignes de traits par réindexation
entière des pixels et extrait les calques. L'extraction ne repeint aucun pixel.
Les ressources finales se trouvent dans
`client/src/main/resources/city/client/portraits/bank-v1`.

```sh
python3 tools/BuildGamePortraits.py
./gradlew :core:test :server:test :client:test
```

Le générateur vérifie la recomposition exacte des 180 portraits préparés et
la couverture complète des assemblages croisés. Les tests Kotlin vérifient
toutes les ressources, les masques communs, la persistance, la migration des
anciennes sauvegardes, les deltas réseau et la conservation du code avec l'âge.
Les planches `family-*-all-ages.png` et `mixed-aging-preview.png` servent au
contrôle visuel ; le test du client produit également
`client/build/reports/portraits/runtime-aging.png` avec le compositeur du jeu.
