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

Le protocole passe en **version 3** : redémarrer **le serveur et tous les clients** avec cette version.
Recompiler les plugins contre le nouveau `core` avant de les redéployer, car le modèle `City` a changé.

## Habitants et trafic

Chaque mois simule une journée de déplacements individuels : travail ou éducation, courses,
loisirs, soins et repos. Routes, marche, trains et métro participent à la recherche d’itinéraires ;
la fréquentation horaire détermine la charge des voies et influence la planification suivante.

Cliquer **Habitants / trajets…** ou double-cliquer un domicile avec l’outil Inspection. Choisir
un habitant et un besoin pour tracer son parcours ; déplacer le curseur horaire pour voir sa
position. Le calque **Trafic** montre la charge de cette heure, ou la pointe quotidienne quand
l’inspecteur est fermé. Après une construction, laisser passer un mois pour recalculer les plans.

Les [règles de mobilité](docs/MOBILITY.md) détaillent horaires, profils, capacités et limites :
journée représentative, itinéraires partagés, pas encore de files de véhicules aux carrefours.

## Jouer à plusieurs

Sur la machine qui héberge la ville :

```sh
./gradlew :server:run --args="--host 0.0.0.0 --port 2000 --size 64 --seed 2000 --save saves/shared.json"
```

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
existante prend priorité sur `--seed` et `--size`. Un serveur correspond à une ville. `Ctrl+C`
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
- Les boutons temps sélectionnent pause ou 1/2/3 mois par cycle de 1,5 seconde. Les années commencent
  en 1900 ; les technologies apparaissent à leur date d’introduction dans le catalogue.
- Les incendies aléatoires et leur propagation sont activables. Les casernes alimentées et financées
  les combattent. Les catastrophes sont désactivées par défaut.

Les calques disponibles sont Surface, Zonage, Électricité, Eau/sous-sol, Pollution, Criminalité,
Valeur foncière et Trafic. `Ctrl` + molette et les boutons `−`/`+` règlent le zoom ; les barres de
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
