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
un historique de 120 mois et une liste de `Tile`. `day` identifie le prochain jour civil à exécuter ;
`households` contient les habitants persistants, leurs positions et leurs carnets de chemins. Ce sont des objets-valeurs exposés en lecture
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

## Relief partagé

`City.terrain` porte une grille de `(width+1) × (height+1)` sommets entiers de 0 à 12. Chaque case
référence implicitement quatre sommets, sans copies indépendantes des bords : nord `(x,y)`, est
`(x+1,y)`, sud `(x+1,y+1)`, ouest `(x,y+1)`. La différence entre le sommet le plus haut et le plus
bas d’une case ne dépasse jamais un niveau. `Surface` expose le minimum, le maximum, la planéité
et l’orientation des rampes simples. Le rendu triangule le quadrilatère suivant sa diagonale
nord-sud et ombre les pentes. Le centre repose sur cette diagonale, y compris pour les coins relevés.

L’altitude `Tile.elevation` est conservée pour les inspections et les plugins existants au niveau
source, mais c’est une valeur dérivée égale au minimum des quatre sommets. Elle ne suffit plus à
caractériser un terrain. `City.surface(index)` est l’accès commun aux règles et au client.

Élever/abaisser déplace les quatre sommets d’une case d’un niveau. Chaque autre sommet est ramené
dans l’intervalle compatible avec ces ancrages et leur distance de Chebyshev, afin de raccorder le
relief sans sauts. Aplanir fixe une altitude pour les quatre coins et raccorde également le voisinage.
Le serveur calcule toute la transformation avant d’accepter l’opération : chaque sommet modifié coûte
5 $, et toute modification d’une case comportant zone, bâtiment ou réseau, y compris souterrain,
refuse la transaction. Les finances et la géométrie restent intactes en cas de refus.

Les bâtiments ordinaires exigent une emprise plane à une même altitude. Le zonage sur pente reste
possible, mais la croissance de base exige une case plane. Routes et rails acceptent le plat et les
rampes simples ; leurs connexions suivent l’axe de la pente. Les coins et selles ne sont pas des
rampes routières. Les conduites et lignes électriques suivent la géométrie de surface dans le rendu.
La croissance conserve son critère de proximité ; les déplacements individuels utilisent un graphe
multimodal respectant les raccordements routiers et ferroviaires.

L’eau naturelle occupe les cases entièrement à l’altitude zéro. L’outil Eau peut également couvrir
une surface plane ou une rampe en altitude ; un barrage neuf exige une rampe couverte d’eau.
Il ne s’agit pas d’un solveur hydraulique : le débit et l’écoulement automatique ne sont pas simulés.
Terrasser une case recalcule son eau à partir du niveau zéro et retire ses arbres.

Le champ nullable `terrain` permet de reconnaître une ancienne sauvegarde. `withSharedTerrain()`
crée les sommets à partir de ses altitudes, puis rassemble les cases développées qui partagent des
sommets en plateformes planes. Leur altitude est relevée si nécessaire pour respecter les anciens
hauts niveaux ; les terrains libres voisins sont raccordés. Aucune construction n’est supprimée.
La migration est déterministe et ne débite pas le budget, mais peut modifier sensiblement le relief
d’un quartier déjà bâti. Les barrages existants bénéficient de la conservation des constructions.
Le chargement sauvegarde les octets originaux sous `.pre-slopes.json` avant de rendre la ville
convertie au serveur ; un nom numéroté évite d’écraser une sauvegarde de secours différente.

Les nouveaux états TCP (protocole 6) et les villes créées par le générateur possèdent toujours la
grille partagée. Le serveur normalise aussi les villes anciennes fournies directement à son
constructeur. La sélection et le cache utilisent les quatre sommets : une modification d’un coin
invalide les portions concernées même si le minimum de la case n’a pas changé.

## Ordonnancement et coroutines

1. Chaque socket dispose d’une coroutine de lecture et d’une coroutine d’écriture sur une vue de `Dispatchers.IO` réservant assez de slots pour les deux sens des 32 sockets.
   Les opérations Java bloquantes n’occupent donc pas les workers CPU de la simulation.
