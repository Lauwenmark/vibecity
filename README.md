# Shared City 2000

Jeu de construction urbaine coopératif en **Kotlin 2.4.21**, **Swing**, **Gradle 9.7.0**, Java 21.
Tous les joueurs construisent dans **la même ville**, simulée par un serveur autoritaire.
Les échanges réseau sont des objets JSON UTF-8 délimités par des retours à la ligne sur TCP.

Cette première version jouable réinterprète les mécanismes de SimCity 2000 avec des graphismes
procéduraux originaux. Elle ne reproduit pas intégralement le jeu commercial, ses données ou ses
formules internes. La [matrice de fidélité](docs/FIDELITY.md) décrit précisément les approximations
et les fonctionnalités absentes.

## Démarrage rapide

Prérequis : un **JDK 21** installé et un environnement graphique pour le client. Le Wrapper
télécharge Gradle ; la première compilation nécessite l’accès aux dépôts Maven et Gradle.

```sh
./gradlew build
./gradlew :client:run --args="--local"
```

Sous Windows, utiliser `gradlew.bat`. Le mode local démarre un serveur TCP dans le processus du
client, sur un port libre de l’interface loopback. Il ouvre `saves/local-city.json` si ce fichier
existe, sinon il crée un quartier de démonstration alimenté et desservi. La ville est sauvegardée
automatiquement chaque année simulée, sur demande et à la fermeture normale de la fenêtre.

Sans `--local`, le client affiche un accueil avec les boutons **Ville locale** et **Rejoindre…**.
Pour recommencer, arrêter le jeu puis déplacer la sauvegarde locale vers un autre nom.

## Mise à jour du relief et anciennes villes

Le relief utilise désormais des sommets partagés : les pentes sont des surfaces du modèle de jeu,
pas un raccord graphique entre plateformes. Les sauvegardes antérieures sont converties à l’ouverture.
Une copie du fichier original est conservée à côté de celui-ci, avec le suffixe `.pre-slopes.json`
(ou un suffixe numéroté si ce nom correspond déjà à une autre sauvegarde). Les constructions, réseaux,
finances et données des plugins sont conservés. Les secteurs développés sont convertis en plateformes
planes ; leur altitude et le relief voisin peuvent être relevés pour réconcilier les anciennes cases.
Les anciens barrages sont conservés, même s’ils étaient construits sur de l’eau plate.

Le protocole passe en **version 13** : redémarrer **le serveur et tous les clients** avec cette version.
Les états réseau sont compressés et les registres des habitants transmis par différences pour limiter
la latence sur réseau distant. Recompiler les plugins contre le nouveau `core` avant de les redéployer, car le modèle `City` a changé.

## Habitants et trafic

Chaque tick exécute une ou plusieurs journées civiles de déplacements individuels : travail ou éducation, courses,
loisirs, soins et repos. Routes, marche, trains, métro et vélos participent à la recherche d’itinéraires ;
la fréquentation horaire détermine la charge des voies et influence la planification suivante.
Les domiciles sont traités en parallèle par des coroutines ; chacun conserve ses chemins et ses
compteurs de trafic. L’exécution coordonne ensuite les capacités routières : attentes, bouchons,
détours et apprentissage individuel. Chaque habitant possède un nom et un carnet par jour de semaine.

Sélectionner une case habitée avec l’outil Inspection puis cliquer **Habitants / trajets…**, ou
double-cliquer le domicile. Cliquer un habitant dans la liste, ou le sélectionner et appuyer sur
Entrée, pour ouvrir sa fiche. Dans l’onglet **Déplacements**, choisir un besoin pour tracer son plan, son parcours réalisé ou ses itinéraires mémorisés
avec leurs scores ; déplacer le curseur horaire pour voir sa
position. Le calque **Trafic** montre la charge de cette heure, ou la pointe quotidienne quand
l’inspecteur est fermé. Après une construction, laisser passer un jour pour recalculer les plans.

Les calques Surface et Trafic montrent des voitures animées dont le nombre et la vitesse visuelle
reflètent la charge routière. La case **Voitures** permet de masquer cette animation, qui se fige
en pause. Il s’agit d’une illustration de densité ; le point de l’inspecteur reste la position de
l’habitant sélectionné.

Les [règles de mobilité](docs/MOBILITY.md) détaillent horaires, profils, capacités et limites :
calendrier quotidien, files par case, choix probabilistes et élagage mensuel des itinéraires.

## Éducation et cycle de vie

Les habitants vieillissent, fondent des couples, déménagent et ont des enfants. Ils fréquentent
l’école de 3 à 17 ans, peuvent poursuivre un cursus supérieur et prennent leur retraite à 65 ans.
Le NI (niveau d’instruction) et la CA (capacité d’apprentissage) sont individuels ; les résultats
universitaires dépendent des présences réellement exécutées, avec un redoublement possible par année.
Bibliothèques, musées et visites des établissements entretiennent les connaissances à l’âge adulte.

