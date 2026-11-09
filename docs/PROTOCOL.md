# Protocole TCP v8

Chaque trame est un objet JSON UTF-8 terminé par LF (`\n`). CRLF est également accepté. Les retours
à la ligne dans les chaînes sont échappés par JSON. Aucune sérialisation Java n’est utilisée.
Le serveur accepte des requêtes de 8 192 caractères maximum et le client des réponses jusqu’à la limite des chaînes JVM, afin de recevoir les grandes villes. Une fin de connexion avant LF ne valide jamais une commande partielle.

## Ouverture et cycle de vie

Envoyer dans les 10 secondes :

```json
{"type":"HELLO","version":8,"name":"Alice","token":""}
```

Le nom contient 1 à 24 lettres, chiffres, espaces, `_` ou `-`. La version doit correspondre.
Un jeton configuré côté serveur doit correspondre. Un HELLO invalide ferme la connexion.

Le serveur répond `WELCOME` avec `catalog`, puis `STATE` avec `city` et `players`.
Les champs omis des requêtes prennent les valeurs par défaut du modèle ; les champs inconnus
sont refusés. Les champs optionnels des réponses sont sérialisés pour faciliter l’inspection.

Après HELLO, chaque requête porte un `id` strictement croissant, positif et propre à la connexion.
Un ID réutilisé est refusé. Cela protège contre la répétition accidentelle **dans la même session**,
pas contre un rejeu après reconnexion. Les commandes valides s’appliquent à l’état courant, sans
précondition de révision. Les échecs métier renvoient `ERROR` et la session reste utilisable.
Les trames illisibles, trop grandes, les débits supérieurs à 100 requêtes par seconde par session
ou l’absence de requête pendant 45 secondes ferment la connexion.

Le client Swing émet un `PING` toutes les 15 secondes. Les messages sont écrits par une seule
coroutine par socket, ce qui empêche l’entrelacement de deux lignes.

## Requêtes

| `type` | Champs | Effet |
| --- | --- | --- |
| `BUILD` | `id`, `tool`, `x`, `y` | Construction, zonage, démolition ou relief |
| `TAX` | `id`, `value` 0..20 | Taux d’impôt commun |
| `FUNDING` | `id`, `value` 0..100 | Financement global des services |
| `SPEED` | `id`, `value` 0..3 | Pause ou 1/2/3 mois par cycle |
| `DISASTERS` | `id`, `value` 0 ou 1 | Désactiver/activer les incendies aléatoires |
| `BOND` | `id` | Emprunter 10 000 $ |
| `REPAY` | `id` | Rembourser 10 000 $ |
| `SAVE` | `id` | Sauvegarder au chemin configuré côté serveur |
| `PING` | `id` | Réponse `PONG` |

`tool` accepte `road`, `rail`, `power`, `pipe`, `subway`, `bulldoze`, `clear_underground`, `raise`,
`lower`, `level`, `water`, `trees`, `zone:RESIDENTIAL_LIGHT`, `zone:RESIDENTIAL_DENSE`, `zone:COMMERCIAL_LIGHT`,
`zone:COMMERCIAL_DENSE`, `zone:INDUSTRIAL_LIGHT`, `zone:INDUSTRIAL_DENSE`, ou un ID de `catalog`.

Pour `level`, `value` indique l’altitude cible de 0 à 12. `raise`/`lower` modifient les quatre
sommets de la case, avec propagation des raccords de pente. Les modifications induites sont validées
et facturées dans la même transaction. `water` pose de l’eau de surface (5 $ si la case était sèche).

`STATE.city.terrain` contient `width`, `height` et `vertices`, une liste de `(width+1) × (height+1)`
entiers en ordre ligne par ligne. La case `(x,y)` utilise les sommets `(x,y)`, `(x+1,y)`, `(x+1,y+1)`
et `(x,y+1)`. `Tile.elevation` reste transmis, mais représente seulement leur altitude minimale.
Les clients v1 doivent être mis à jour ; ils ne peuvent interpréter cette géométrie correctement.

Exemple de commande et d’accusé de réception (champs par défaut omis pour la lisibilité) :

