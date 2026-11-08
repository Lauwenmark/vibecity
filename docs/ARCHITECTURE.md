# Architecture

## Découpage

```text
client (Swing) ─── TCP / JSON Lines ─── server (acteur autoritaire)
      │                                      │
      └──────────────── core ────────────────┘
                           ↑
                    example-plugin (SPI)
```

| Module | Responsabilité |
| --- | --- |
| `core` | Modèle sérialisable, catalogue, transactions pures, simulation, API des plugins, protocole |
| `server` | Écoute TCP, sessions, ordre des commandes, cadence, sauvegardes et chargement des JAR |
| `client` | Connexion, rendu isométrique, outils, inspection et tableaux de bord Swing |
| `example-plugin` | Bâtiment et règle de comportement ajoutés via ServiceLoader |

Le client dépend aussi du serveur pour proposer un mode local utilisant le même protocole.
Aucune règle de construction ou de budget n’est exécutée exclusivement dans l’interface.
Une autre interface graphique pourrait se brancher sur `core` et le protocole sans modifier le moteur.

## État et invariants

`City` contient les dimensions, la graine, la date, les finances, les règles, les statistiques,
un historique de 120 mois et une liste de `Tile`. Ce sont des objets-valeurs exposés en lecture
seule. Le propriétaire de la ville courante est exclusivement l’acteur de `CityServer`.
Les listes Kotlin ne constituent pas une barrière de sécurité contre un plugin JVM malveillant.

Une case contient des couches indépendantes de surface et de sous-sol. Une zone peut recouvrir
une conduite. Un bâtiment fixe couvre un carré de `BuildingDef.size` cases ; chaque case porte
son identifiant et l’indice de la même ancre. Le coût et l’entretien sont calculés une seule fois,
à l’ancre. Une destruction retire l’emprise entière. La version actuelle développe les bâtiments
RCI par case, contrairement aux emprises variables du jeu original.

`Commands.apply` vérifie les coordonnées, les fonds, la disponibilité historique, l’emprise,
l’occupation et le relief. Elle retourne une nouvelle ville avec une révision incrémentée ; une
exception laisse la ville d’origine intacte. Les outils idempotents ne facturent pas une deuxième
fois un réseau déjà présent. Rezoner une zone existante nécessite de la démolir au préalable.

## Ordonnancement et coroutines

1. Chaque socket dispose d’une coroutine de lecture et d’une coroutine d’écriture sur une vue de `Dispatchers.IO` réservant assez de slots pour les deux sens des 32 sockets.
   Les opérations Java bloquantes n’occupent donc pas les workers CPU de la simulation.
2. Les commandes rejoignent un `Channel` borné à 256 événements. Un acteur sur `Dispatchers.Default` les traite séquentiellement, sans dépendre des slots d’I/O bloquante.
   Aucune mutation concurrente des finances ou du terrain n’est possible.
3. Un tick travaille sur un instantané. Analyse des transports et de l’électricité en parallèle ;
   eau et services après l’électricité. Les analyses utilisent `Dispatchers.Default`.
4. La croissance, la pollution locale, le crime et les incendies sont calculés par lots indépendants.
   La taille des lots dépend de `availableProcessors()`, avec au moins 64 cases par lot pour éviter
   une coroutine par case. `awaitAll()` réunit les résultats dans l’ordre de la carte.
5. Une réduction calcule les finances, les statistiques et la destruction des emprises incendiées.
   Les systèmes globaux des plugins s’exécutent ensuite dans leur ordre d’enregistrement.
6. L’acteur publie la nouvelle ville, puis accepte la prochaine transaction. Le ticker attend la fin
   du tick avant de planifier le suivant : aucun empilement de ticks en cas de calcul lent.

L’aléatoire est une fonction de la graine, de l’indice et du mois. La simulation donne donc le même
résultat à un ou plusieurs workers, sous réserve de plugins eux-mêmes déterministes. Un test compare
huit mois calculés avec 1 et 8 workers.

La parallélisation exploite le pool CPU standard des coroutines sans imposer une limite artificielle
à quelques cœurs. Elle ne garantit pas une occupation de 100 % sur une petite carte : ordonnancement,
réductions, construction du graphe et sérialisation ont un coût séquentiel. Des processus de simulation
concurrents ne pourraient pas écrire sans coordination dans la même ville.