L’inspecteur **Habitants / trajets…** affiche NI, CA, cursus, résultats, diplôme, revenu,
liens familiaux, grossesse et instruction moyenne par tranche d’âge.
Les anciennes sauvegardes restent lisibles : ces données sont initialisées au premier jour simulé.
Les [règles du cycle de vie](docs/LIFE_CYCLE.md) détaillent les paramètres initiaux et leurs limites.

## Jouer à plusieurs

Sur la machine qui héberge la ville :

```sh
./gradlew :server:run --args="--host 0.0.0.0 --port 2000 --size 64 --seed 2000 --save saves/shared.json"
```

Pour tester les constructions de toutes les époques avec un budget initial de **1 000 000** :

```sh
./gradlew :server:run --args="--test-mode --save saves/test-city.json"
```

`--test-mode` ignore les limites d’année des constructions pour tous les joueurs, y compris
celles des plugins. Les coûts et les autres contraintes de construction restent applicables.
Le million est attribué uniquement à une nouvelle ville ; une sauvegarde existante conserve
sa trésorerie. Le mode n’est pas enregistré dans la sauvegarde : fournir l’option à chaque
lancement pour continuer à ignorer les limites d’année.

Sur chaque poste joueur :

```sh
./gradlew :client:run --args="--host 192.168.1.10 --port 2000 --name Alice"
```

Remplacer l’adresse par celle du serveur. Autoriser TCP/2000 dans le pare-feu du serveur.
Le serveur sans `--host` écoute uniquement sur `127.0.0.1`. Il accepte au plus **32 connexions**.
Les commandes sont traitées dans leur ordre d’arrivée : si deux maires construisent au même endroit,
la première transaction valide gagne, l’autre reçoit un refus et ne paie rien.
Tous les joueurs disposent des mêmes droits, y compris pour le budget, la vitesse et la sauvegarde.

Un jeton partagé facultatif se configure avec la variable d’environnement `CITY_TOKEN` sur le
serveur et les clients, ou dans la boîte **Rejoindre…**. Le protocole est volontairement en clair :
utiliser un réseau de confiance ou un tunnel SSH/VPN pour jouer à distance. Le jeton n’est pas un
chiffrement et ne fournit pas d’identités individuelles.

La taille de carte peut aller de 16 à 256 cases de côté ; 64 est la valeur par défaut. Une sauvegarde
existante prend priorité sur `--seed` et `--size` ; le serveur signale alors que ces options sont
ignorées. Pour créer une carte 256×256, choisir un chemin de sauvegarde qui n’existe pas encore :

```sh
./gradlew :server:run --args="--size 256 --save saves/new-256.json"
```

La syntaxe `--size=256` est également acceptée. Avec Gradle, les options du serveur doivent être
placées dans `--args`. Un serveur correspond à une ville. `Ctrl+C`
sauvegarde et arrête le serveur autonome ; l’arrêt forcé du processus ne le garantit pas.

## Construire sa ville

- Choisir un outil à gauche, cliquer ou glisser sur la carte ; survoler pour inspecter une case.
- Placer une centrale sur une emprise **plate et dégagée**. Les outils Élever/Abaisser modifient
  les quatre sommets d’une case et raccordent les pentes voisines. **Aplanir** utilise l’altitude
  cible du sélecteur (0–12). Le coût est de 5 $ par sommet effectivement modifié, y compris les
  raccords. Une opération touchant une zone, un bâtiment ou un réseau existant est refusée en entier.
  Les cases dont tous les sommets atteignent zéro deviennent de l’eau. Une démolition retire tout
  le bâtiment sélectionné.
- Zoner en résidentiel, commercial et industriel, avec densité légère ou dense. Une zone en pente
  reste sans développement jusqu’à son aplanissement ; retirer d’abord le zonage pour terrasser. Les zones et les
  bâtiments transmettent l’électricité ; les routes nécessitent une ligne électrique pour la franchir.
- Mettre des transports à trois cases au plus des zones. Les routes et rails sur l’eau deviennent
  des ponts, coûtant cinq fois le prix normal. Les routes et rails acceptent les rampes simples,
  orientées dans le sens de la montée ; les pentes composées exigent un aplanissement.
- L’outil **Eau / cascade** pose de l’eau de surface sur une case plane ou une rampe. Un barrage
  hydroélectrique neuf nécessite une rampe couverte d’eau. Ce modèle n’effectue pas de simulation
  hydraulique ni de propagation automatique du débit.
