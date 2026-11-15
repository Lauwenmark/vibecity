# Évolution du modèle social et économique

Document de travail ouvert le 15 novembre 2026. Les orientations ci-dessous ont reçu un accord
de principe ; leurs règles détaillées doivent être enrichies au cours de conversations séparées
avant l’implémentation. L’objectif est de relier les parcours individuels à des décisions municipales
compréhensibles : logement, emplois, éducation, services et santé.

## Point de départ

Le fonctionnement existant est décrit dans [Cycle de vie et instruction](LIFE_CYCLE.md).
Il comprend vieillissement, mortalité, NI et CA, scolarité, cursus supérieurs, diplômes,
couples, déménagements, grossesses, naissances, salaires abstraits et pensions.
Les paramètres de cette première version sont des valeurs d’équilibrage à éprouver en partie.

Les principales limites auxquelles répondent les pistes suivantes sont :

- Tous les occupants d’une case partagent encore l’unité de logement et de budget, même s’ils
  appartiennent à plusieurs familles.
- Le salaire ne dépend pas d’un emploi effectivement obtenu. Les destinations de travail
  existantes servent à la mobilité, sans constituer un marché du travail complet.
- Les établissements éducatifs ne dépendent pas d’un personnel qualifié et n’ont pas de capacité
  d’accueil scolaire limitant les inscriptions.
- La santé individuelle, la dépendance, les intérêts personnels et la reprise d’études ne sont
  pas encore modélisés.

La documentation des comportements existants reste dans `LIFE_CYCLE.md`. Le présent document
conserve les propositions et décisions de conception des évolutions futures.

## Suivi des discussions

Les identifiants des axes et des questions sont stables pour faciliter les reprises entre conversations.
Chaque axe commence au statut **À discuter**. Il peut ensuite passer à **En discussion**, puis à
**Spécifié** lorsque ses règles et ses cas limites ont été retenus explicitement. L’implémentation
fera l’objet d’une demande distincte ; un accord sur une direction ne fixe pas tous ses paramètres.

| Axe | Sujet | Statut | Résultat attendu de la discussion |
|---|---|---|---|
| M | Ménages et logements | À discuter | Unités de ménage et de logement, budgets et transitions familiales |
| E | Emplois et qualifications | À discuter | Postes, recrutement, revenus et parcours professionnels |
| S | Personnel et capacité des services | À discuter | Effectifs, financement, capacité et qualité de service |
| H | Santé et autonomie | À discuter | États de santé, besoins d’aide et conséquences quotidiennes |
| P | Préférences et réorientation | À discuter | Intérêts, choix de parcours et formation continue |

## Axe M Ménages et logements

**Intérêt pour le jeu.** Un immeuble pourrait accueillir plusieurs ménages autonomes. Un jeune
adulte, une colocation et un couple avec enfants auraient des budgets et des besoins différents.
Une pénurie de logements pourrait retarder le départ des parents et la cohabitation des couples.

**Piste proposée.** Distinguer la personne, ses liens familiaux, son ménage, son logement et le
bâtiment qui contient ce logement. Une recherche comparerait place disponible, coût et accès aux
activités. Un ménage pourrait changer de logement sans perdre son identité ni ses liens familiaux.

**Questions ouvertes**

- **M1** — Comment définir un ménage ? Un couple, une famille et une colocation ont-ils les mêmes
  règles de partage des ressources ? Peut-on rester en couple dans deux ménages différents ?
- **M2** — Comment déduire le nombre et la taille des logements d’une case ou d’un bâtiment ?
  Comment distinguer capacité normale et surpeuplement ?
- **M3** — Quels revenus, dépenses et réserves financières appartiennent à la personne ou au ménage ?
  Comment aider un étudiant vivant ailleurs ou un proche dépendant ?
- **M4** — Comment se déroulent recherche, réservation et emménagement lorsque plusieurs ménages
  convoitent le même logement ? Que se passe-t-il sans logement accessible ou abordable ?
- **M5** — Comment traiter départ des enfants, séparation, décès, orphelins et regroupement familial ?
- **M6** — Comment convertir les occupants des sauvegardes existantes sans inventer arbitrairement
  une parenté ni provoquer une vague immédiate de déménagements ?

**Situations à examiner.** Deux familles partagent un immeuble sans partager leurs finances ;
un couple quitte deux domiciles parentaux ; un parent meurt en laissant des mineurs ; un logement
est détruit ; deux ménages souhaitent emménager dans le dernier logement disponible.

**Dépendances.** Le budget peut d’abord utiliser les revenus abstraits actuels. Il devra ensuite
recevoir les revenus du travail et les pensions sans imposer de refaire le modèle de logement.

## Axe E Emplois et qualifications

**Intérêt pour le jeu.** L’instruction ouvrirait des perspectives professionnelles concrètes.
Une ville pourrait manquer de médecins tout en comptant des chômeurs, ou perdre des diplômés
faute de postes correspondant à leur formation.

