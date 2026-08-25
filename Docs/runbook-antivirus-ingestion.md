# Runbook — Analyse anti-malware à l'ingestion

_Story 1.8 (NFR-P7). Procédure opérationnelle : exécutable sans lire le code._

## Ce que le scan protège

Tout fichier entrant est analysé **avant** d'être écrit dans le stockage objet, dans
le tronc commun d'ingestion des preuves. Les trois routes existantes en bénéficient
sans exception, et toute route d'upload créée plus tard en héritera d'office :

| Route | Ce qui est déposé |
| --- | --- |
| `POST /api/v1/escrow/{id}/evidence` | preuve déposée par une partie |
| `POST /api/v1/escrow/{id}/dispute` (composite) | pièces jointes à l'ouverture d'un litige |
| `POST /api/v1/partner/evidence` (signé HMAC) | dépôt machine d'un transporteur partenaire |

L'enjeu est la règle WORM (AD-25, rétention ≥ 5 ans) : une fois stocké, un binaire de
preuve **ne peut plus être supprimé** par l'application, et il est ensuite servi en
téléchargement aux deux parties et à l'arbitre. Un PDF ou un JPEG porteur d'un
malware deviendrait donc une pièce de dossier définitive. Le scan est la seule
occasion de dire non.

## Ce que le scan ne protège PAS

> **Il ne relit pas les preuves déjà stockées.** Aucune reprise, aucun rescan
> différé : les pièces déposées avant cette story n'ont jamais été analysées et ne le
> seront pas. Le scan porte sur l'ingestion, point.

> **Il n'est pas un contrôle d'intégrité du stockage.** Il ne vérifie rien au
> téléchargement, et `evidence_files` ne porte aucune empreinte permettant de
> détecter la substitution d'un objet dans le seau. Qui sait écrire dans le stockage
> peut y déposer ce qu'il veut : c'est un objectif distinct, routé vers la Story 11.5.

> **Il n'ouvre pas les archives et n'élargit pas la whitelist.** Seuls
> `image/jpeg`, `image/png` et `application/pdf` franchissent la validation de type
> (Apache Tika, sur les octets réels), et c'est ce qui parvient au moteur.

> **Il n'y a pas de quarantaine.** Un fichier détecté n'est **jamais** persisté : il
> n'y a donc rien à mettre en quarantaine, rien à purger, et aucun nouvel état de
> pièce. La transaction de dépôt fait rollback intégralement.

## Comportement, en deux lignes

- **Détection** → `400` avec le code `EVIDENCE_MALWARE_DETECTED`, message nommant le
  fichier refusé. Rien n'est stocké, aucune ligne `evidence_files` n'est écrite, et
  **une entrée d'audit est écrite quand même** (voir plus bas). Un seul fichier
  infecté dans un lot de vingt rejette le lot entier.
- **Moteur injoignable, lent, ou réponse incomprise** → `502` avec le code
  `SCAN_UNAVAILABLE`. Rien n'est stocké. Le code est **transitoire** : les clients,
  file de rejeu hors-ligne incluse, doivent réessayer, pas abandonner.

> **Il n'existe aucun interrupteur.** Aucune propriété `enabled`, aucun profil,
> aucune variable d'environnement ne désactive le scan — ni en développement, ni en
> CI, ni en production. Un tel drapeau viderait l'exigence de son sens : ce qui n'est
> pas analysé n'est pas stocké. Le nom de signature détecté, lui, ne franchit jamais
> la frontière HTTP (il resterait un banc d'essai d'évasion) ; il est dans le journal
> serveur et dans l'entrée d'audit.

## Exploitation

### Le service et son ordre de démarrage

Le moteur est un service voisin (`clamav` dans `infra/docker-compose.yml`), interrogé
en INSTREAM sur TCP/3310. Le backend déclare
`depends_on: clamav: {condition: service_healthy}` : il **attend** que clamd soit
prêt avant d'ouvrir son propre port. Conséquence assumée : le premier
`docker compose up` est plus long qu'avant.

```
ESCROW_ANTIVIRUS_HOST=clamav      # nom du service compose (défaut)
ESCROW_ANTIVIRUS_PORT=3310
ESCROW_ANTIVIRUS_CONNECT_TIMEOUT_MS=2000
ESCROW_ANTIVIRUS_READ_TIMEOUT_MS=30000
ESCROW_ANTIVIRUS_WRITE_TIMEOUT_MS=30000   # chien de garde d'écriture (revue 1.8)
```

Aucune de ces valeurs n'est un secret : elles n'apparaissent pas dans la validation
des secrets au démarrage, et un hôte absent ne fait **pas** échouer le boot — il fait
échouer les dépôts, en 502. En revanche un **port hors bornes** fait désormais échouer
le démarrage avec un message nommant `escrow.antivirus.port` (revue 1.8) : il partait
auparavant en 500 au premier dépôt, présentant une faute de configuration comme un
défaut serveur.

> ⚠️ **Sous compose, ces variables sont codées en dur dans `infra/docker-compose.yml`**
> (`ESCROW_ANTIVIRUS_HOST: clamav`), sans passthrough `${…}` : les poser dans
> `infra/.env` **ne fait rien**. Elles ne sont réglables que hors conteneur, ou en
> modifiant le compose. Les deux documents se contredisaient avant la revue 1.8.

