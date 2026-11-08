# Fidélité et règles actuelles

La référence fonctionnelle est le [manuel de SimCity 2000](https://www.retrogames.cz/manualy/DOS/SimCity_2000_-_Manual_-_PC.pdf).
Ce document décrit la réalisation actuelle, sans revendiquer une reproduction des algorithmes
propriétaires ou une compatibilité avec les données du jeu original. Les prix et coefficients de
ce projet sont des paramètres de gameplay explicitement choisis et modifiables.

| Domaine | Réalisé | Différences / reste à développer |
| --- | --- | --- |
| Terrain | Sommets partagés, pentes par case, rivière, arbres, élévation/abaissement avec raccords, aplanissement à altitude choisie, ponts | 13 altitudes (0–12), pas de lacs/océan configurables, d’érosion ni de nivellement par rectangle dans l’interface |
| Vue | Isométrie, altitude, bâtiments procéduraux, zoom, inspection, mini-carte | Pas de rotation, de sprites originaux, d’animations de véhicules ou de sons |
| Zonage | R/C/I léger et dense, croissance/déclin, demande, desserte, fiscalité | Développement par case ; pas de regroupement des lots en immeubles multi-cases ni de styles par époque |
| Électricité | 9 familles : charbon, fioul, gaz, nucléaire, éolien, solaire, hydro, micro-ondes, fusion | Barrages neufs sur rampe couverte d’eau, sans modèle de débit ; capacités abstraites ; pas de durée de vie, de renouvellement des centrales, de saisonnalité ni d’accident nucléaire |
| Eau | Pompes alimentées, conduites, château d’eau, dessalement, épuration | Château d’eau traité comme une source fixe, sans réservoir dynamique ; pas de sécheresse ni de salinité |
| Transports | Routes, rails, ponts, métro souterrain, gares, bus, stations de métro | Rampes simples dans le sens de la montée ; accessibilité par distance ; trafic agrégé ; pas de recherche d’itinéraire, d’autoroute, d’échangeur ou de tunnel routier |
| Services | Police, pompiers, hôpitaux, écoles, universités, bibliothèques, prison | Champs d’influence agrégés, pas de capacités de patients/élèves ou d’agents individuels |
| Loisirs | Parcs, musée, zoo, stade | Effets sur valeur foncière et croissance, sans fréquentation détaillée |
| Économie | Impôts, entretien, financement global, dette/intérêts/remboursement | Pas d’ordonnances, de financement par service ni de commerce régional détaillé |
| Ports et aéroports | Emprises fixes, bonus de demande à proximité ; port sur une rive | Pas de zones spécialisées à développement progressif ni de connexions interurbaines |
| Récompenses | Maison du maire et hôtel de ville constructibles | Pas de déblocage par paliers, de statue ou de base militaire |
| Arcologies | Quatre types, disponibilité en 2050, population et entretien | Population fixe si alimentées ; pas de départ des Launch ni de modèle interne d’emplois/services |
| Catastrophes | Incendies, propagation, protection des pompiers, destruction cohérente des emprises | Pas de séisme, inondation, tornade, crash, émeute ou monstre |
| Multijoueur | Ville partagée en temps réel, autorité serveur, refus des conflits, reconnexion manuelle | Pas de territoires, comptes, rôles, messagerie ou historique d’annulation |
| Sauvegarde | JSON, reprise, autosauvegarde annuelle, sauvegarde manuelle et à l’arrêt | Pas d’import/export SC2, de scénarios historiques ni de format binaire original |
| Plugins | Catalogue, règles par case, systèmes globaux, état persistant, JAR exemple | Pas de sandbox, de rechargement à chaud ou de modules graphiques client |

## Coefficients de cette version

La capacité maximale par case est 12/80 habitants en résidentiel, 8/60 emplois en commercial et
12/70 en industriel, respectivement léger/dense. Sans eau, le plafond tombe à 60 %.
La croissance exige une case plane, de l’électricité, une infrastructure de transport à trois cases au plus,
une demande positive et une pollution inférieure à 65 sauf en industriel. Les zones mal desservies
se dépeuplent progressivement. La demande réagit aux habitants, emplois et impôts.

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
2. Graphe de transport avec affectation des trajets, congestion, capacités et correspondances.
3. Réservoirs d’eau, demande proportionnelle à la population, vieillissement des centrales.
4. Budgets par service, ordonnances, récompenses et échanges avec les villes voisines.
5. Catastrophes supplémentaires, animations, scénarios et équilibrage à long terme.

Les interfaces de règles et les modules séparés servent à intégrer ces évolutions sans réécrire
la couche TCP ni rendre l’interface Swing responsable des règles de simulation.
