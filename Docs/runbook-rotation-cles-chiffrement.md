# Runbook — Rotation des clés de chiffrement au repos

_Story 1.7 (AD-29, NFR-P6). Procédure opérationnelle : exécutable sans lire le code._

## Ce que ces clés protègent

`ESCROW_CRYPTO_KEYS` contient le **trousseau** qui chiffre, dans l'application :

| Donnée | Où | Forme au repos |
| --- | --- | --- |
| Secret HMAC partenaire entrant | `partner_hmac_keys.secret_key` | `esc:1:<idDeClé>:<base64>` |
| Secret HMAC webhook sortant | `webhook_subscriptions.secret_key` | `esc:1:<idDeClé>:<base64>` |
| Binaires de preuves | Stockage objet (MinIO/S3) | `ESCX` + en-tête + chiffré |

Algorithme : AES-256-GCM, IV aléatoire à chaque écriture, tag d'authentification
vérifié à la lecture. Altérer un seul octet d'une enveloppe — y compris son en-tête,
qui est authentifié — fait échouer le déchiffrement.

> **Limite à connaître.** Cette détection ne couvre **que les données enveloppées**.
> Une donnée écrite avant cette story n'a pas d'enveloppe et est rendue telle quelle
> (avec un WARN) : qui sait écrire dans le volume de stockage peut donc y déposer un
> objet en clair, qui sera servi sans alerte de déchiffrement. Le chiffrement au repos
> protège la **confidentialité** d'une copie volée ; il ne fait pas office de contrôle
> d'intégrité du stockage, et n'en tient pas lieu.

> **Perdre le trousseau, c'est perdre les données.** Aucune récupération n'existe :
> ni la base, ni le stockage objet, ni le dépôt ne contiennent de copie. Sauvegarder
> les clés au même titre qu'une sauvegarde de base — et **séparément** d'elle.

## Format des variables

```
ESCROW_CRYPTO_KEYS=v1:<clé base64>,v2:<clé base64>
ESCROW_CRYPTO_ACTIVE_KEY_ID=v2
```

- une clé = **exactement 32 octets décodés** (`openssl rand -base64 32`) ;
- un identifiant = `[A-Za-z0-9_-]`, unique dans le trousseau, inscrit dans chaque
  enveloppe qu'il produit — c'est lui qui dit quelle clé relit quoi ;
- `ESCROW_CRYPTO_ACTIVE_KEY_ID` désigne la clé qui **chiffre** ; toutes les autres
  restent là pour **déchiffrer** l'existant. Vide = déduit tant qu'il n'y a qu'une
  clé ; **obligatoire dès qu'il y en a plusieurs** (le démarrage est refusé sinon :
  deviner le sens d'une rotation serait dangereux).

Toute configuration invalide (variable absente, valeur `remplacez-moi` non
remplacée, clé qui ne fait pas 32 octets, identifiant actif hors trousseau)
**refuse le démarrage** en nommant la variable et la cause.

## Rotation en 4 gestes

L'ordre est contraignant, et il impose **deux vagues de redémarrage** dès qu'il y a
plus d'une instance de backend.

> **Pourquoi deux vagues.** Une instance ne connaît que le trousseau qu'elle a lu à
> son démarrage. Si l'on basculait la clé active en même temps qu'on ajoute `v2`,
> l'instance redémarrée écrirait des enveloppes `v2` que les instances encore en
> vol — trousseau `{v1}` — ne sauraient pas relire : vérification de signature
> partenaire et livraison de webhooks échoueraient chez elles jusqu'à leur propre
> redémarrage. On élargit donc le trousseau **partout** d'abord, on bascule ensuite.
> En mono-instance, les deux vagues se confondent en un seul redémarrage.

**1. Générer la nouvelle clé.**

```bash
openssl rand -base64 32          # -> 44 caractères, à coller tel quel
```

**2. L'AJOUTER au trousseau, sans retirer l'ancienne — puis redémarrer TOUTES les
instances (1ʳᵉ vague).**

