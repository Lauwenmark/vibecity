# Cycle de vie et instruction

Ces paramètres constituent un premier équilibrage de jeu, pas un modèle démographique réel.
Un jour simulé correspond à une journée civile. Les règles probabilistes sont indépendantes
de la vitesse d’affichage et du nombre de workers.

## Âge et mortalité

Chaque personne possède une date de naissance. L’âge change le jour anniversaire ; une naissance
le 29 février a son anniversaire le 28 février les années non bissextiles.
Au début de chaque journée, une mortalité annuelle de 0,02 % avant 60 ans, puis de
`min(95 %, 0,5 % × 2^((âge − 60)/8))`, est convertie en risque quotidien par
`1 − (1 − pAnnuel)^(1/365,2425)`. Le décès est certain au 121e anniversaire.
Les personnes décédées sont retirées du logement ; leurs identifiants restent utilisables
comme références généalogiques. Un partenaire survivant redevient disponible.

## NI, CA et école

Le NI est compris entre 0 et 150, la CA entre 0,5 et 1,5. Un nouveau-né reçoit un NI entre
0 et 5 et une CA aléatoire fixe. Il n’effectue pas de déplacements autonomes avant trois ans.
De 3 ans inclus à 18 ans exclus, l’école est prévue du lundi au vendredi : départ entre
8 h et 8 h 29, six heures sur place. Une présence accomplie ajoute `0,0225 × CA` au NI,
avec un plafond scolaire de 100. Une bibliothèque ne remplace pas l’école.

## Études supérieures

À 18 ans, un habitant admissible a 65 % de chances de choisir les études. Il entre directement
dans la vie active si son NI ne donne accès à aucune filière ou si aucune université alimentée,
financée, sans incendie et accessible par un itinéraire n’existe. Le choix n’est pas renouvelé
quotidiennement. Une filière admissible est choisie au hasard, avec inscription au campus accessible
le plus proche en temps de trajet. L’inscription reste attachée à ce campus, même après un déménagement.
Une fermeture ou une coupure peut donc entraîner des absences ; aucun transfert automatique n’est prévu.

| Filière | NI minimum | Années | Salaire quotidien après diplôme |
|---|---:|---:|---:|
| Enseignement | 60 | 3 | 65 |
| Informatique | 70 | 3 | 75 |
| Droit | 80 | 5 | 85 |
| Médecine | 90 | 7 | 95 |

Les cours suivent les mêmes jours et horaires que l’école. L’année commence à l’inscription
et se termine la veille de son anniversaire civil, puis recommence ; les années bissextiles
sont prises en compte. Il n’y a pas encore de vacances scolaires.

Une arrivée au bon campus permettant d’accomplir les six heures avant minuit valide une présence.
Elle ajoute `0,018 × CA` au NI et des points selon :

`(0,8 + 0,2 × CA + NI/500) × (1 + 0,005 × sériePrésences) × (1 − 0,01 × retard)`.

La série de présences est plafonnée à 30 jours de cours. À partir de la troisième absence
consécutive, chaque absence ajoute un point de retard, plafonné à 20. Chaque présence résorbe
un point de retard. Une absence ne rapporte aucun point et interrompt la série de présences.
Les week-ends ne modifient ni les séries ni les compteurs de cours.

Il faut **190 points et 70 % de présence** pour valider une année. Le score annuel est remis à zéro,
le NI reste acquis. Un échec autorise un redoublement de cette année ; un second échec dans la même
année entraîne l’entrée dans la vie active sans diplôme. La dernière année réussie donne le diplôme,
qui est conservé même si le NI diminue ensuite. Le dernier résultat reste visible dans l’inspecteur.

## Travail, culture et retraite

Un travailleur reçoit 50 par jour sans diplôme, ou le salaire de sa filière. Ce revenu abstrait
ne dépend pas d’un poste disponible : aucun nouveau marché du travail n’est introduit.
Les destinations de travail et contrats de trajet existants restent utilisés pour la mobilité.
L’absence de poste peut laisser un besoin de travail insatisfait, mais n’enlève pas le salaire.
Les élèves et étudiants ne touchent pas de salaire.

