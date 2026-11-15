# Fidélité et règles actuelles

La référence fonctionnelle est le [manuel de SimCity 2000](https://www.retrogames.cz/manualy/DOS/SimCity_2000_-_Manual_-_PC.pdf).
Ce document décrit la réalisation actuelle, sans revendiquer une reproduction des algorithmes
propriétaires ou une compatibilité avec les données du jeu original. Les prix et coefficients de
ce projet sont des paramètres de gameplay explicitement choisis et modifiables.

| Domaine | Réalisé | Différences / reste à développer |
| --- | --- | --- |
| Terrain | Sommets partagés, pentes par case, rivière, arbres, élévation/abaissement avec raccords, aplanissement à altitude choisie, ponts | 13 altitudes (0–12), pas de lacs/océan configurables, d’érosion ni de nivellement par rectangle dans l’interface |
| Vue | Isométrie, altitude, bâtiments procéduraux et sprites, zoom, rotation par quarts de tour, inspection, mini-carte | Voitures animées illustrant la densité ; pas de sons |
| Zonage | R/C/I léger et dense, croissance/déclin, demande, desserte, fiscalité | Développement par case ; pas de regroupement des lots en immeubles multi-cases ni de styles par époque |
| Électricité | 9 familles : charbon, fioul, gaz, nucléaire, éolien, solaire, hydro, micro-ondes, fusion | Barrages neufs sur rampe couverte d’eau, sans modèle de débit ; capacités abstraites ; pas de durée de vie, de renouvellement des centrales, de saisonnalité ni d’accident nucléaire |
| Eau | Pompes alimentées, conduites, château d’eau, dessalement, épuration | Château d’eau traité comme une source fixe, sans réservoir dynamique ; pas de sécheresse ni de salinité |
| Transports | Journées civiles, habitants nommés, trajets exécutés, files par case, détours et mémoire hebdomadaire à scores, inspection des plans et traces | Pas de voies séparées, feux, horaires de trains, lignes de bus, autoroutes ou tunnels routiers ; [règles détaillées](MOBILITY.md) |
| Services | Police, pompiers, hôpitaux, écoles, universités, bibliothèques, prison | Champs d’influence agrégés, pas de capacités de patients/élèves ou d’agents individuels |
| Loisirs | Parcs, musée, zoo, stade | Effets sur valeur foncière et croissance, visites individuelles mais sans capacité d’accueil |
| Économie | Impôts, entretien, financement global, dette/intérêts/remboursement | Pas d’ordonnances, de financement par service ni de commerce régional détaillé |
| Ports et aéroports | Emprises fixes, bonus de demande à proximité ; port sur une rive | Pas de zones spécialisées à développement progressif ni de connexions interurbaines |
| Récompenses | Maison du maire et hôtel de ville constructibles | Pas de déblocage par paliers, de statue ou de base militaire |
| Arcologies | Quatre types, disponibilité en 2050, habitants individuels, naissances, décès, déménagements et entretien | Population initialisée à la capacité du catalogue ; pas de départ des Launch ni de modèle interne d’emplois/services |
| Catastrophes | Incendies, propagation, protection des pompiers, destruction cohérente des emprises | Pas de séisme, inondation, tornade, crash, émeute ou monstre |
| Multijoueur | Ville partagée en temps réel, autorité serveur, refus des conflits, reconnexion manuelle | Pas de territoires, comptes, rôles, messagerie ou historique d’annulation |
| Sauvegarde | JSON, reprise, autosauvegarde annuelle, sauvegarde manuelle et à l’arrêt | Pas d’import/export SC2, de scénarios historiques ni de format binaire original |
| Plugins | Catalogue, règles par case, systèmes globaux, état persistant, JAR exemple | Pas de sandbox, de rechargement à chaud ou de modules graphiques client |

## Coefficients de cette version

La capacité maximale par case est 12/80 habitants en résidentiel, 8/60 emplois en commercial et
12/70 en industriel, respectivement léger/dense. Sans eau, le plafond tombe à 60 %.
La croissance exige une case plane, de l’électricité, une infrastructure de transport à trois cases au plus,
une demande positive et une pollution inférieure à 65 sauf en industriel. Les zones mal desservies
ne reçoivent plus de nouveaux habitants ; les départs des résidents dépendent de leur satisfaction.
La demande réagit aux habitants, emplois et impôts.

La croissance résidentielle propose des arrivants uniquement lors du calcul mensuel. Leur installation
exige un chemin depuis une route située sur un des quatre bords de la carte : route puis marche,
selon les règles de relief, d’eau et d’incendie des déplacements ordinaires. La portion à pied n’a
pas de limite de distance supplémentaire. Sans chemin, aucune occupation ni aucun citoyen n’est
créé et aucune demande n’est conservée en attente ; le mois suivant recalcule la croissance.
Les habitants présents et les naissances ne sont pas concernés. Cette validation ne simule pas
encore le trajet physique d’arrivée et ne change pas le peuplement initial des bâtiments spéciaux.

L’influence des services décroît avec la distance de Manhattan. Les services non alimentés ou
financés à zéro n’améliorent pas le quartier. Les revenus mensuels sont
`(habitants + emplois) × tauxImpôt / 12`. L’entretien et les intérêts sont soustraits chaque mois.
La ville peut être déficitaire mais ne peut construire sans les fonds nécessaires ; il n’y a pas
de séquence de faillite automatique.

Un incendie non maîtrisé progresse chaque mois, réduit les occupants et détruit toute l’emprise
lorsqu’il dépasse six mois. Les casernes actives raccourcissent sa durée. Les probabilités sont
stables pour une graine et un mois donnés. L’activation des catastrophes concerne les nouvelles
ignitions et la propagation, pas l’extinction instantanée d’un feu existant.

## Évolutions naturelles vers davantage de fidélité

1. Développement RCI sur lots multi-cases avec plusieurs variantes architecturales.
2. Voies et feux aux intersections, horaires et capacités d’embarquement, lignes de bus, quotas de destinations et besoins physiologiques persistants.
3. Réservoirs d’eau, demande proportionnelle à la population, vieillissement des centrales.
4. Budgets par service, ordonnances, récompenses et échanges avec les villes voisines.
5. Catastrophes supplémentaires, animations, scénarios et équilibrage à long terme.

Les interfaces de règles et les modules séparés servent à intégrer ces évolutions sans réécrire
la couche TCP ni rendre l’interface Swing responsable des règles de simulation.

La satisfaction individuelle tient désormais compte des besoins, déplacements, services, nuisances,
emploi et budget. Elle agit sur les déménagements collectifs par case. Le « foyer » reste tout
l’immeuble, pas une famille démographique ; salaires, allocations, pensions, loyers, qualité du
logement et bruit sont des modèles simplifiés documentés dans ARCHITECTURE.md. L’attachement
repose sur l’ancienneté ; les liens familiaux et relations étudiantes sont simulés séparément. Un contrat ne garantit pas la présence
au travail et un accès physique à un service ne modélise pas sa file d’attente ni sa capacité.
