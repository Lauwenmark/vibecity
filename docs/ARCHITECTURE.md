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
l’occupation et le relief. Une modification retourne une nouvelle ville avec une révision incrémentée ; une
exception laisse la ville d’origine intacte. Une commande validée sans changement retourne le même
instantané : elle conserve révision, mobilité et calcul de simulation en cours. Les outils idempotents ne facturent pas une deuxième
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

Les nouveaux états TCP (protocole 9) et les villes créées par le générateur possèdent toujours la
grille partagée. Le serveur normalise aussi les villes anciennes fournies directement à son
constructeur. La sélection et le cache utilisent les quatre sommets : une modification d’un coin
invalide les portions concernées même si le minimum de la case n’a pas changé.

## Ordonnancement et coroutines

1. Chaque socket dispose d’une coroutine de lecture et d’une coroutine d’écriture sur une vue de `Dispatchers.IO` réservant assez de slots pour les deux sens des 32 sockets.
   Les opérations Java bloquantes n’occupent donc pas les workers CPU de la simulation. Chaque session
   dépend directement du superviseur et intercepte ses erreurs hors de son scope de lecture/écriture :
   une rupture ne peut pas annuler la boucle d’acceptation ni les autres sessions.
2. Les commandes rejoignent un `Channel` borné à 256 événements. Un acteur sur la vue réservée de `Dispatchers.IO` les traite séquentiellement ; la simulation et l’encodage utilisent `Dispatchers.Default`.
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

L’électricité traverse les zones, bâtiments et lignes. L’eau traverse les conduites, les bâtiments placés et les zones développées (occupants > 0).
Un simple zonage vide ne transmet pas l’eau. Ses sources ont besoin d’électricité. L’eau élève la capacité de développement d’une zone de
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

Chaque session possède une file de contrôle bornée à 32 messages et un seul état non encodé en
attente, remplacé par le plus récent. L’écrivain encode les états à son rythme, contre sa propre
base entièrement envoyée, sans interrompre une trame en cours. La saturation des contrôles peut
encore fermer la session. Le client ne garde qu’un état en attente d’affichage pour ne pas saturer
l’EDT. Les réponses ACK/ERROR ne sont pas regroupées. Toutes les modifications Swing passent par l’EDT.

Le premier état reste complet ; les suivants utilisent des deltas par révision. La compression gzip
rapide est négociée par HELLO. La taille maximale de carte et les files bornées ne remplacent pas un benchmark.

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
Le JSON est sérialisé directement dans un flux, sans chaîne complète intermédiaire. Un worker
unique écrit les instantanés dans l’ordre, pendant que l’acteur continue de traiter les commandes.
La file conserve au plus une sauvegarde en attente en plus de l’écriture active ; les demandes
manuelles supplémentaires reçoivent une erreur explicite. Un échec de sauvegarde automatique
met la ville courante en pause sans annuler les modifications reçues depuis l’instantané sauvegardé.
Le repli est un remplacement ordinaire, moins robuste à une coupure. Il n’y a pas de journal durable
par commande, de rotation de sauvegardes ni de garantie contre une panne électrique avant flush disque.

Au chargement : bornes de taille, dimensions, règles et intégrité des emprises sont vérifiées.
Un identifiant de bâtiment absent du catalogue est une erreur explicite, pour éviter de perdre
silencieusement les bâtiments d’un plugin manquant. La sauvegarde n’est pas compatible avec `.SC2`.

L’arrêt normal traite les événements jusqu’à l’événement d’arrêt, puis refuse les nouvelles
mutations et suspend la simulation. Son instantané final est écrit après les sauvegardes déjà
admises ; il peut occuper une place supplémentaire pendant l’attente. Les `PING` restent traités.
La réussite ferme l’écoute et les sockets et annule le scope. Un échec conserve la ville en pause
et les connexions, afin de réparer la destination et réessayer. Les appels concurrents à `shutdown()`
attendent la même tentative ; l’annulation d’un appelant n’annule pas la sauvegarde.
Fermer les sockets débloque les lectures/écritures bloquantes. `close()` est un arrêt immédiat
sans garantie de sauvegarde ; `shutdown()` est le chemin normal.

