# Développer un plugin

## Chargement

Un plugin est un JAR chargé au démarrage du serveur depuis `plugins/` (ou `--plugins`). Il expose
une ou plusieurs classes publiques avec constructeur sans argument implémentant `city.core.CityPlugin`.
Le fichier `META-INF/services/city.core.CityPlugin` contient leurs noms qualifiés, un par ligne.
Les JAR sont triés par nom puis les plugins par ID, pour un ordre reproductible.
Les ID de plugins et de règles doivent être uniques ; `apiVersion` doit être 1.

Le module `example-plugin` est un exemple compilé et testé par le build, sans bibliothèque externe.
Son JAR doit être déployé uniquement côté serveur. Le catalogue transmis au client contient le nom,
le coût, la couleur et l’emprise, afin que la palette et le rendu prennent en charge les nouveaux bâtiments.

## Les trois points d’extension

```kotlin
class MyPlugin : CityPlugin {
    override val id = "my.city.extension"

    override fun configure(context: PluginContext) {
        context.catalog.register(
            BuildingDef("my_garden", "Jardin", cost = 75, upkeep = 2,
                color = 0x74b95b, service = "park", radius = 6)
        )
        context.rules.add(MyDevelopmentRule())
        context.systems.add(MyEconomySystem())
    }
}
```

1. **Catalogue** : `register` ajoute ou remplace une définition sous le même ID. Cela permet de
   modifier prix, capacités, pollution, services, date de disponibilité ou habitants des arcologies.
   Ne pas changer la taille d’un bâtiment déjà présent dans une sauvegarde sans migration : la
   validation d’emprise la refusera. Les services intégrés reconnus sont `park`, `police`, `fire`,
   `health`, `education`, `environment`, `transport`, `commerce` et `industry`. Un nom inédit peut
   être interprété par votre propre règle.
2. **Règle de case** : `TileRule.apply(context, proposed)` reçoit le snapshot **avant** le mois et le
   résultat proposé pour la case **après** les règles de base, puis les règles précédentes. Elle
   retourne la case finale. Cela permet de remplacer une formule de croissance ou de simuler des
   comportements d’habitants agrégés. L’exemple augmente l’attrait et le développement près d’un jardin.
3. **Système global** : `CityRule.evolve(city, catalog)` est suspendable et s’exécute après les règles
   de base, dans l’ordre d’enregistrement. Il peut modifier les finances, les règles globales ou les
   notices, et conserver un état sérialisable dans `city.pluginData`. Le préfixer par son ID, par
   exemple `my.city.extension.population-model`, pour éviter les collisions.

Un système peut remplacer les résultats des règles de base plutôt que les cumuler. Pour des
habitants individuels, conserver un modèle propre au plugin et projeter ses agrégats dans les cases
et statistiques. La taille et le coût de sérialisation de cet état restent à la charge de l’extension.

## Contrats

- Ne jamais modifier les collections du snapshot reçu, même après un cast vers une collection mutable.
  Retourner une copie. Les fonctions de cases sont appelées simultanément depuis plusieurs coroutines.
- Ne pas conserver de compteur mutable partagé sans synchronisation. Préférer un état dans `City`
  et un aléatoire dérivé de `noise(seed, index, month)` pour garder la reproductibilité.
- Ne pas effectuer d’I/O bloquante dans `TileRule`. Un système global peut utiliser `withContext(IO)`
  et la concurrence structurée, mais le tick l’attend : aucune tâche détachée ou scope global.
- Préserver dimensions et révision. Ne pas créer d’emprises incohérentes, d’occupants négatifs ou de
  coordonnées invalides. Mettre à jour les statistiques si vous modifiez les occupants après la réduction.
- Le registre est configuré avant la première connexion ; ne plus le modifier pendant la simulation.
- Une exception met la simulation en pause. Un plugin est du **code JVM de confiance**, sans sandbox
  ni quota CPU ; ne pas charger un JAR non vérifié.

## Relief partagé

Utiliser `city.surface(index)` pour les quatre coins, la planéité et l’orientation ;
`Tile.elevation` n’est que l’altitude minimale. Une `TileRule` ne doit plus modifier cette propriété
isolément. Pour un système global qui transforme le terrain, construire un `Terrain` valide puis
utiliser `city.withTerrain(terrain)` pour synchroniser les altitudes dérivées ; pour les mêmes
protections et coûts que les joueurs, passer par les commandes de terrassement. Un système global
reste responsable de ne pas déformer des emprises occupées et de préserver les statistiques.

Le changement du modèle `City` nécessite de **recompiler les JAR de plugins** contre cette version
de `core`. Le protocole réseau 7 et l’API de plugins 1 sont deux numérotations distinctes ; comme
annoncé, cette API ne garantit pas une compatibilité binaire des modèles entre versions du jeu.

## Compatibilité et distribution

Compiler avec Kotlin 2.4.21 et une cible JVM 21 contre `core` en `compileOnly`.
L’exemple de build est dans `example-plugin/build.gradle.kts`. Pour distribuer un plugin externe,
fournir le JAR et toute dépendance supplémentaire dans le répertoire de plugins, sans embarquer
une deuxième copie de `core`, Kotlin ou kotlinx-coroutines. Les plugins partagent un classloader ;
les conflits de bibliothèques ne sont pas isolés.

Les nouvelles constructions apparaissent avec un rendu procédural générique. Des sprites sur mesure,
des panneaux Swing additionnels, de nouvelles commandes réseau ou un chargement à chaud nécessitent
une évolution du client et/ou de l’API. Cette API v1 n’est pas encore une promesse de compatibilité
binaire entre toutes les futures versions de Shared City.

Pour retirer un plugin : sauvegarder, démolir ses bâtiments et migrer son état si nécessaire, arrêter
le serveur puis retirer son JAR. Une sauvegarde qui référence ses bâtiments refusera de se charger
si le plugin n’est plus présent. Conserver une copie de la sauvegarde avant toute migration.

La mobilité est calculée après les systèmes globaux : les trajets reflètent les occupants et
constructions qu’ils produisent. Elle recalcule `Tile.traffic` et `City.mobility` ; modifier ces
champs dans une règle de plugin ne remplace pas le moteur de mobilité.

`Simulation.step` exécute maintenant un jour civil. Les règles de cases et systèmes globaux
restent exécutés lors du traitement économique du premier jour du mois. La mobilité suit ce
traitement, ou l’actualisation quotidienne des réseaux les autres jours.


Les définitions de bâtiments peuvent préciser `bicycleParking = 24` pour offrir
24 places vélo, partagées par toute l’emprise. La valeur par défaut est 0 ; les valeurs
acceptées vont de 0 à 100 000. Ce paramètre est transmis au client avec le catalogue
et influence le choix du vélo ainsi que la protection contre le vol.