**Piste proposée.** Les entreprises et services offriraient des postes. Le recrutement pourrait
prendre en compte diplôme, NI, expérience professionnelle et accessibilité. Ces notions resteraient
distinctes : le diplôme atteste un cursus, le NI représente les connaissances générales et
l’expérience traduit une pratique professionnelle.

**Questions ouvertes**

- **E1** — Quels employeurs et métiers représenter ? Comment les zones commerciales et industrielles
  existantes se traduisent-elles en entreprises et en postes ?
- **E2** — Quels postes exigent un diplôme, un NI minimum ou une expérience particulière ?
  Quelles équivalences et possibilités de sous-qualification accepter ?
- **E3** — À quelle fréquence rechercher un emploi et comment départager les candidatures ?
  Comment intégrer distance, temps de trajet, salaire et stabilité ?
- **E4** — D’où provient l’argent des salaires ? Quel budget attribuer aux employeurs, quels revenus
  pendant le chômage, et quel effet sur les pensions et les impôts ?
- **E5** — Comment traiter absences, changements d’employeur, licenciements, fermeture, retraite
  et départ de la ville ? Un trajet impossible entraîne-t-il immédiatement une perte d’emploi ?
- **E6** — Comment remplacer progressivement les salaires garantis sans déséquilibrer toutes les
  familles d’une ville existante ?

**Situations à examiner.** Un diplômé accepte provisoirement un poste moins qualifié ; deux
candidats postulent au même emploi ; une entreprise ferme ; une liaison de transport est coupée ;
un salarié prend sa retraite, libère son poste et commence à percevoir une pension.

**Dépendances.** L’axe M définit qui reçoit et partage le revenu. L’axe S utilisera ce recrutement
pour les établissements publics. L’axe P pourra ensuite enrichir les choix de carrière.

## Axe S Personnel et capacité des services

**Intérêt pour le jeu.** Construire une école ou un hôpital ne suffirait plus à garantir son
fonctionnement. Effectifs, qualifications et financement détermineraient sa capacité et la qualité
du service. Le joueur pourrait identifier une pénurie de personnel et y répondre.

**Piste proposée.** Définir les postes nécessaires à un établissement, sa capacité nominale et
son fonctionnement lorsque des postes restent vacants. Une école surchargée ou insuffisamment
pourvue en enseignants pourrait ralentir l’apprentissage ; un hôpital pourrait traiter moins de patients.

**Questions ouvertes**

- **S1** — Quels services intégrer en premier et quelles professions leur sont indispensables ?
- **S2** — Comment effectifs, présence effective, qualifications et budget déterminent-ils capacité
  et qualité ? Quels effets doivent être immédiats, et lesquels doivent être progressifs ?
- **S3** — Comment attribuer les places, gérer les listes d’attente et les transferts entre établissements ?
- **S4** — Comment faire démarrer une ville qui ne possède encore ni enseignants ni diplômés ?
  L’arrivée de professionnels extérieurs est une piste à préciser.
- **S5** — Comment articuler la couverture géographique existante avec les places et les services
  réellement rendus, sans compter deux fois le même bénéfice ?

**Situations à examiner.** La première école ouvre sans enseignant local ; un enseignant prend
sa retraite ; une université ferme en cours de cursus ; un quartier dispose d’une école proche
mais saturée ; une réduction budgétaire dure un mois puis est annulée.

**Dépendances.** L’axe E fournit les postes et les qualifications. Les règles d’apprentissage
existantes devront recevoir un effet de qualité sans effacer les acquis ou diplômes.

## Axe H Santé et autonomie

**Intérêt pour le jeu.** Pollution, accès aux soins et vieillissement auraient des conséquences
sur les parcours individuels. Les besoins des enfants et personnes dépendantes donneraient une
utilité aux crèches, aux aidants et aux services d’assistance.

**Piste proposée.** Introduire un état de santé et distinguer celui-ci de l’autonomie quotidienne.
Une mauvaise santé pourrait causer des absences, limiter les déplacements ou le travail et
modifier le risque de décès. Une personne dépendante pourrait avoir besoin d’un accompagnement.

**Questions ouvertes**

- **H1** — Une valeur de santé et un niveau d’autonomie suffisent-ils, ou faut-il quelques états
  temporaires distincts ? Quelle granularité apporte un intérêt de jeu lisible ?
- **H2** — Comment pollution, âge, conditions de vie, prévention et soins font-ils évoluer ces états ?
  Comment préserver la courbe de mortalité et la limite d’âge déjà retenues ?
- **H3** — Quand un soin devient-il nécessaire, et comment distinguer accès, attente, consultation
  et résultat du traitement ?
- **H4** — Qui accompagne un enfant ou une personne dépendante ? Quelles conséquences sur les
  horaires, l’assiduité et le revenu de l’aidant ?
- **H5** — Comment éviter qu’un problème temporaire déclenche une cascade sans issue de chômage,
  perte de logement et aggravation de la santé ?

**Situations à examiner.** Une maladie interrompt les études pendant plusieurs semaines ; un
parent isolé n’a pas de solution de garde ; une personne âgée vit loin de ses proches ; un hôpital
sature ; un quartier reste longtemps exposé à la pollution.