## Limites opérationnelles

Les plugins sont du code de confiance dans le processus serveur. Une exception de simulation met
la ville en pause et affiche une notification ; un blocage infini de plugin n’est pas isolé.
Les joueurs sont des pairs de confiance : pas de permissions par maire, d’authentification par compte,
de modération ou de TLS natif. Le serveur ne propose aucun chemin de fichier contrôlé par le client.

Le routeur de mobilité préconstruit pour chaque instantané un graphe immuable de voisins et de
coûts primitifs, avec et sans congestion. Les surfaces sont calculées une seule fois. Chaque recherche
emprunte exclusivement un espace de travail (distances, parents, file de priorité), conservé jusqu’à
la reconstruction des chemins puis remis au pool dans un `finally`, y compris en cas d’annulation.
Seuls les nœuds visités sont réinitialisés. Le verrou du pool n’est jamais gardé pendant le calcul ;
la propriété de l’espace suit l’appel, indépendamment du thread exécutant la coroutine. L’ordre des
voisins, les départages, les coûts et les points de vérification d’annulation restent inchangés.

La planification quotidienne construit un index temporaire des souvenirs par habitant, jour de
semaine, besoin et sens du trajet. Les groupes conservent l’ordre de la liste initiale et des ajouts,
afin de préserver les tirages pondérés déterministes. L’index est construit après l’élagage et la
validation des souvenirs ; il appartient au worker du domicile et est abandonné après préparation
des activités. Les carnets persistants et les mutations pendant l’exécution de la journée gardent
leur représentation existante : aucun index périmé n’est conservé entre deux journées.
Le routeur indexe également les destinations accessibles par besoin dans des masques immuables,
pour que la validation répétée des souvenirs ne parcoure plus les listes de destinations. Les listes
ordonnées restent utilisées pour sélectionner les destinations de manière déterministe.

`TravelPath` conserve les nœuds et minutes dans deux tableaux primitifs privés. L’API publique
expose des vues `List<Int>` non modifiables ; les constructeurs copient les entrées mutables et
`copy` peut partager un stockage déjà immuable. L’égalité et le hachage restent fondés sur le contenu,
y compris pour les vues utilisées comme clés par le registre de chemins. La sérialisation lit et
écrit directement les tableaux primitifs avec les mêmes champs JSON `nodes` et `minutes` : aucune
migration des sauvegardes ni nouvelle version du protocole n’est nécessaire. Les opérations `copy`,
la déstructuration et les constructeurs à listes restent disponibles malgré le remplacement de la
`data class` par une classe immuable explicite.

L’exécuteur séquentiel réutilise un masque primitif pour les détours : routes saturées et cases de
marche associées, prochain nœud bloqué, puis exception du départ. Chaque recherche lit le masque
exclusivement jusqu’à son retour ; il est reconstruit avant la recherche suivante.

Dijkstra utilise un tas de longs primitifs ordonné par coût puis nœud. Le comparateur des événements
compare directement minute, priorité et identifiant, sans boxing ni soustraction susceptible de déborder.

Les sauvegardes modernes sont décodées directement depuis un flux. Le marqueur executionVersion
est obligatoire à la lecture de Mobility ; son absence déclenche uniquement dans Persistence le
chemin de migration ancien, qui invalide le cache de mobilité. Les autres erreurs restent rejetées.

Après validation d’une sauvegarde ou reconstruction d’un état réseau, les registres de chemins
égaux du même domicile sont partagés. Aucun indice ni contenu différent n’est réécrit. Le format
JSON reste complet ; les vues des tableaux primitifs rendent ce partage immuable.


## Publication des états

