# Habitants, besoins et déplacements

## Utilisation

Après au moins un mois de simulation, cliquer **Habitants / trajets…** dans la barre de vue,
ou double-cliquer un domicile avec l’outil Inspection. Choisir le domicile, puis le numéro de
l’habitant. Cliquer une ligne de besoin affiche le chemin envisagé, y compris pour un besoin
non programmé aujourd’hui. Le curseur horaire montre la position de cet habitant sur la carte.

Les chemins sont colorés par mode : jaune pour la marche, orange pour la route, bleu pour le
train, violet pour le métro. Les extrémités indiquent domicile et destination ; le point blanc
situe l’habitant à l’heure observée. L’aller et le retour empruntent le même chemin.

Choisir le calque **Trafic** pour voir la charge à l’heure de l’inspecteur. Sans inspecteur ouvert,
ce calque affiche la pointe journalière. Vert signifie faible charge ; rouge signifie capacité
horaire atteinte ou dépassée. L’inspection de case affiche toujours la charge de pointe.

### Voitures animées

Les calques Surface et Trafic affichent de petites voitures sur les routes. La case **Voitures**
dans la barre de vue permet de les masquer. Leur nombre reflète les compteurs ROAD de l’heure
choisie dans l’inspecteur ; sans inspecteur, la pointe journalière est utilisée. La densité est
actualisée à chaque nouvel état de ville. Les charges des trains et du métro ne créent pas de voitures.

Chaque liaison entre deux cases routières connectées utilise le minimum de leurs charges : aucun
véhicule ne traverse une coupure, une connexion de pente invalide ou une case incendiée. Quatre
voitures par liaison représentent une charge nominale complète ; le rendu est plafonné à six en
surcharge. Plus la charge est forte, plus le mouvement visuel est lent. Les routes sont élargies
pour rendre les deux files lisibles ; les bâtiments au premier plan masquent les véhicules.

Il s’agit d’une illustration locale de densité, sans association voiture/habitant ni direction
réelle déduite des déplacements. Les motifs bouclent sur chaque liaison ; il n’y a pas de simulation
de collision ou de files physiques. Le curseur ne fait pas défiler automatiquement la journée.
L’animation est figée en pause, arrêtée hors des deux calques concernés ou quand la fenêtre est
masquée, et peut être désactivée indépendamment de la simulation.

Les constructions et changements de financement invalident les parcours : ils sont recalculés
au prochain mois. En pause, l’inspecteur explique cette attente au lieu de montrer des chemins
qui traverseraient des infrastructures supprimées. Les anciennes sauvegardes acquièrent leurs
premiers plans au prochain mois, sans modification de leurs habitants ou finances.

## Temps et identité

L’économie reste mensuelle. Après la croissance et les plugins, le serveur simule une journée
représentative complète du nouveau mois. Chaque résident des zones et chaque résident des
arcologies alimentées participe, sans échantillonnage. Le client consulte ce résultat ; déplacer
le curseur ne fait pas avancer le serveur et ne modifie pas la ville des autres joueurs.

Un habitant est identifié par `(domicile, numéro de place)`, encodé dans un entier de 64 bits.
Son âge (6 à 85 ans), ses préférences et ses décalages horaires dérivent de cette identité et de
la graine de la ville. Ils sont stables entre deux mois tant que sa place existe. La baisse de
population retire les dernières places ; une croissance ultérieure peut réutiliser ces identités.
Il ne s’agit donc pas encore d’un registre d’état civil avec naissances, vieillissement,
déménagements et décès nominatifs.

Les habitants d’un domicile partagent jusqu’à deux chemins par besoin ; chacun choisit une option
avec sa préférence déterministe. Horaires et position sont reconstruits à l’identique à partir
de ces données, au lieu de sérialiser des milliers de copies des mêmes chemins. Le serveur
itère néanmoins sur chaque habitant pour compter ses déplacements effectifs dans cette journée.

## Besoins