## Réseaux et simulation

Les réseaux d’eau et d’électricité sont parcourus en composantes connexes par voisinage orthogonal.
Chaque composante possède sa capacité propre ; une centrale déconnectée ne peut pas compenser un
quartier en panne. Les infrastructures seules ne consomment pas de capacité. Chaque case de zone
ou de bâtiment en consomme une, attribuée dans l’ordre des indices ; c’est une simplification
explicite, pas un calcul physique ni une priorité équitable en cas de pénurie.

L’électricité traverse les zones, bâtiments et lignes. L’eau traverse les conduites et bâtiments,
et ses sources ont besoin d’électricité. L’eau élève la capacité de développement d’une zone de
60 % à 100 %. Les services exigent une alimentation et leur efficacité dépend du financement.
Leur influence décroît avec la distance de Manhattan. Les émissions industrielles et des centrales
s’additionnent ; parcs, éducation, santé, police et épuration modifient des champs locaux.

Le transport autorise le développement dans un rayon de trois cases. Le trafic est actuellement
une estimation agrégée de population atténuée par les services de transport : aucun cheminement
individuel domicile-travail n’est simulé. Les habitants sont des agrégats, extensibles via les plugins.

Chaque mois : demande R/C/I dépendant population/emplois/impôts ; croissance ou déclin selon desserte,
alimentation, pollution et demande ; revenus proportionnels aux occupants ; dépenses d’entretien,
financement et intérêts des emprunts. Les formules sont visibles dans `Simulation.kt`.

## Réplication et pression réseau

Le serveur publie un instantané complet après chaque transaction et tick, avec sa révision.
Les clients peuvent ainsi rejoindre ou se reconnecter sans historique d’opérations. Une reconnexion
est une nouvelle session ; aucune commande n’est automatiquement rejouée.

Chaque session possède une file d’écriture de 32 messages. Un client trop lent est déconnecté plutôt
que de bloquer la simulation ou faire croître une file sans limite. Une même chaîne JSON de snapshot
est partagée entre les files des clients. Le client ne garde qu’un état en attente d’affichage,
pour ne pas saturer l’EDT avec des snapshots devenus obsolètes. Les réponses ACK/ERROR ne sont pas
coalescées. Toutes les modifications des composants Swing passent par l’EDT.

Les snapshots complets privilégient la simplicité de débogage. À grande échelle, la prochaine
évolution pertinente est un protocole de deltas par révision, puis une resynchronisation complète
sur demande. La taille maximale de carte et les files bornées ne remplacent pas un benchmark.

## Persistance et arrêt

La sauvegarde est un JSON complet de `City`. Le serveur écrit un fichier temporaire dans le même
répertoire puis remplace la cible par déplacement atomique si le système de fichiers le permet.
Le repli est un remplacement ordinaire, moins robuste à une coupure. Il n’y a pas de journal durable
par commande, de rotation de sauvegardes ni de garantie contre une panne électrique avant flush disque.

Au chargement : bornes de taille, dimensions, règles et intégrité des emprises sont vérifiées.
Un identifiant de bâtiment absent du catalogue est une erreur explicite, pour éviter de perdre
silencieusement les bâtiments d’un plugin manquant. La sauvegarde n’est pas compatible avec `.SC2`.

L’arrêt normal ferme l’écoute, traite les événements déjà admis jusqu’à l’événement d’arrêt,
sauvegarde, ferme les sockets et annule le scope. Fermer les sockets débloque les lectures/écritures
bloquantes. `close()` est un arrêt immédiat sans sauvegarde ; `shutdown()` est le chemin normal.

## Limites opérationnelles

Les plugins sont du code de confiance dans le processus serveur. Une exception de simulation met
la ville en pause et affiche une notification ; un blocage infini de plugin n’est pas isolé.
Les joueurs sont des pairs de confiance : pas de permissions par maire, d’authentification par compte,
de modération ou de TLS natif. Le serveur ne propose aucun chemin de fichier contrôlé par le client.