Chaque écrivain compare l’état à la dernière ville entièrement envoyée à son client, et regroupe
les instantanés encore en attente avec une pause de 50 ms après chaque état envoyé. Le protocole 9 transmet les cases et sommets
modifiés pour les changements localisés ; les états initiaux et les changements denses gardent une
carte complète. Les carnets et la mobilité inchangés restent réutilisés par référence de révision.
Le protocole 10 ajoute les différences des registres de chemins et de souvenirs des habitants,
puis la compression gzip niveau 1 avec enveloppe base64. Les grands états de simulation ne
retransmettent plus tous les registres inchangés. La reconstruction restitue toutes les données,
y compris lors d’une migration, d’un changement d’ordre ou d’un élagage de registres.
Le client reconstruit chaque état dans l’ordre avant le regroupement des notifications Swing.
Les états en attente restent bornés par client ; un transfert lent ne bloque pas les autres sessions.
L’encodage est désormais propre à chaque client, ce qui augmente son coût CPU avec le nombre de clients.

## Satisfaction et déménagements

Chaque `Citizen` conserve un `Wellbeing`, sérialisé dans les sauvegardes et les états TCP v9.
Les anciens habitants commencent à 75 %. Après l’exécution de la journée, `updateWellbeing`
évalue leurs activités réellement accomplies et les lieux réellement visités. Les besoins
manqués ont des poids différents (courses 18, repos 15, loisirs 4, soins/éducation selon l’âge).
Le temps de déplacement, les retards par rapport au départ prévu et les distances en cases sont
mesurés ; leur pénalité est le maximum des trois contributions, plafonné à 25, pour ne pas
additionner trois fois le même trajet. Le délai moyen d’accomplissement inclut le séjour sur place.

Les autres facteurs sont la criminalité (65 % domicile, 35 % destinations), la couverture incendie,
l’eau, l’électricité, la pollution résidentielle, l’emploi, le budget, le logement, les soins,
l’éducation, le bruit et les espaces verts. Les champs de services respectent l’alimentation,
le financement et les rayons de Manhattan. Les personnes âgées ont davantage besoin de soins ;
les mineurs d’éducation. La qualité du logement utilise la valeur foncière ; au-delà de 85 % de
la capacité nominale, une pénalité d’occupation apparaît. Le bruit est un proxy du trafic et de
l’industrie adjacente, et les parcs/arbres réduisent le manque d’espaces verts.

La somme des pénalités est plafonnée à 100 puis lissée : 80 % de la pression mémorisée,
20 % de celle du jour. La satisfaction cible est `100 − pression`. La remontée est limitée à
0,35 point par jour ; la baisse absorbe 12 % de l’écart, au maximum 4 points par jour.
Une nuisance modérée converge ainsi vers une satisfaction intermédiaire au lieu de vider
inéluctablement la ville. Les facteurs affichés sont ceux du jour, avant lissage.

Les emplois commerciaux/industriels ont une capacité finie. Les contrats existants sont conservés
en priorité tant que leur établissement fonctionne ; les candidats sont recrutés après avoir
atteint un lieu de travail disposant d’une place. Leurs prochains trajets ciblent cet employeur.
Le contrat est conservé le week-end et lors d’un retard ; l’activité manquée reste pénalisée.
L’ancienneté d’emploi et la durée du chômage sont suivies. L’économie domestique reste simplifiée :
revenu quotidien 50 pour un salarié, pension 28 après 66 ans, allocation 12 pour un adulte sans
emploi ; loyer par personne `5 + valeur foncière / 10` (+2 en résidentiel léger), consommation
10 par adulte et 6 par enfant, plus impôts au taux municipal. Revenus et dépenses sont mutualisés
par foyer. Il n’existe pas encore de marché immobilier, d’épargne ni de qualifications professionnelles.

Le modèle historique appelle « foyer » l’ensemble des habitants d’une case : ce n’est pas encore
une partition de l’immeuble en familles. Sa satisfaction moyenne pilote un tirage déterministe
quotidien sous 60 %, modulé par la durée d’insatisfaction (plein effet à 30 jours) et l’ancienneté
résidentielle (risque divisé par deux après deux ans). Le risque maximal est de 8 % par jour.
À la frontière entre deux journées, un foyer entièrement rentré peut rejoindre une habitation
vide, suffisamment grande, desservie et sensiblement meilleure ; faute d’alternative il quitte
la ville. Identités et opinions sont conservées lors d’un relogement, les anciens itinéraires
sont effacés. La population des arcologies ne disparaît plus lors d’une panne et une occupation
explicite empêche de recréer automatiquement les habitants partis. La croissance mensuelle
reste le mécanisme simplifié d’arrivée de nouveaux habitants.