```
ESCROW_CRYPTO_KEYS=v1:<ancienne>,v2:<nouvelle>
ESCROW_CRYPTO_ACTIVE_KEY_ID=v1        # inchangé à ce stade : v2 ne fait que déchiffrer
```
Retirer `v1` ici serait l'erreur fatale du jour : tout ce qui a été chiffré par
`v1` deviendrait illisible. À la fin de cette vague, **toutes** les instances savent
lire `v2`, et aucune n'en produit encore.

**3. Basculer la clé active.**

```
ESCROW_CRYPTO_ACTIVE_KEY_ID=v2
```

**4. Redémarrer le backend (2ᵉ vague).** Au démarrage, `SecretsEncryptionBootstrap` balaie les
deux tables de secrets et re-chiffre sous `v2` tout ce qui portait `v1`. La trace
le confirme, une ligne par table :

```
Chiffrement au repos [partner_hmac_keys] : 0 scellée(s), 3 pivotée(s), 0 inchangée(s), 0 vide(s) ignorée(s)
Chiffrement au repos [webhook_subscriptions] : 0 scellée(s), 1 pivotée(s), 0 inchangée(s), 0 vide(s) ignorée(s)
```

Le balayage est **idempotent** : un second redémarrage affiche `0 pivotée(s)` et
n'écrit rien.

> ⚠️ **`vide(s) ignorée(s)` n'est pas anodin.** Une ligne dont `secret_key` est vide
> n'est ni scellée ni pivotée : il n'y a rien à chiffrer. Elle est comptée à part
> (et non parmi les « inchangées ») parce qu'elle ne pourra **jamais** signer —
> la première livraison de webhook qui l'emploiera échouera. Un compteur non nul
> se traite en corrigeant la ligne, pas en relançant le balayage.

> ⚠️ **Le balayage est un événement de démarrage, pas une réconciliation continue.**
> Chaque instance chiffre avec la clé active qu'elle a lue à SON démarrage : pendant
> la 2ᵉ vague, une instance encore en `v1` qui crée un abonnement webhook écrit une
> ligne `v1` **après** que l'instance déjà redémarrée a pivoté la table. Ce n'est pas
> une anomalie et rien n'est perdu (`v1` est toujours au trousseau) — mais la
> vérification ci-dessous n'a de sens qu'une fois **toutes** les instances
> redémarrées, et un reliquat se résorbe par un redémarrage supplémentaire.

## Ce que la rotation ne fait PAS : les objets

Les **binaires de preuves déjà stockés ne sont pas ré-écrits**. Ils gardent leur
enveloppe `v1` et restent lisibles tant que `v1` est au trousseau — c'est voulu :
réécrire des preuves entrerait en conflit avec la règle WORM (AD-25, rétention
≥ 5 ans), et une reprise en masse du stockage objet est une opération à part
entière, non couverte par cette story.

Conséquence pratique : **`v1` reste nécessaire longtemps après la rotation.**

## Quand retirer l'ancienne clé sans risque

Retirer `v1` de `ESCROW_CRYPTO_KEYS` est sûr quand **les deux** conditions sont
vraies :

1. plus aucune ligne de secret ne porte `v1` — le redémarrage de l'étape 4 l'a fait,
   et un second redémarrage l'a confirmé (`0 pivotée(s)`). Vérification directe :

   ```sql
   SELECT count(*) FROM partner_hmac_keys      WHERE secret_key LIKE 'esc:1:v1:%';
   SELECT count(*) FROM webhook_subscriptions  WHERE secret_key LIKE 'esc:1:v1:%';
   -- les deux doivent rendre 0
   ```

2. plus aucun **objet** chiffré sous `v1` n'existe dans le stockage.

