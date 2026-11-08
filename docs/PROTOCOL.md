# Protocole TCP v2

Chaque trame est un objet JSON UTF-8 terminé par LF (`\n`). CRLF est également accepté. Les retours
à la ligne dans les chaînes sont échappés par JSON. Aucune sérialisation Java n’est utilisée.
Le serveur accepte des requêtes de 8 192 caractères maximum et le client des réponses de 64 Mi
caractères maximum. Une fin de connexion avant LF ne valide jamais une commande partielle.

## Ouverture et cycle de vie

Envoyer dans les 10 secondes :

```json
{"type":"HELLO","version":3,"name":"Alice","token":""}
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

Après une mutation, tous les clients reçoivent un `STATE`. Un ACK confirme une transaction en
mémoire, **pas** une sauvegarde durable. Seul l’ACK de `SAVE` confirme la fin de l’écriture du fichier.
Une sauvegarde ne change pas la révision. Une commande refusée ne change ni la révision ni les fonds.

```json
{"type":"ERROR","id":2,"message":"Trésorerie insuffisante"}
```

Le message est destiné à un humain. Pour une future interface externe, ajouter des codes d’erreur
stables avant de dépendre du texte français. `STATE.city.revision` augmente à chaque transaction
acceptée et à chaque mois simulé ; un événement de présence peut republier la même révision.

## Débogage manuel

Démarrer un serveur sans jeton sur localhost, puis ouvrir `nc 127.0.0.1 2000` et saisir une ligne
HELLO suivie de commandes. Les réponses STATE sont volumineuses. Un script de client peut filtrer
les objets par `type` pour n’afficher que ACK/ERROR. Ne pas enregistrer un jeton réel dans un exemple
ou un journal partagé.

La version du protocole est distincte de celle du plugin (`apiVersion`). Une évolution incompatible
du schéma devra incrémenter `PROTOCOL_VERSION`. Aucun support d’anciennes versions n’est promis
par cette première version du jeu.

## Mobilité (version 3)

Les états `City` incluent `mobility`, nullable lorsque les trajets ne sont pas encore calculés
ou ont été invalidés par une construction ou un changement de financement. Cet objet contient
`month`, `homes`, `paths`, `loads`, `completedNeeds` et `unmetNeeds`. Les chemins encodent leurs
états par `case * 4 + mode` (marche 0, route 1, rail 2, métro 3), avec leurs minutes cumulées.
Les charges portent une case, un mode et 24 compteurs horaires. Le client reconstruit les horaires
individuels avec le code partagé de `core` ; il n’effectue aucune recherche d’itinéraire.
Les clients et serveurs doivent tous utiliser la version 3.