**Pourquoi un timeout d'écriture séparé.** `SO_TIMEOUT` ne borne que les lectures.
Un clamd qui accepte la connexion puis cesse de lire — toutes ses `MaxThreads`
occupées, ou un rechargement de bases — bloquait `write` indéfiniment dès que les
tampons du socket étaient pleins, en tenant le thread de requête, la connexion JDBC
et le verrou de ligne escrow. Le chien de garde ferme le socket, ce qui débloque
l'écriture et rend un 502 ordinaire.

Hors conteneur (`mvn spring-boot:run`), poser `ESCROW_ANTIVIRUS_HOST=localhost` et
lancer un clamd local, sinon aucun dépôt ne passera.

### Budget mémoire

clamd charge **l'intégralité** des signatures en mémoire — ~1,3 Go aujourd'hui. Le
compose fixe `mem_limit: 2g`. En dessous d'environ 1,5 Go, le processus est tué par
l'OOM killer **pendant le chargement**, et le symptôme ne dit pas qu'il s'agit de
mémoire : le conteneur redémarre en boucle et ne devient jamais `healthy`.

Vérifier en cas de doute :

```bash
docker compose -f infra/docker-compose.yml ps clamav          # State / Health
docker compose -f infra/docker-compose.yml logs clamav | tail -30
docker stats --no-stream $(docker compose -f infra/docker-compose.yml ps -q clamav)
```

Une trace de démarrage saine se termine par `socket found, clamd started.`

### Mise à jour des bases virales

> **Corrigé à la revue 1.8.** Ce runbook affirmait que l'image `_base` embarque les
> bases virales. **C'est faux** : l'image fait ~79 Mo, `/var/lib/clamav` y est vide,
> et son `/init` lance `freshclam` dès que ce dossier est vide. Tout ce qui suit a
> été réécrit sur le comportement réel.

L'image utilisée est la variante **`_base`** (`clamav/clamav:1.4.3_base`). Elle ne
porte **aucune signature** : au tout premier démarrage, le conteneur télécharge
`main.cvd` + `daily.cvd` + `bytecode.cvd` (~113 Mo) **avant** que clamd n'écoute.

Ce qui rend les démarrages suivants rapides et hors ligne, c'est le **volume nommé
`clamavdb`** monté sur `/var/lib/clamav` (posé à la revue 1.8) : les bases y
persistent d'une recréation de conteneur à l'autre. Sans lui, chaque
`docker compose up --build` repayait les 113 Mo — et sur un lien lent dépassait le
budget du healthcheck, ce qui, le backend étant gaté par `depends_on:
service_healthy`, empêchait **toute la stack** de démarrer au lieu de dégrader en
502.

Le compose pose `CLAMAV_NO_FRESHCLAMD=true`. Deux précisions qui comptent :

- **Le nom exact est `CLAMAV_NO_FRESHCLAMD`**, avec un `D` final (`/init` de l'image,
  ligne 67). La variante sans `D`, posée jusqu'à la revue 1.8, n'était lue par
  personne : `freshclam` tournait quand même, contrairement à ce que le compose
  affirmait.
- Elle coupe le **daemon** de mise à jour, pas le téléchargement initial — celui-ci
  est inconditionnel quand le volume est vide.

- **En local / CI** : rien à faire au-delà du premier démarrage, plus long. Les bases
  vivent ensuite dans le volume.
- **En staging / production** : les bases doivent vieillir le moins possible. Retirer
  `CLAMAV_NO_FRESHCLAMD` (freshclam tourne alors en tâche de fond et rafraîchit les
  signatures), et relever l'image de temps en temps.

Mettre à jour manuellement, sans attendre :

```bash
docker compose -f infra/docker-compose.yml exec clamav freshclam
docker compose -f infra/docker-compose.yml restart clamav   # clamd recharge ses bases
```

> ⚠️ Pendant un rechargement de bases, clamd peut accepter la connexion **puis se
> taire**. C'est exactement le cas que le timeout de lecture borne : les dépôts
> échouent en 502 pendant quelques secondes, puis repartent. Rien à faire.

### `StreamMaxLength` doit rester au-dessus de la limite métier

La limite applicative est de **10 Mo par fichier** ; `StreamMaxLength` de clamd vaut
25 Mo par défaut. Si quelqu'un l'abaissait sous 10 Mo, les gros fichiers **légitimes**
remonteraient `INSTREAM size limit exceeded`. Ce n'est pas un verdict : l'adaptateur
le traite comme une indisponibilité (502) et le journal nomme la cause probable. Ne
jamais interpréter cette réponse comme un « rien trouvé ».

## Symptômes et diagnostic