La case « Satisfaction » affiche sur le calque Surface un visage par habitation (vert ≥70 %, jaune
≥40 %, rouge sinon). Les scores sont agrégés à réception de l’état et dessinés en surimpression,
sans invalider les images du décor. L’inspecteur donne moyenne, tendance et probabilité de départ ;
l’inspecteur des habitants expose budget, emploi, trajets et causes de l’insatisfaction.

## Sprites du métro

`MetroSprites` charge une fois les 16 variantes de tunnel et l’entrée de station depuis les ressources du client.
Les tunnels remplacent les traits du calque Eau / sous-sol. Le masque des quatre voisins est tourné avec la
caméra ; chaque image est plaquée sur les deux triangles du terrain, puis les conduites d’eau et le marqueur
M sont superposés. La station utilise son sprite en surface et conserve la représentation classique dans
les calques de diagnostic. Les dimensions d’occlusion du trafic tiennent compte du nouveau sprite.

Les sources graphiques, leur index et les instructions de régénération sont dans `assets/sprites/metro/`.
`tools/PrepareMetroSprites.java` produit les PNG compacts et normalise la géométrie des voies et de leurs
raccords. Aucune génération d’image ni lecture des sources haute résolution n’est effectuée en jeu.


## Cycle de vie, famille et instruction

Le modèle sérialisable est dans `LifeModel.kt`. `LifeCycle.kt` réconcilie les habitants avec
les logements, initialise les anciennes données et exécute mortalité/vieillissement.
`FamilyLife.kt` gère naissances et décisions mensuelles ; `Education.kt` gère orientation,
progression quotidienne, examens et agrégats d’âge. Voir [les règles](LIFE_CYCLE.md).

L’ordre quotidien est : réconciliation, mortalité et âges, naissances, décisions familiales
(le premier du mois), économie/réseaux, réconciliation des arrivées/sinistres, migration,
planification/exécution des déplacements, instruction et examens, bien-être.
Les mutations démographiques précèdent la construction des registres de trajets. Les nouveaux
occupants mettent à jour immédiatement les compteurs résidentiels, y compris dans les arcologies.
Une naissance peut dépasser temporairement la capacité résidentielle ; un emménagement ne le peut pas.
La décroissance économique ne tronque plus arbitrairement les habitants d’une zone résidentielle.

Les tirages utilisent exclusivement graine, identifiant, jour et sel d’événement. Les décisions
familiales se font dans l’ordre des identifiants ; chaque couple ne décide qu’une fois par mois.
La planification parallèle est indépendante de ces mutations et reste déterministe.
Les moyennes de NI sont dérivées des citoyens et mises en cache par instantané dans l’inspecteur.


### Coordination des rendez-vous étudiants

Après les plans individuels parallèles, `planStudentDates` effectue une phase déterministe de
coordination entre domiciles. L'orientation est déjà résolue : l'établissement des candidats est
la destination du cours prévu. Les groupes établissement/âge bornent les recherches à 32 candidats.
Les agendas communs sont insérés dans les créneaux libres sans partager les registres de chemins.
Le `DayExecutor` conserve l'identité des rendez-vous dans chaque jambe, réserve la présence de
l'hôte et termine les séjours à l'heure commune prévue. `finishStudentDates` mesure le recouvrement
des présences exécutées, corrige les compteurs d'activités, actualise la solidité réciproque et
produit le bilan individuel utilisé par `updateWellbeing`. `finishEducationDay` nettoie les liens
dès qu'un partenaire quitte les études. Les statistiques sociales de l'inspecteur sont calculées
une fois par instantané de mobilité, et non à chaque déplacement du curseur horaire.
