# Habitants, itinéraires et circulation

## Utilisation

Cliquer **Habitants / trajets…** ou double-cliquer un domicile avec l’outil Inspection.
Choisir le domicile, puis l’habitant : son nom, son âge et la date de la journée exécutée sont affichés.
Le tableau présente ses activités et leurs heures de départ prévues. Trois vues sont disponibles :

- **Plan du matin** : itinéraire choisi avant l’exécution de la journée.
- **Parcours réalisé** : chemin effectivement suivi, avec les heures réelles et les attentes.
- **Carnet d’itinéraires** : chemins mémorisés pour le jour de semaine sélectionné, classés par score.

La case **Retour** permet d’inspecter l’autre sens. Le carnet propose chaque chemin avec son score
et sa durée nominale. Le curseur horaire positionne le point blanc d’après la trace réellement
exécutée, même lorsque l’on affiche un itinéraire prévu ou mémorisé. Les décisions prises dans les
bouchons sont listées avec leur heure. Un trajet peut rester inachevé à minuit.

Les constructions et changements de financement invalident le résultat de la journée, qui sera
recalculé au prochain jour simulé. Ils ne détruisent pas les identités et carnets des habitants.
Les routes devenues impossibles sont écartées lors de la planification suivante.

## Calendrier et habitants

Un pas de simulation serveur représente désormais **un jour civil**, à partir du lundi 1er janvier
1900. Le calendrier grégorien gère les mois et années bissextiles. Les vitesses 1, 2 et 3 exécutent
respectivement un, deux et trois jours par tick serveur ; zéro met la simulation en pause.
L’économie, la croissance, les incendies et les plugins de simulation restent mensuels, exécutés
le premier jour de chaque mois. Les réseaux d’électricité et d’eau sont actualisés chaque jour.

Le serveur exécute la journée complète minute par minute, puis publie le résultat. L’inspecteur
permet de rejouer cette journée ; il ne change pas le temps partagé entre joueurs. Il ne s’agit
pas d’une publication réseau après chaque minute.

Chaque résident des zones et arcologies alimentées est représenté, sans échantillonnage. Il reçoit
un identifiant monotone non réutilisé, un prénom, un nom et un âge initial de 6 à 85 ans. Le nom
inclut l’identifiant pour distinguer les homonymes. Les identités et carnets sont sauvegardés.
Une diminution de population retire les derniers habitants du domicile ; une croissance ultérieure
crée de nouveaux identifiants. Le vieillissement, les familles et les déménagements nominatifs ne
sont pas encore simulés.

## Programme et planification du matin

| Besoin | Programme | Départ souhaité | Temps sur place |
| --- | --- | --- | --- |
| Travail | 18–66 ans, lundi à vendredi ; commerce ou industrie occupés et alimentés | 7 h à 8 h 29 | 8 h |
| Éducation | Moins de 18 ans, lundi à vendredi ; service `education` | 8 h à 8 h 29 | 6 h |
| Soins | Une fois par semaine, jour dépendant de l’habitant ; service `health` | 10 h à 15 h 59 | 30 min |
| Courses | Tous les jours ; commerce occupé et alimenté | 17 h à 18 h 29 ; 11 h à 12 h 29 le week-end | 30 min |
| Loisirs | Tous les jours ; service `park` | 19 h à 19 h 59 ; 15 h à 15 h 59 le week-end | 45 min |
| Repos | Tous les jours, au domicile | À partir de 22 h | — |

Les services doivent être alimentés, financés et non incendiés. L’âge, l’identité et la graine
fixent les préférences horaires ; les activités sont traitées dans l’ordre du tableau. L’habitant
attend son retour réel au domicile avant de partir pour l’activité suivante. Une sortie non encore
commencée à 22 h est annulée ; les déplacements déjà en cours peuvent continuer jusqu’à minuit.
Un besoin est satisfait si l’arrivée et le séjour ont pu se terminer avant minuit. Le retour peut
être retardé sans annuler un besoin déjà satisfait. Les besoins non satisfaits n’affectent pas
encore la santé ou la croissance.

À la création, le carnet est initialisé pour les sept jours, avec les trajets applicables et
accessibles. Chaque entrée distingue jour de semaine, besoin et sens aller/retour. Le matin,
un tirage déterministe pondéré par le **carré du score** choisit les trajets : une route de score
80 a seize fois le poids d’une route de score 20. Le meilleur chemin reste préféré sans éliminer
l’exploration. Les retours sont également choisis le matin, pour la destination retenue à l’aller.

Les chemins sont stockés dans un registre propre à chaque domicile. Les carnets individuels
référencent ses indices locaux avec leurs propres scores. Il n’y a aucune mutualisation entre
domiciles. Une recherche par domicile fournit les destinations candidates ; les chemins valides
et les coûts sont partagés en lecture seule entre les workers de planification.

## Exécution et bouchons

L’exécution utilise une file d’événements ordonnée par minute, ancienneté de l’attente et identifiant.
Un seul ordonnanceur possède les réservations routières : aucun worker parallèle ne peut faire
entrer simultanément trop de véhicules sur une case.

Deux limites indépendantes s’appliquent à chaque case de route, tous sens confondus :

- **Occupation** : six habitants en mode routier au maximum par défaut.
- **Débit d’entrée** : deux admissions par minute, soit au plus 120 par heure.