```json
{"type":"BUILD","id":1,"tool":"road","x":12,"y":8}
{"type":"ACK","id":1,"message":"Alice : road"}
```

Après une ou plusieurs mutations rapprochées, tous les clients reçoivent un `STATE` reflétant leur résultat. Un ACK confirme une transaction en
mémoire, **pas** une sauvegarde durable. Seul l’ACK de `SAVE` confirme la fin de l’écriture du fichier.
Une sauvegarde ne change pas la révision. Une commande refusée ne change ni la révision ni les fonds.

```json
{"type":"ERROR","id":2,"message":"Trésorerie insuffisante"}
```

Le message est destiné à un humain. Pour une future interface externe, ajouter des codes d’erreur
stables avant de dépendre du texte français. `STATE.city.revision` augmente à chaque transaction
acceptée et à chaque jour simulé ; un événement de présence peut republier la même révision.

## Débogage manuel

Démarrer un serveur sans jeton sur localhost, puis ouvrir `nc 127.0.0.1 2000` et saisir une ligne
HELLO suivie de commandes. Les réponses STATE sont volumineuses. Un script de client peut filtrer
les objets par `type` pour n’afficher que ACK/ERROR. Ne pas enregistrer un jeton réel dans un exemple
ou un journal partagé.

La version du protocole est distincte de celle du plugin (`apiVersion`). Une évolution incompatible
du schéma devra incrémenter `PROTOCOL_VERSION`. Aucun support d’anciennes versions n’est promis
par cette première version du jeu.

## Mobilité (version 7)

Les états `City` incluent `mobility`, nullable lorsque les trajets ne sont pas encore calculés
ou ont été invalidés par une construction ou un changement de financement. Cet objet contient
`month`, `homes`, `completedNeeds` et `unmetNeeds`. Chaque domicile dans `homes` contient
`cell`, `residents`, `options`, `paths`, `loads`, `completedNeeds` et `unmetNeeds`. Les indices
des options sont locaux à sa liste `paths`. Les chemins encodent leurs
états par `case * 4 + mode` (marche 0, route 1, rail 2, métro 3), avec leurs minutes cumulées.
Les charges portent une case, un mode et 24 compteurs horaires. Le client reconstruit les horaires
individuels avec le code partagé de `core` ; il n’effectue aucune recherche d’itinéraire.
Les clients et serveurs doivent tous utiliser la version 8.

`City.day` est l’indice du prochain jour à exécuter depuis le 01/01/1900, ou `null` dans une ancienne
ville : sa date de reprise est alors le premier jour indiqué par `month`. `City.nextCitizenId`
alloue des identifiants non recyclés. `City.households` conserve les personnes et le registre de
chemins propre à leur domicile ; les carnets référencent ce registre avec jour, besoin, sens et score.
`City.trafficRules` contient les coefficients de capacité et d’apprentissage.

`mobility.day` indique la journée exécutée, `executionVersion` son format temporel. Chaque domicile
contient aussi `days`, les plans et traces de ses habitants. Une trace garde les noeuds et minutes
écoulées depuis son départ ; une arrivée nullable signifie un trajet inachevé. Les indices des plans,
retours et carnets sont locaux au domicile. Les positions nocturnes persistent dans les habitants.
Les caches des protocoles 3 et 4 sont invalidés au chargement ; les données de ville sont conservées.

## États compacts (version 7)

La première trame `STATE` d’une connexion contient la ville complète. Les suivantes peuvent
porter `retained: {"revision": N, "households": true, "mobility": false}` : les champs indiqués
par `true` sont repris de la précédente ville reçue, dont la révision doit être exactement N.
Le champ omis est représenté par `[]` pour `households`, par `null` pour `mobility`.
Avec `false`, la valeur reçue remplace toujours la précédente, y compris `null` ou une liste vide.
Sans `retained`, les habitants et la mobilité sont intégralement transmis. En version 8, la carte peut
être différentielle via `delta` (voir ci-dessous), même sans `retained`.