| Symptôme | Cause probable | Manœuvre |
| --- | --- | --- |
| **Tous** les dépôts répondent 502 `SCAN_UNAVAILABLE` | clamd mort, pas encore prêt, ou hôte/port erronés | `docker compose ps clamav` ; si `unhealthy`/absent : `docker compose up -d clamav` puis attendre `healthy`. Vérifier `ESCROW_ANTIVIRUS_HOST/PORT`. |
| Conteneur `clamav` redémarre en boucle, jamais `healthy` | mémoire insuffisante (voir plus haut) | augmenter la RAM allouée à Docker, ou `mem_limit` |
| 502 intermittents, quelques secondes | rechargement de bases, ou machine saturée | aucune ; relever `ESCROW_ANTIVIRUS_READ_TIMEOUT_MS` si récurrent |
| Journal : `limite de flux … StreamMaxLength` | `StreamMaxLength` sous les 10 Mo métier | remonter `StreamMaxLength` dans la configuration clamd |
| Journal : `réponse incomprise de clamd …` | ce n'est pas clamd qui écoute sur ce port | corriger `ESCROW_ANTIVIRUS_PORT` |
| Un déposant signale un fichier refusé en 400 | détection réelle, ou faux positif | lire l'entrée d'audit ci-dessous : elle nomme la signature |

**Reprise après un scanner mort** : il n'y a rien à réparer côté données. Aucun dépôt
n'a été à moitié écrit (la transaction fait rollback, y compris le nettoyage des
objets déjà stockés du lot), et les clients hors-ligne rejouent d'eux-mêmes puisque
le code est transitoire. Remettre clamd debout suffit.

## Lire une entrée d'audit `EVIDENCE_REJECTED_MALWARE`

Chaque détection écrit une ligne dans `audit_logs`, **dans sa propre transaction** :
elle survit au rollback du dépôt qu'elle consigne. C'est la seule trace qu'un fichier
malveillant a été présenté — le fichier, lui, n'existe nulle part.

```sql
SELECT id, timestamp, transaction_id, action_by, payload
FROM audit_logs
WHERE payload->>'action' = 'EVIDENCE_REJECTED_MALWARE'
ORDER BY timestamp DESC
LIMIT 20;
```

Le `payload` JSONB porte :

| Champ | Sens |
| --- | --- |
| `action` | toujours `EVIDENCE_REJECTED_MALWARE` |
| `actorRole` | `BUYER` / `SELLER` / `ADMIN`, ou `null` pour un dépôt partenaire |
| `filename` | nom **assaini** (basename seul, jamais le chemin fourni) |
| `sha256` | empreinte des octets refusés — de quoi reconnaître une récidive |
| `signature` | nom de la signature ClamAV déclenchée |

`transaction_id` situe la transaction escrow concernée, `action_by` l'utilisateur (nul
pour un dépôt partenaire, où la transaction et l'entreprise portent le contexte),
`previous_state = next_state` : un rejet n'est pas un changement d'état.

**Aucun octet du fichier n'est conservé** dans le payload. Le tracer par
`sha256` est le seul moyen de dire « le même fichier a été présenté trois fois ».

Compter les récidives d'un même contenu :

```sql
SELECT payload->>'sha256' AS sha256, payload->>'signature' AS signature, count(*)
FROM audit_logs
WHERE payload->>'action' = 'EVIDENCE_REJECTED_MALWARE'
GROUP BY 1, 2 ORDER BY 3 DESC;
```

> Une **panne** de scanner n'est délibérément **pas** auditée (WARN serveur
> seulement). `audit_logs` est append-only et conservé ≥ 5 ans : un incident
> d'infrastructure de quelques minutes y écrirait autant de lignes qu'il y a de
> tentatives de dépôt, pour zéro information de conformité. Un scanner mort se
> diagnostique dans les journaux et la supervision, pas dans le journal d'audit.

## Remplacer le moteur d'analyse

Une seule classe sait qu'il s'agit de ClamAV et qu'on lui parle en INSTREAM :
`backend/src/main/java/com/zlecaf/escrow/service/scan/ClamavMalwareScanner.java`.
Le service d'ingestion, les controllers et les tests de pipeline ne connaissent que
le port.

Pour brancher un autre moteur :

1. implémenter `MalwareScanner` (`ScanVerdict scan(byte[] content)`) dans
   `service/scan/`, en `@Component` ;
2. respecter le contrat **fail-closed** : tout ce qui n'est pas un verdict clairement
   compris lève `MalwareScanUnavailableException`. Ne jamais rendre « sain » par
   défaut, jamais avaler une erreur ;
3. borner les entrées/sorties par des timeouts explicites, et ne jamais relire ni
   recopier les octets (ils sont déjà en mémoire) ;
4. retirer l'ancien adaptateur — un seul bean `MalwareScanner` doit exister, sinon le
   démarrage échoue sur une injection ambiguë, ce qui est le bon comportement ;
5. adapter le service voisin dans `infra/docker-compose.yml` et le bloc
   `escrow.antivirus` d'`application.yml`.

Rien d'autre ne bouge : ni `EvidenceService`, ni les codes d'erreur, ni le frontend.

> **Une décision de conformité à ne pas prendre seul** : un moteur **distant** ou
> commercial (API cloud, service tiers) ferait sortir de la plateforme des binaires
> confidentiels sous rétention réglementaire. C'est un arbitrage de conformité, pas
> un choix d'implémentation.