Le NI des travailleurs et retraités perd `0,0015 / CA` par jour. Une visite éducative hebdomadaire
est prévue vers une bibliothèque, un musée, une école ou une université : une heure sur place
rapporte `0,03 × CA`. Une visite de loisirs accomplie au musée rapporte `0,0225 × CA` si aucun gain
culturel n’a déjà été obtenu ce jour-là. Le musée conserve son service de loisirs et sa couverture
locale. Les cours/visites ne donnent pas de points si le trajet ne permet pas d’accomplir le séjour.

À 65 ans, l’habitant cesse de travailler et reçoit les deux tiers de son dernier salaire,
avec un minimum de 20. Salaires, pensions et aide aux mineurs sans adulte sont des revenus privés
abstraits : ils ne sont pas débités du budget municipal. La couverture locale éducative et ses effets
sur satisfaction/valeur foncière restent en place en complément du NI individuel.

## Couples, logements et enfants

Chaque premier du mois, un adulte sorti des études et sans partenaire a 4 % de chances de chercher
une relation. Le partenaire doit être adulte, disponible, hors études, à moins de 21 ans d’écart,
sans lien parent/enfant ni ancêtre commun connu parmi parents et grands-parents. Jusqu’à 32 candidats
sont examinés dans un ordre déterministe ; la recherche peut échouer. Les couples peuvent être de même sexe.

La volonté de déménager est de 2 % par mois, portée à 15 % pour un couple vivant séparément ou
chez les parents. Une seule décision est prise par couple. Le partenaire indépendant est rejoint
si possible ; sinon le couple cherche un logement vacant commun. Les enfants mineurs suivent leurs
parents si aucun de leurs parents présents dans le logement d’origine n’y reste.

Un déménagement exige une place suffisante et un revenu après impôts couvrant le budget du logement.
Les nouveaux logements vacants doivent être alimentés, approvisionnés en eau, plats et sans incendie.
Les habitants en déplacement ne sont pas téléportés. L’échec d’une recherche conserve le domicile actuel.
La migration préexistante pour insatisfaction reste possible et concerne tous les occupants d’une case.

Pour un couple de sexes opposés cohabitant, une femme de 18 à 44 ans sans grossesse en cours a une
probabilité mensuelle de conception de `2,5 % / (1 + enfants déjà nés)` avant 35 ans, puis de
`1,2 % / (1 + enfants déjà nés)`. La naissance survient neuf mois civils plus tard. La grossesse
continue si le père décède ou quitte la ville. Une naissance peut créer du surpeuplement, sans être
supprimée par une limite de capacité. Un foyer ne contenant plus que des mineurs reste dans son
logement avec une aide abstraite de 30 par personne et par jour ; l’adoption n’est pas simulée.

## Sauvegardes et limites

Les anciennes personnes conservent identité, âge, position et historique. Une date de naissance
compatible avec leur âge et des valeurs NI/CA sont initialisées de manière déterministe au premier
jour. Les adultes initiaux ont des connaissances et, pour certains, un diplôme cohérents avec leur âge.
Les anciens occupants d’une même case et les nouvelles arrivées économiques ne sont pas supposés
parents ou partenaires : seuls les liens établis par la simulation sont enregistrés.

Une case résidentielle reste l’unité de logement et de budget collectif ; il n’existe pas encore
de découpage d’un immeuble en appartements. Les filiations sont individuelles, et plusieurs familles
peuvent partager une case. Les nouveaux liens, grossesses, résultats scolaires et revenus sont sauvegardés
et transmis dans les états complets comme dans les différences réseau (protocole 11).

Les tests couvrent seuils, accès réel aux établissements, assiduité, examens, redoublement, diplômes,
anniversaires, décès, grossesses, couples/déménagements, persistance et déterminisme parallèle.