| Besoin | Qui / fréquence | Destination | Horaire souhaité | Temps sur place |
| --- | --- | --- | --- | --- |
| Travail | 18–66 ans, quotidien | Zone commerciale ou industrielle occupée et alimentée | 7 h à 8 h 29 | 8 h |
| Éducation | 6–17 ans, quotidien | Bâtiment alimenté de service `education` | 8 h à 8 h 29 | 6 h |
| Soins | Tous, une journée représentative sur sept | Bâtiment alimenté de service `health` | 10 h à 15 h 59 | 30 min |
| Courses | Tous, quotidien | Zone commerciale occupée et alimentée | 17 h à 18 h 29 | 30 min |
| Loisirs | Tous, quotidien | Bâtiment alimenté de service `park` | 19 h à 19 h 59 | 45 min |
| Repos | Tous, quotidien | Domicile | 22 h | Reste à domicile |

Les services doivent aussi être financés au-dessus de zéro et les destinations ne doivent pas
brûler. Les besoins sont traités dans cet ordre de priorité. Chaque sortie attend le retour de
la précédente : aucune téléportation entre deux activités, aucun chevauchement. Si l’aller,
l’activité et le retour dépassent 22 h, le besoin reste non satisfait. Les besoins sans destination
accessible sont également signalés. Un soin prioritaire peut donc empêcher les courses ou les loisirs.

Le tableau distingue un besoin non applicable, non nécessaire aujourd’hui, inaccessible ou trop
long. Le compteur de besoins satisfaits comprend le repos ; il décrit le résultat de la journée
représentative. La faim ou la maladie ne s’accumulent pas encore d’un mois à l’autre. Les besoins
non satisfaits n’ajoutent pas encore de pénalité économique ou de mortalité.

## Graphe et congestion

Une recherche de Dijkstra par domicile travaille sur quatre états par case : marche, route,
rail, métro. Les destinations proposées sont les deux accessibles les plus rapides (départage
par indice de case), dans une limite de 180 minutes pour un aller. Un domicile sans destination
n’émet aucun trafic pour ce besoin.

- Marche : 5 minutes par case orthogonale, terrain terrestre ou pont routier, sans feu, avec
  différence d’altitude minimale au plus égale à un. Les parcelles et emprises terrestres sont
  traversables à pied ; clôtures et entrées de bâtiments ne sont pas modélisées.
- Route : 2 minutes par case, plus la pénalité de congestion. Les routes doivent être adjacentes
  et raccordées selon leur pente. Entrer ou sortir de ce mode prend 2 minutes.
- Rail et métro : 1 minute par case, plus la pénalité de congestion. Le rail respecte les rampes ;
  le métro suit les tunnels orthogonaux. Monter et descendre demande une gare ou station alimentée
  et financée, sur la case du réseau ou immédiatement à côté d’une case de son emprise. Chaque
  correspondance prend 5 minutes. Les changements entre modes passent par la marche.

Les trajets routiers représentent des déplacements individuels abstraits ; ni possession d’une
voiture ni permis de conduire ne sont simulés. Les dépôts de bus restent constructibles mais ne
fournissent pas encore de lignes ou de mode bus dans ce graphe.

Chaque passage d’un habitant sur une case d’infrastructure est attribué à son heure estimée,
à l’aller puis au retour. Les capacités sont 120 passages/h pour la route, 600 pour le rail et
900 pour le métro. Les compteurs bruts ne sont pas plafonnés ; la couleur utilise
`min(100, passages × 100 / capacité)`. Les piétons n’alimentent pas la charge des véhicules.

La pointe du mois précédent ajoute à chaque étape un délai en minutes :
`clamp(floor(2 × pointe / capacité) − 1, 0, 8)`. Les routes alternatives et les transports collectifs
peuvent alors devenir préférables. Ce coût est figé pour la journée planifiée ; le retour utilise
les mêmes durées en sens inverse. Ce modèle ne résout pas les files de véhicules, les feux,
les horaires de trains ou un équilibre de trafic par itérations. Les fluctuations d’affectation
entre mois sont possibles. La capacité pénalise les temps ; elle ne bloque pas l’embarquement.