- Alimenter une pompe et poser des conduites **sous chaque zone à desservir**, reliées à la pompe.
  Une pompe voisine de l’eau a une capacité doublée. Le calque Eau montre les réseaux souterrains.
- Ajuster impôts et financement des services ; ajouter écoles, police, pompiers, parcs et hôpitaux.
  La pollution industrielle, le crime et la valeur foncière influencent la croissance.
- Surveiller la demande R/C/I, les recettes et dépenses mensuelles. Un emprunt apporte 10 000 $,
  avec 10 % d’intérêts annuels et un plafond de dette de 100 000 $. Le remboursement se fait par 10 000 $.
- Les boutons temps sélectionnent pause ou 1/2/3 jours civils par cycle. Le serveur attend par défaut
  1,5 seconde entre les calculs ; leur durée s’ajoute à cette attente. L’économie est calculée le
  premier jour de chaque mois. Les années commencent en 1900 ; les technologies apparaissent à
  leur date d’introduction dans le catalogue.
- Les incendies aléatoires et leur propagation sont activables. Les casernes alimentées et financées
  les combattent. Les catastrophes sont désactivées par défaut.

Les calques disponibles sont Surface, Zonage, Électricité, Eau/sous-sol, Pollution, Criminalité,
Valeur foncière et Trafic. Maintenir le bouton droit et glisser déplace la carte.
La molette (avec ou sans `Ctrl`) et les boutons `−`/`+` règlent le zoom de ×0,5 à ×16.
Les boutons `↶ 90°`/`↷ 90°` tournent la carte par quarts de tour ; les barres de
défilement déplacent la vue. Les erreurs de placement s’affichent dans la barre d’état.

## Distribution sans Gradle à l’exécution

```sh
./gradlew :client:installDist :server:installDist
client/build/install/client/bin/client --local
server/build/install/server/bin/server --host 0.0.0.0 --port 2000
```

Les archives ZIP/TAR créées par `build` se trouvent dans `client/build/distributions` et
`server/build/distributions`. Elles contiennent les bibliothèques mais **pas le JRE** : Java 21+
est requis sur les postes. Les chemins `saves/` et `plugins/` sont relatifs au répertoire de lancement.

## Étendre le jeu

L’API JVM `CityPlugin` fournit un registre de bâtiments remplaçables, des règles de développement
par case exécutées en parallèle et des systèmes globaux suspendables. L’état propre aux plugins
peut être conservé dans la sauvegarde avec `City.pluginData`.

```sh
./gradlew :example-plugin:jar
mkdir -p plugins
cp example-plugin/build/libs/example-plugin-0.1.0.jar plugins/
```

Redémarrer le serveur (ou le mode local). Le jardin communautaire apparaît automatiquement dans
la palette des clients, **sans installer le plugin sur leurs postes**. Voir le
[guide des plugins](docs/PLUGINS.md), qui décrit aussi leurs contrats de concurrence et de compatibilité.

## Architecture et validation

- [Architecture et modèle de simulation](docs/ARCHITECTURE.md)
- [Document de travail sur le modèle social et économique](docs/SOCIAL_SIMULATION_WORKING_PLAN.md)
- [Protocole TCP, commandes et erreurs](docs/PROTOCOL.md)
- [Règles et fidélité à SimCity 2000](docs/FIDELITY.md)
- [Plugins et personnalisation](docs/PLUGINS.md)

`./gradlew build` compile les quatre modules, produit les distributions et exécute les tests de
style Kotlin (ktlint), simulation, transactions, multijoueur sur vrais sockets TCP, persistance, plugin JAR et rendu Swing.
Les tests du cache vérifient aussi la sélection, les raccords graphiques et l’absence de reconstruction
de la scène au survol. Le test de rendu crée `client/build/previews/city.png` sans écran.

Pour vérifier une vraie fenêtre Swing avec connexion TCP et action de budget :

```sh
xvfb-run -a ./gradlew :client:uiSmoke
```

Un affichage X11 réel peut remplacer Xvfb. Le test produit `client/build/previews/application.png`.
Il ne touche pas aux sauvegardes utilisateur. Les tests automatiques ne constituent pas une mesure
de charge à 32 joueurs ni une validation graphique sur chaque système d’exploitation.

## Choix des versions

Kotlin 2.4.21 est la dernière version **stable** vérifiée le 7 novembre 2026 dans les
[notes officielles](https://kotlinlang.org/docs/releases.html). Gradle 9.7.0 correspond au maximum
[officiellement pris en charge](https://kotlinlang.org/docs/gradle-configure-project.html)
par ce plugin Kotlin. Les versions sont fixées dans le build, sans dépendance dynamique.

Formater le code : `./gradlew ktlintFormat`. Les conventions sont dans `.editorconfig`.