Un habitant refusé attend et conserve sa place sur la case en amont. Le blocage peut donc remonter
la route, puis empêcher d’autres départs. Les mouvements réussis libèrent la case quittée et
réservent la suivante. Les traces enregistrent les heures de passage réelles, sans téléportation
lors d’un détour. Les impasses et blocages circulaires sont possibles.

À minuit, les déplacements inachevés sont marqués comme tels et pénalisés. L’habitant conserve sa
position exacte sur la grille. Le lendemain, il tente d’abord de rentrer au domicile avant ses
activités ; s’il reste enclavé, il ne se téléporte pas. Une route démolie sous un véhicule le
replace en mode marche sur la même case. La mémoire d’un trajet interrompu reste consultable.

La marche coûte cinq minutes par case, la route deux, le rail et le métro une. Les entrées/sorties
du mode routier coûtent deux minutes ; les correspondances ferroviaires cinq minutes. Les gares
et stations doivent être alimentées et financées. Les routes et rails respectent les raccordements
de pente ; les tunnels suivent les cases orthogonales. La recherche limite les trajets initiaux
à trois heures dans un sens. Les pointes du jour précédent servent d’estimation de congestion lors
de la recherche, tandis que les durées réellement exécutées viennent des mouvements et attentes.

La marche traverse les parcelles terrestres ; clôtures, voitures privées et stationnements ne sont
pas modélisés. Un changement de mode peut faire partie d’un détour. Les limites physiques décrites
ici concernent les routes ; les trains/métros n’ont pas encore de véhicules, horaires ou quotas
individuels d’embarquement. Les dépôts de bus n’ont pas encore de lignes.

## Réévaluation et apprentissage

Un habitant bloqué réévalue sa décision à intervalles d’environ huit minutes, avec un décalage
individuel de zéro à quatre minutes. Il a par défaut 45 % de chances de chercher une alternative.

1. Il examine d’abord son carnet pour ce jour, ce besoin et ce sens.
2. Une route connue doit rejoindre la même destination depuis sa position actuelle et son suffixe
   doit éviter les cases saturées. Les candidates sont tirées au sort avec le même poids de score.
3. Faute de candidate, une recherche de chemin depuis la position actuelle évite les cases bloquées.
4. Si aucun chemin ne convient, il reste dans la file et réévaluera plus tard.

Une route initiale reçoit 80 points. Chaque tranche de cinq minutes d’attente, arrondie au supérieur
à la fin de la tentative, retire un point à la route utilisée. L’abandon d’une tentative retire
12 points supplémentaires. Une réussite sans attente rapporte trois points, avec un plafond de
100. Une alternative nouvellement découverte n’entre dans le carnet qu’après une arrivée réussie,
avec un score initial de 20. Les tentatives connues utilisées après une déviation sont aussi
pénalisées lorsqu’elles échouent ; le choix initial n’absorbe pas seul toutes les pénalités.

Le premier de chaque mois, les scores inférieurs à cinq sont éliminés et seuls les cinq meilleurs
chemins sont conservés par jour, besoin et sens. Si aucun itinéraire initial valide ne subsiste,
la planification peut en recréer un afin de ne pas condamner définitivement ce besoin. Les chemins
non référencés par les carnets, plans ou traces de la journée sont retirés du registre du domicile.
Les nouveaux détours appris pendant le mois peuvent dépasser cinq jusqu’au prochain élagage.

Les valeurs sont regroupées dans `City.trafficRules` (`TrafficRules`) et sauvegardées. Elles sont
configurables dans le modèle ; aucun panneau de réglage de ces coefficients n’est encore fourni.

## Affichage du trafic

Le calque Trafic montre les passages effectivement admis par heure, ou leur pointe journalière
quand l’inspecteur est fermé. Les voitures animées restent une illustration de cette charge :
elles ne représentent pas individuellement les habitants ni une file exacte à la minute choisie.
Le point blanc de l’inspecteur, lui, provient de la trace temporelle réelle. Les pics d’occupation
sont conservés pour vérifier les contraintes du moteur.

La case Voitures permet de masquer l’animation. Elle se fige en pause et s’arrête sur les autres
calques ou quand la fenêtre est masquée. Elle reste une surcouche indépendante du cache du paysage.

## Persistance, parallélisme et limites

Le protocole est en **version 6** : redémarrer serveur et clients ensemble et recompiler les plugins.
Les anciennes sauvegardes sans habitants persistants initialisent leurs identités au prochain jour.
Les anciens caches de mobilité sont invalidés, sans réécrire le fichier d’origine. Les sauvegardes
nouvelles conservent identités, scores, positions nocturnes et traces de la dernière journée.
Le chargement contrôle les références de chemins, identifiants uniques, scores et traces.

La planification reste parallèle via un nombre borné de coroutines sur `Dispatchers.Default`.
Chaque worker possède les données d’un domicile. L’exécution temporelle est coordonnée après leur
achèvement ; elle n’est pas parallélisée entre domiciles puisqu’ils partagent les capacités routières.
Le résultat est déterministe pour un même état et une même graine. Les boucles CPU vérifient
l’annulation. Le serveur publie seulement un état complet.

La mémoire et le temps de calcul augmentent avec la population, les variantes d’itinéraires et
le nombre d’événements de circulation. Les snapshots restent complets et conservent la limite de
64 Mio du protocole et des sauvegardes : cette version ne garantit pas de performances pour une
carte maximale entièrement peuplée. Les files sont au niveau des cases, sans voies séparées,
feux ni priorité physique aux intersections. Les capacités d’emplois, de patients et d’élèves ne
limitent pas encore le choix des destinations.