2. Les commandes rejoignent un `Channel` borné à 256 événements. Un acteur sur `Dispatchers.Default` les traite séquentiellement, sans dépendre des slots d’I/O bloquante.
   Aucune mutation concurrente des finances ou du terrain n’est possible.
3. Un tick exécute un à trois jours civils selon la vitesse. Le premier jour du mois, analyse
   des transports et de l’électricité en parallèle ;
   eau et services après l’électricité. Les analyses utilisent `Dispatchers.Default`.
4. La croissance, la pollution locale, le crime et les incendies sont calculés par lots indépendants.
   La taille des lots dépend de `availableProcessors()`, avec au moins 64 cases par lot pour éviter
   une coroutine par case. `awaitAll()` réunit les résultats dans l’ordre de la carte.
5. Une réduction calcule les finances, les statistiques et la destruction des emprises incendiées.
   Les systèmes globaux des plugins s’exécutent ensuite dans leur ordre d’enregistrement, puis
   la mobilité planifie les itinéraires par domicile via un nombre borné de coroutines sur
   `Dispatchers.Default`. Un ordonnanceur temporel déterministe exécute les mouvements et arbitre
   les réservations routières partagées. Les autres jours, les réseaux sont actualisés et la mobilité
   est exécutée sans répéter la croissance ni les prélèvements mensuels.
6. L’acteur publie la nouvelle ville, puis accepte la prochaine transaction. Le ticker attend la fin
   du tick avant de planifier le suivant : aucun empilement de ticks en cas de calcul lent.

L’aléatoire est une fonction de la graine, de l’indice et du mois. La simulation donne donc le même
résultat à un ou plusieurs workers, sous réserve de plugins eux-mêmes déterministes. Un test compare
huit jours calculés avec 1 et 8 workers ; les tests de mobilité comparent aussi les scores et traces.

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

Le transport autorise toujours le développement dans un rayon de trois cases. Le trafic est désormais
issu des déplacements exécutés chaque jour : besoins individuels, chemins multimodaux, occupation
et débit routiers bornés, attentes, détours et apprentissage. Les jours de semaine influencent les
activités ; les retards décalent les sorties suivantes et les habitants restent à leur position
lorsqu’un déplacement ne peut pas être terminé à minuit.
`City.mobility.homes` conserve les chemins et charges horaires propres à chaque domicile.
Le graphe est partagé en lecture seule ; aucune liste globale de chemins n’est nécessaire. Les contrats,
limites et règles d’inspection sont décrits dans [Mobilité](MOBILITY.md).

Chaque mois : demande R/C/I dépendant population/emplois/impôts ; croissance ou déclin selon desserte,
alimentation, pollution et demande ; revenus proportionnels aux occupants ; dépenses d’entretien,
financement et intérêts des emprunts. Les formules sont visibles dans `Simulation.kt`.

## Réplication et pression réseau

L’acteur traite les commandes sans attendre les calculs de trafic. La simulation travaille sur
un instantané immuable dans une coroutine `Default` ; l’acteur reste l’unique propriétaire de la
ville. Toute transaction acceptée annule le calcul en cours. Un résultat (ou une erreur) portant
une ancienne révision est ignoré : il ne peut écraser ni une construction ni une pause. Le prochain
tick reprend le calcul depuis la ville modifiée. Une séquence continue de constructions peut donc
retarder l’avancement du calendrier ; les journées ne sont jamais fusionnées avec des commandes
après coup. Il n’y a qu’un calcul de simulation à la fois, et aucun tick accumulé.

Le serveur demande un instantané complet après chaque transaction et tick, avec sa révision.
La sérialisation JSON se fait hors de l’acteur, avec une seule demande en attente (la plus récente).
Les états intermédiaires remplacés ne sont pas publiés ; les ACK/ERROR restent individuels.
Les journées sont exécutées entièrement avant publication ; l’inspecteur rejoue la dernière journée.
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

## Rendu Swing et réactivité du pointeur