Le client reconstruit **chaque** état sur le lecteur réseau avant de regrouper les notifications
pour Swing. Une référence absente ou incompatible ferme la connexion au lieu d’appliquer un état
incomplet. Le serveur encode les états en ordre, en comparant uniquement les références immuables ;
il ne retransmet donc pas les carnets des habitants après une simple construction. L’arrivée d’un
nouveau client force un état complet. Les sauvegardes restent complètes et leur format est inchangé.

La lecture des trames procède par blocs bornés, sans consommer la trame suivante. La limite est
contrôlée avant ajout au résultat et une fin de flux sans LF reste une erreur, même si le JSON est valide.

En version 7, `STATE.sharedRouteHomes` liste les cellules dont `mobility.homes[].paths`, transmis
vide, doit être repris de `households[].paths` dans l’état reconstruit. Cela fonctionne aussi pour un
état complet et après simulation ; reconstruire d’abord les champs `retained`, puis ces registres.
Le client rejette les références manquantes ou répétées et les registres non vides portant ce marqueur.
Les sauvegardes conservent leur format complet.

Les files de sortie comptent la mémoire des chaînes jusqu’à la fin de leur écriture : budget de
64 Mio pour les états et réserve de 1 Mio pour les contrôles, avec au plus 32 messages en attente.
Le comptage utilise deux octets par caractère, conservateur pour les chaînes compactes de la JVM.
Une seule trame dépassant son budget est autorisée lorsque sa catégorie est vide, pour préserver
les grandes villes et catalogues ; aucune seconde trame de cette catégorie ne peut s’accumuler.
Un dépassement déconnecte le client lent, sans supprimer de delta au milieu d’une chaîne. Une
reconnexion repart d’un état complet. L’état en cours d’écriture est inclus dans le comptage.


## Carte différentielle et rafales (version 8)

Chaque `STATE` contient exactement l’un de `city` ou `delta` (l’autre vaut `null`). Le premier état
reste un `city` complet. Un `delta` contient `baseRevision`, `width`, `height`, `seed`, puis :

- `tiles` : objet JSON associant l’indice d’une case à sa nouvelle valeur `Tile` complète ;
- `vertices` : objet JSON associant l’indice d’un sommet à sa nouvelle altitude ;
- tous les autres champs de `City`, sauf `terrain`, qui remplacent les valeurs précédentes :
  `revision`, `month`, `treasury`, `debt`, `rules`, `stats`, `history`, `notices`, `pluginData`,
  `mobility`, `day`, `households`, `nextCitizenId`, `trafficRules`.

Les indices sont des clés JSON décimales. Une case ou un sommet absent est conservé depuis la
ville précédente. Le client vérifie sa révision, ses dimensions, sa graine, les indices et les
invariants du relief, puis reconstruit une `City` complète. Il applique ensuite `retained` et
`sharedRouteHomes` comme auparavant, avant de notifier Swing. Une valeur vide ou `null` dans les
autres champs remplace bien l’ancienne valeur, sauf marqueur `retained` explicite.

Le serveur compare les valeurs des cases et sommets, pas seulement l’identité des listes.
Si plus de la moitié des cases ou des sommets changent, il transmet la carte complète. Une arrivée
force également une nouvelle base complète pour garantir la synchronisation du nouveau client.
Les sauvegardes restent complètes et leur format ne change pas.

L’encodeur attend 50 ms après chaque état encodé : les mises à jour en attente sont regroupées,
avec au plus 20 états par seconde. Chaque commande reste appliquée et reçoit son propre ACK ou
ERROR ; seul l’affichage des états intermédiaires peut être regroupé. Aucun delta déjà encodé
n’est supprimé : il peut servir de base au suivant.

Chaque session dépend directement du superviseur, indépendamment de la boucle d’acceptation.
Les exceptions du lecteur et de l’écrivain sont traitées à la frontière de la session ; ses tâches,
sa socket et sa file sont fermées. Son départ est transmis même si la file des commandes est pleine.
Une rupture réseau ou l’exclusion d’un client lent n’arrête ni les autres sessions ni l’acceptation
de nouvelles connexions.