La règle historique de croissance exigeant une infrastructure à trois cases est conservée.
Elle est indépendante des déplacements : un quartier peut se développer tout en affichant des
besoins inaccessibles. Les destinations n’ont pas encore de quotas d’emplois, d’élèves ou de patients.

## Contrats techniques et validation

`City.mobility` contient les domiciles et le bilan total des besoins. Chaque `MobilityHome`
possède ses effectifs, ses options, sa liste locale `paths` (modes et temps cumulés), sa liste
`loads` (charges horaires clairsemées) et son bilan des besoins. Les indices de chemins dans les
options et les trajets désignent exclusivement la liste du domicile concerné. Aucune liste globale
de chemins ou de charges n’est stockée. `aggregateLoads()` calcule temporairement la somme des
charges pour le rendu et la congestion ; il additionne les heures avant de chercher la pointe. Il est nullable pour les anciens
états et les plans invalidés. Les champs sont sérialisés dans la sauvegarde et le protocole **4**.
Le chargement vérifie les indices, les effectifs, les durées, les dimensions horaires et les modes.
Les plugins doivent être recompilés contre ce nouveau modèle.

Le calcul est suspendable et s’exécute sur `Dispatchers.Default`. Un nombre borné de coroutines
(`workers`, par défaut le nombre de processeurs disponibles, au plus le nombre de domiciles)
réclament les domiciles à traiter via un compteur atomique. Chaque coroutine calcule pour un
domicile les chemins, horaires individuels, passages et bilans, puis passe au suivant. Il n’y a
ni coroutine par habitant, ni création de milliers de recherches simultanées.

Le graphe et les coûts du mois précédent sont construits avant les workers, puis partagés en
lecture seule. Chaque recherche possède ses tableaux de distances, prédécesseurs et sa file de
priorité ; chaque domicile possède ses compteurs. Les workers conservent des résultats locaux,
réunis avec `awaitAll()` puis ordonnés par case de domicile. Les boucles de recherche vérifient
l’annulation toutes les 256 visites et celles de comptage tous les 64 habitants. Une erreur annule
les autres workers ; aucun instantané partiel n’est publié. La préparation du graphe et la somme
finale des charges restent séquentielles.

Le calcul reste déterministe, indépendant du nombre de workers. Les systèmes de
plugins s’exécutent avant la mobilité pour que les trajets reflètent leur ville finale ; la mobilité
est propriétaire du champ `traffic`. L’affichage des parcours est une surcouche qui ne reconstruit
pas les portions de paysage du cache. Seul le calque Trafic est invalidé lors d’un changement d’heure.

Les tests couvrent profils et horaires individuels, retours et positions, coupure par l’eau et pont,
stations et financement, congestion et changement de mode, arcologies sans échantillonnage,
invalidation après construction, sauvegarde/ancien format, sélection dans l’inspecteur et rendu.
Les tests de concurrence comparent aussi les snapshots JSON avec 1 et 4 workers, vérifient la
possession locale des données, l’addition des charges, la concurrence bornée, l’annulation et les
erreurs de workers. Le smoke test Swing ouvre la fenêtre des trajets via une connexion TCP réelle.

Les sauvegardes du protocole 3 sont reconnues à leurs listes globales de mobilité : seul ce cache
dérivé et les anciennes couleurs de trafic sont invalidés au chargement, puis recalculés au mois
suivant. Les données de ville et le fichier original restent intacts. Les serveurs et clients doivent
être redémarrés ensemble en protocole 4 ; les plugins doivent être recompilés.

Le coût de recherche croît avec le nombre de domiciles et de cases accessibles ; le comptage croît
avec le nombre d’habitants et la longueur de leurs trajets. Aucun débit garanti n’est annoncé pour
une carte maximale entièrement peuplée. La mutualisation évite une recherche par habitant et réduit
les snapshots, mais ne remplace pas de futurs benchmarks sur de telles villes.