> ⚠️ **La condition 2 n'est aujourd'hui pas vérifiable.** Aucun outil ne recense
> l'identifiant de clé porté par les objets stockés (rien n'est indexé en base :
> `evidence_files` ne garde que la clé de stockage). En l'absence de cette reprise,
> la règle d'exploitation est donc **absolue et non négociable** : une clé ayant
> chiffré ne serait-ce qu'un objet ne se retire **jamais** avant l'échéance de
> rétention de la dernière preuve concernée (≥ 5 ans, AD-25). Autrement dit, en
> pratique, on **n'enlève pas** de clé du trousseau : on l'y laisse et on cesse de
> s'en servir. Le trousseau ne coûte que quelques dizaines d'octets par clé.

La condition 1, elle, ne conditionne que les colonnes de base : la remplir ne
suffit pas à autoriser un retrait. Si une clé manque, le message le dit et nomme
l'identifiant introuvable :

```
Déchiffrement impossible : la clé « v1 » n'est pas dans ESCROW_CRYPTO_KEYS
(ids présents : [v2]). La remettre au trousseau pour relire cette donnée.
```

Le remède est alors exactement celui-là : remettre `v1` et redémarrer.

> ⚠️ **L'arrêt n'est pas instantané : il y a une fenêtre où l'instance répond.** Le
> balayage s'exécute *après* l'ouverture du port HTTP (c'est un `CommandLineRunner`),
> donc entre l'ouverture et l'échec, l'instance accepte des requêtes et son
> `/actuator/health` répond `UP` — un orchestrateur peut donc lui router du trafic
> pendant quelques instants avant qu'elle ne meure. La fenêtre est brève (les tables
> de secrets sont petites) mais réelle. En pratique : **surveiller la trace de
> démarrage, pas seulement le healthcheck**, et considérer une instance comme saine
> uniquement après avoir vu sa ligne `Chiffrement au repos [...]` par table.

**Le backend s'arrête au démarrage** si une clé manque au trousseau alors qu'une
ligne de secret la référence encore — c'est délibéré : servir des requêtes avec des
secrets partenaires indéchiffrables serait pire qu'un arrêt. Le message nomme la
table concernée et la table est laissée **intacte** (aucune ligne à moitié pivotée) :

```
Chiffrement au repos [partner_hmac_keys] : balayage interrompu, table laissée INTACTE
(transaction annulée). Cause probable : une clé référencée par une enveloppe a été
retirée de ESCROW_CRYPTO_KEYS — la remettre au trousseau et redémarrer.
```

## Cas particulier : première mise en service sur une base existante

Les lignes écrites avant la Story 1.7 sont en clair, sans enveloppe. Elles restent
**lisibles** (la lecture tolère le legacy et journalise un WARN) et sont scellées au
premier démarrage :

```
Chiffrement au repos [partner_hmac_keys] : 3 scellée(s), 0 pivotée(s), 0 inchangée(s)
```

Idem côté objets : une preuve déposée avant la story se relit telle quelle, avec un
WARN nommant sa clé de stockage. Aucun déploiement ne rend une preuve illisible.

> ⚠️ **La mise en service elle-même n'est PAS transparente en multi-instance.** Dès
> que la première instance 1.7 a scellé les tables, une instance encore en version
> antérieure lit l'enveloppe `esc:1:v1:…` **comme si c'était le secret** : elle
> rejette alors toute signature partenaire (401) et signe ses webhooks avec une
> valeur fausse. Il n'existe pas de version intermédiaire « sait lire, n'écrit pas
> encore » — le premier déploiement de 1.7 doit donc se faire **en bascule
> complète** (toutes les instances passées en 1.7 avant que l'une d'elles ne
> serve du trafic), et non en déploiement tournant. En mono-instance — la
> topologie livrée aujourd'hui — la question ne se pose pas.

Deux lignes vides (`0 vide(s) ignorée(s)`) et aucun `Scellement refusé` : la mise en
service est complète. Si le démarrage échoue en nommant une ligne sous le plancher
de 32 octets, c'est un secret partenaire trop faible provisionné en SQL direct
avant la story : le remplacer, puis redémarrer. Le chiffrer aurait rendu sa
faiblesse invisible à toutes les couches — c'est pourquoi le balayage préfère
s'arrêter.