**Dépendances.** Les axes M et S déterminent l’aide familiale et les soins disponibles. L’axe E
doit distinguer une absence temporaire d’une sortie définitive du travail.

## Axe P Préférences et réorientation

**Intérêt pour le jeu.** À qualifications égales, les habitants pourraient suivre des trajectoires
différentes. Les intérêts personnels orienteraient les choix, tandis que la formation continue
permettrait de rebondir après un échec ou une évolution du marché du travail.

**Piste proposée.** Quelques intérêts relativement stables — sciences, technique, relations humaines,
arts — influenceraient les choix sans les imposer. La reprise d’études, la reconversion et
l’apprentissage au travail compléteraient le cursus initial.

**Questions ouvertes**

- **P1** — Quels intérêts représenter, comment les attribuer et peuvent-ils évoluer ?
- **P2** — Quel poids donner à l’intérêt, aux chances de réussite, au revenu espéré et aux contraintes
  familiales dans le choix d’une filière ou d’un métier ?
- **P3** — Comment reprendre des études après un abandon ? Quels acquis conserver, quels horaires
  proposer et comment financer cette période ?
- **P4** — Quelles connaissances le travail entretient-il ? Faut-il des compétences spécialisées
  en plus du NI, sans multiplier inutilement les statistiques ?
- **P5** — Comment conserver des perspectives aux habitants ayant une faible CA ou un premier
  parcours défavorable ?

**Situations à examiner.** Un ancien étudiant reprend ses études à trente ans ; un travailleur
se reconvertit après une fermeture ; une personne préfère un métier moins rémunéré ; un parent
souhaite étudier sans interrompre entièrement son activité.

**Dépendances.** Les intérêts peuvent être discutés dès maintenant. Les effets de la reconversion
sur revenus, emplois et famille demandent des contrats clairs avec M et E.

## Ordre de travail proposé

L’ordre suggéré est **M → E → S → H**, avec **P** à discuter en parallèle sur le plan conceptuel
et à intégrer lorsque ses dépendances sont assez définies. Cet ordre n’est pas une décision de
réalisation : chaque discussion peut le revoir en explicitant les conséquences pour les autres axes.

M fournit les unités de logement et de budget ; E donne une origine aux revenus et aux qualifications
recherchées ; S relie ce personnel aux services municipaux ; H ajoute les besoins de santé et d’aide.
La conception doit permettre à chaque étape de fonctionner avec des valeurs provisoires explicites
pour les axes encore absents.

## Questions communes

- **T1 Temps et argent** — Fixer les unités des revenus, loyers, budgets et durées. Distinguer
  calcul quotidien, versement mensuel et évolution annuelle. Préciser l’origine et la destination
  de chaque flux financier.
- **T2 Boucles de dépendance** — Prévoir le démarrage et la reprise après une crise. Une ville
  sans enseignants doit pouvoir en obtenir ; une pénurie de logements ne doit pas rendre toute
  embauche impossible sans aucune action corrective.
- **T3 Mobilité** — Définir ce que signifie « accessible » et comment les retards, absences,
  changements de domicile et déplacements d’accompagnement affectent les autres systèmes.
- **T4 Lisibilité** — Pour chaque conséquence, déterminer l’explication visible par le joueur
  et les leviers municipaux disponibles. Prévoir les indicateurs avant de multiplier les règles.
- **T5 Persistance et simulation** — Préserver identités, liens et historiques ; définir les
  migrations des sauvegardes, l’ordre des événements et la reproductibilité des tirages.
- **T6 Coût et validation** — Identifier ce qui doit être individuel, agrégé ou calculé seulement
  lors d’un événement. Prévoir des scénarios de reprise après sauvegarde, de charge et d’équilibrage
  sur plusieurs générations, en plus des tests de règles isolées.

## Reprendre le document dans une autre conversation

Commencer par lire ce document et la documentation actuelle de l’axe concerné. Indiquer les
questions traitées, puis enrichir la section correspondante. Une discussion peut préciser une
option sans la transformer en décision retenue.

À la fin d’une discussion, mettre à jour :

1. Les règles retenues, avec leurs conditions, unités et cas limites.
2. Les alternatives encore ouvertes et les motifs des choix abandonnés lorsqu’ils sont utiles.
3. Les dépendances ou contradictions avec les autres axes et les exemples à vérifier.
4. Le statut de l’axe et le journal ci-dessous, sans déclarer une règle implémentée avant sa réalisation.

Une demande de reprise peut être : « Lis `docs/SOCIAL_SIMULATION_WORKING_PLAN.md`, puis discutons
M1 à M3 et enrichissons le document, sans modifier la simulation. »

## Journal des décisions

| Date | Portée | Décision ou état |
|---|---|---|
| 2026-11-15 | Ensemble | Accord de principe sur les cinq orientations ; création d’un document commun à enrichir dans plusieurs conversations avant implémentation. |
| 2026-11-15 | Ordre de travail | M → E → S → H, puis intégration de P selon ses dépendances : proposition initiale, encore à arbitrer. |

Aucune règle détaillée des cinq axes n’est encore arrêtée dans ce document.