`CityCanvas` sépare la scène de la sélection survolée et des voitures animées.
`RoadTrafficAnimation` prépare les liaisons routières, charges et masques de bâtiments lors des
changements d’état, d’heure, de catalogue ou de zoom. Un timer Swing coalescé demande au plus
20 rafraîchissements par seconde de la zone visible : il ne modifie pas le cache du paysage.
Les liaisons hors du clip ne sont pas dessinées. Les voitures restent sous les surcouches de
trajet et de sélection. Le timer s’arrête en pause, sur les calques techniques, lorsque la vue
est masquée ou retirée, ou quand l’utilisateur décoche Voitures.
Après une construction, les dernières charges routières connues restent utilisées pour les voitures
décoratives jusqu’au prochain résultat de simulation. Les routes supprimées ou en feu sont retirées
immédiatement de ce cache ; une route reconstruite attend de nouvelles mesures. Ce cache ne restaure
aucun itinéraire individuel invalidé et est vidé à la déconnexion ou au changement de ville.

`SceneCache` conserve des portions d’image
RGB de 256 × 256 pixels logiques, avec une bordure technique de deux pixels. Le cache LRU est limité
à 128 portions, soit environ 33 Mio de pixels par fenêtre, indépendamment de la taille de la carte.
Le survol redessine uniquement les anciens et nouveaux contours ; rester sur la même case ne
redéclenche ni inspection ni dessin. Le texte de l’inspecteur n’est remplacé que s’il change.

`CityRenderer` parcourt seulement les diagonales et cellules susceptibles d’intersecter la portion
à reconstruire, en conservant l’ordre du peintre. Les marges tiennent compte du relief, des toits,
des libellés et des traits de connexion. Chaque portion reproduit cet ordre indépendamment ; la
bordure technique est recadrée lors de l’affichage pour éviter les raccords d’anticrénelage visibles.
La hauteur visuelle des immeubles RCI est plafonnée à celle d’une zone pleine, même si un plugin
introduit une population supérieure à sa capacité nominale.

Un nouveau snapshot invalide uniquement les portions touchées par des différences visuelles dans
le calque courant, y compris les connexions routières/ferroviaires voisines. Une modification des
finances ou de champs invisibles dans le calque ne vide pas le cache. Changer le catalogue, le zoom,
le calque ou les dimensions réinitialise le cache. La disposition Swing n’est recalculée que lorsque
la taille préférée change.

`CityProjection` sert au dessin et à la sélection. La recherche inverse du point teste seulement
les quelques diagonales compatibles avec les altitudes 0..12, en ordre inverse du peintre. Elle
fonctionne avant le premier dessin et ne dépend ni du cache ni du défilement.

Les reconstructions restent synchrones sur l’EDT, mais sont limitées aux portions nécessaires.
Un premier affichage, un changement de zoom/calque ou une ville très modifiée coûte donc davantage
qu’un survol d’une scène déjà en cache. Aucun composant Swing n’est manipulé depuis un worker.

Les tests comparent les pixels du cache avec un dessin complet sur les huit calques et plusieurs
zooms, vérifient les invalidations, la sélection sur relief, l’effacement du surlignage et la borne
mémoire du cache. Ils vérifient également qu’un survol ne reconstruit aucune portion de scène.
Les seuils de temps ne sont pas des assertions de CI : ils dépendraient de la machine.

Mesure locale du 8 novembre 2026 : sur l’EDT, image mémoire 900 × 650, terrain généré de graine 2000,
10 passages d’échauffement puis 30 mesures, sans réseau/simulation et avec inspection vide. Temps
médians d’un mouvement synthétique du pointeur suivi du dessin de toute la zone visible :

| Carte | Avant | Après, cache chaud |
| --- | ---: | ---: |
| 64 × 64 | 21,24 ms | 0,25 ms |
| 128 × 128 | 28,40 ms | 0,15 ms |
| 256 × 256 | 53,29 ms | 0,13 ms |

Ces mesures isolent le chemin du survol et ne représentent ni une cadence écran garantie ni le coût
d’une reconstruction à froid. Les allocations mesurées dans ce chemin sur 64 × 64 passent d’environ
3,5 Mio à 6,3 Kio par opération.

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
