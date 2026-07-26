---
title: 'Story 1.7 — Chiffrement au repos'
type: 'feature'
created: '2026-07-26'
status: 'done'
baseline_revision: '8f614e6d0fb213311730842acbf21232e7435b8d'
final_revision: '173cf66b92f856723092889e1c23b639f05c829f'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized', 'multiple-goals']
---

<intent-contract>

## Intent

**Problem:** Tout ce que la plateforme garde de plus sensible est en clair au repos : les secrets HMAC partenaires (`partner_hmac_keys.secret_key`, `V4__…sql:16`), les secrets de webhook sortants (`webhook_subscriptions.secret_key`, `V1__init.sql:46`) et les binaires de preuves dans le stockage objet (`MinioEvidenceStorage.java:43-47` — aucun SSE, aucun chiffrement client). Un `pg_dump`, un volume Docker (`pgdata`, `miniodata`) ou une sauvegarde suffisent à tout divulguer, alors que la rétention imposée est ≥ 5 ans (AD-25) — c'est le trou que l'AD-29 et le NFR-P6 ferment, et un P0 stop-ship.

**Approach:** Introduire **une** primitive de chiffrement applicatif AES-256-GCM (`SecretCipher`) alimentée par un trousseau de clés versionnées injectées par variables d'environnement, puis la brancher aux deux seuls points d'entrée existants : un `AttributeConverter` JPA sur les deux colonnes de secrets, et l'adaptateur `MinioEvidenceStorage` (chiffrement côté client, derrière le port `EvidenceStorage` comme l'exige AD-6/AD-29). La rotation est livrée avec : le trousseau accepte plusieurs clés, l'enveloppe porte l'identifiant de la clé, et un runner de démarrage idempotent re-chiffre les lignes de base sous la clé active.

## Boundaries & Constraints

**Always:**
- **Chiffrement applicatif, pas « du disque »** : l'enveloppe doit protéger un `pg_dump` et une copie de volume, pas seulement un vol de disque nu. Chiffrement authentifié obligatoire (AES-256-GCM), IV aléatoire par opération (`SecureRandom`), jamais réutilisé.
- **Clés hors du code** (NFR-P1, AD-29) : trousseau injecté par `ESCROW_CRYPTO_KEYS`, aucun défaut, démarrage refusé si absent/sentinelle/invalide — pattern `RequiredSecretsEnvironmentPostProcessor` de la Story 1.2, étendu et non dupliqué.
- **Enveloppe auto-descriptive** : elle porte la version de format et l'**identifiant de clé** qui l'a produite, sinon la rotation est impossible et le déchiffrement devient une devinette.
- **Lecture tolérante au legacy** : une valeur/objet écrit avant cette story (clair, sans enveloppe) reste lisible — la rétention WORM (AD-25) interdit de rendre une preuve irrécupérable au déploiement.
- Le chiffrement objet vit **uniquement** dans l'adaptateur (AD-6 : rien hors de l'adaptateur ne connaît S3 ; le port garde sa signature `byte[]`/`InputStream`).
- Les suites existantes restent vertes, sans régression (backend `./mvnw test`, frontend `npm run test` : relever les compteurs avant/après).

**Block If:**
- Une décision d'**outillage** de gestion des secrets (Vault / SOPS / KMS cloud) deviendrait nécessaire pour satisfaire un AC : c'est le spike AR-P4, tranché en Story 11.2 (`ARCHITECTURE-SPINE.md:300`) — HALT plutôt que le préempter.
- Une contradiction apparaîtrait entre l'AC de la story et un AD du spine : HALT et remonter, ne jamais arbitrer localement.
- Une donnée sensible **déjà persistée aujourd'hui** et non listée dans le périmètre ci-dessous se révélerait devoir être chiffrée pour satisfaire l'AC : HALT (élargissement de périmètre non arbitré).

**Never:**
- Ne pas créer de table pour une catégorie AD-29 encore inexistante (coordonnées bancaires → Story 4.9, secrets TOTP → 2.6, résultats de screening AML → 3.5) : ces stories réutiliseront la primitive, elles ne sont pas préemptées ici.
- Pas de hachage à la place du chiffrement pour les secrets HMAC : ils doivent être **restitués en clair** pour signer/vérifier (`PartnerSignatureVerifier.java:81`, `WebhookService.java:67`).
- Pas de SSE-S3/SSE-KMS ni de KES MinIO : le backend objet définitif n'est pas tranché (exigence de lancement, `SPINE:199,309`) et un chiffrement serveur ne survivrait pas à la bascule ; pas d'ajout de `s3-encryption-client` ni de `pgcrypto`.
- Pas de chiffrement de `evidence_files.original_filename`, `comment`, `audit_logs.payload`, `escrow_transactions.description`, ni de la table `messages` (inexistante) — hors liste AD-29 ; toute extension serait un élargissement non arbitré.
- Pas de reprise (backfill) des objets déjà stockés en clair, pas de cache de clés dérivées, pas de nouveau code d'erreur d'API, aucun changement frontend.
- Ne pas renforcer la longueur minimale des secrets de webhook à l'entrée d'API (aucun invariant préexistant à préserver de ce côté — hors périmètre).

## I/O & Edge-Case Matrix

| Scénario | Entrée / État | Sortie / Comportement attendu | Gestion d'erreur |
|---|---|---|---|
| Secret persisté | `PartnerHmacKey.secretKey` = 40 octets, clé active `v1` | La colonne contient une enveloppe `esc:1:v1:…`, jamais le clair ; relecture JPA → clair identique | Aucune erreur attendue |
| Preuve stockée | `store(42, bytes, "application/pdf")` | L'objet S3 commence par le magic `ESCX`, ne contient pas la séquence en clair ; `load` rend les octets **identiques** | Échec infra → `EvidenceStorageException` (502) inchangé |
| Legacy en clair | Ligne/objet écrit avant la story (sans enveloppe) | Rendu tel quel, un WARN nommant la colonne/clé de stockage | Aucune erreur : ne jamais casser une lecture WORM |
| Rotation | Enveloppe produite par `v1`, clé active devenue `v2`, `v1` conservée au trousseau | Déchiffrement OK via `v1` ; toute **nouvelle** écriture utilise `v2` ; le runner réécrit les lignes de base sous `v2` | Clé `v1` retirée du trousseau → `IllegalStateException` nommant l'id de clé introuvable |
| Altération | Un octet du chiffré ou de l'IV modifié en base/objet | Rejet : le tag GCM ne valide pas | Exception explicite ; jamais de clair partiel rendu |
| Config invalide | `ESCROW_CRYPTO_KEYS` absent, sentinelle, clé ≠ 32 octets, ou `ESCROW_CRYPTO_ACTIVE_KEY_ID` hors trousseau | **Démarrage refusé**, message nommant la variable et la cause | Agrégé au message unique de la Story 1.2 |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/security/crypto/SecretCipher.java` -- **à créer** : primitive unique (trousseau + enveloppe + AES-GCM), texte et binaire.
- `backend/src/main/java/com/zlecaf/escrow/security/crypto/EncryptedStringConverter.java` -- **à créer** : `AttributeConverter<String,String>` Spring-managé (Boot enregistre `SpringBeanContainer` : l'injection de `SecretCipher` fonctionne, à prouver par test).
- `backend/src/main/java/com/zlecaf/escrow/config/SecretsEncryptionBootstrap.java` -- **à créer** : `CommandLineRunner` idempotent (gabarit `AdminBootstrap.java:37`) qui scelle/rotate les lignes via `JdbcTemplate` — **pas** via JPA (le dirty-checking compare le clair : un `save` après rotation n'émettrait aucun UPDATE).
- `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` -- chiffrer dans `store` (`:43-54`), déchiffrer dans `load` (`:59-74`) ; `delete` inchangé.
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java:31` / `WebhookSubscription.java:24` -- `@Convert`, colonne élargie, garde de longueur (partenaire uniquement).
- `backend/src/main/java/com/zlecaf/escrow/config/RequiredSecretsEnvironmentPostProcessor.java:26` -- ajouter le 5ᵉ secret obligatoire.
- `backend/src/main/resources/db/migration/V7__encrypted_secrets_at_rest.sql` -- **à créer** (prochain numéro libre confirmé).
- `backend/src/main/resources/application.yml:40-52` -- bloc `escrow.crypto.*` ; `backend/src/test/resources/application.properties` -- 4 secrets de test → 5.
- `infra/.env.example:18-26`, `infra/docker-compose.yml:104-115` -- provisionnement local des clés.
- Références de lecture : `service/PartnerSignatureVerifier.java:81`, `service/WebhookService.java:67`, `service/EvidenceService.java:194,317,467`.

## Tasks & Acceptance

**Execution:**
- [x] `security/crypto/SecretCipher.java` -- créer la primitive : parse `escrow.crypto.keys` (`id:base64,…`, chaque clé **exactement 32 octets** décodés, ids uniques, charset `[A-Za-z0-9_-]`), valide que `escrow.crypto.active-key-id` est dans le trousseau, sinon `IllegalStateException` nommant la variable. API : `encryptToText(String clair, String aad)` → `esc:1:<keyId>:<base64(iv‖chiffré‖tag)>` ; `decryptFromText` ; `encryptBytes(byte[], String aad)` → magic `ESCX` + version + longueur/id de clé + IV(12) + chiffré‖tag ; `decryptBytes` ; `isEnvelope(...)` pour texte et binaire. IV `SecureRandom` par opération ; **l'AAD lie l'enveloppe à son contexte** (voir Design Notes).
- [x] `security/crypto/EncryptedStringConverter.java` -- écriture : chiffrer avec l'AAD `escrow:db-secret`. Lecture : si l'enveloppe est absente → rendre la valeur telle quelle avec un WARN (legacy), sinon déchiffrer. `null` reste `null`.
- [x] `domain/PartnerHmacKey.java` -- `@Convert(converter = EncryptedStringConverter.class)` sur `secretKey`, retirer `length = 255`, et **poser en `@PrePersist`/`@PreUpdate` la garde « clair ≥ 32 octets UTF-8 »** qui remplace le CHECK SQL supprimé (compter les **octets**, pas les caractères — régression prouvée en Story 1.6). Conserver `@JsonProperty(WRITE_ONLY)`.
- [x] `domain/WebhookSubscription.java` -- même `@Convert`, retirer `length = 255`, ajouter `@JsonProperty(access = WRITE_ONLY)` sur `getSecretKey()` par symétrie (AD-29 : « `WRITE_ONLY` conservé ») — aucune garde de longueur ici.
- [x] `db/migration/V7__encrypted_secrets_at_rest.sql` -- `DROP CONSTRAINT ck_partner_hmac_keys_secret_len` (elle mesurerait désormais le chiffré : garde devenue mensongère, déplacée dans l'entité) ; passer les deux colonnes `secret_key` en `TEXT` (l'enveloppe dépasse 255 pour un secret long) ; commentaire expliquant pourquoi la base ne peut plus voir le clair.
- [x] `config/SecretsEncryptionBootstrap.java` -- runner `JdbcTemplate` sur les deux tables : valeur sans enveloppe → chiffrer ; enveloppe d'une clé ≠ active → déchiffrer + re-chiffrer ; enveloppe déjà active → ignorer. `UPDATE … WHERE id = ?` natif, transactionnel, log de synthèse (`n scellées, n pivotées, n inchangées`). Idempotent : une 2ᵉ exécution ne modifie rien.
- [x] `service/storage/MinioEvidenceStorage.java` -- injecter `SecretCipher` ; `store` chiffre les octets avec **l'AAD = `storageKey`** (la clé est générée avant l'appel) ; `load` lit le flux, déchiffre si magic présent, sinon rend le legacy tel quel avec un WARN. Ne pas changer la signature du port ni le `contentType` déclaré à S3.
- [x] `config/RequiredSecretsEnvironmentPostProcessor.java` -- ajouter `escrow.crypto.keys` → `ESCROW_CRYPTO_KEYS` à `REQUIRED_SECRETS` (présence + sentinelle héritées).
- [x] `application.yml` + `test/resources/application.properties` + `infra/.env.example` + `infra/docker-compose.yml` -- déclarer `escrow.crypto.keys` (`${ESCROW_CRYPTO_KEYS}`, sans défaut) et `escrow.crypto.active-key-id` ; fournir une clé de test `v1` ; `.env.example` avec sentinelle + `openssl rand -base64 32` et rappel du charset base64 (contrainte d'interpolation compose déjà documentée) ; compose en `${ESCROW_CRYPTO_KEYS:?…}`.
- [x] `Docs/runbook-rotation-cles-chiffrement.md` + `README.md` -- procédure de rotation en 4 gestes (générer `v2` → l'**ajouter** au trousseau sans retirer `v1` → basculer `active-key-id` → redémarrer, le runner pivote la base ; les objets restent lisibles via `v1`, la reprise objet est différée), + quand retirer `v1` sans risque.
- [x] `security/crypto/SecretCipherTest.java` -- unitaire, couvre la matrice I/O : aller-retour texte et binaire, deux chiffrés différents pour un même clair, altération d'un octet → rejet, AAD divergent → rejet, keyId inconnu → erreur nommant la clé, et les 4 configurations invalides refusées au démarrage.
- [x] `security/crypto/EncryptedSecretsIntegrationTest.java` -- Testcontainers Postgres 16 + Flyway (gabarit `PartnerEvidenceDepositIntegrationTest.java:60`) : persistance via repository → lecture **JDBC brute** = enveloppe, sans le clair ; relecture JPA = clair ; legacy en clair inséré en SQL → lu sans erreur ; vérification HMAC partenaire toujours fonctionnelle bout en bout.
- [x] `config/SecretsEncryptionBootstrapTest.java` -- Testcontainers : scellement d'une ligne legacy, idempotence (2ᵉ passe = 0 écriture), rotation `v1→v2` (valeur re-chiffrée, clair préservé), ligne déjà active ignorée.
- [x] `service/storage/MinioEvidenceStorageTest.java` -- étendre (gabarit `MinIOContainer` existant, `:29-51`) : l'objet brut relu par un `S3Client` nu ne contient pas le clair et porte le magic ; aller-retour identique octet pour octet ; objet legacy déposé directement → `load` le rend ; objet écrit sous `v1` toujours lisible après bascule sur `v2`.

**Acceptance Criteria:**
- Given une base et un stockage objet peuplés par les parcours normaux, when on inspecte les données au repos (`SELECT secret_key`, objet S3 brut), then aucun secret HMAC/webhook ni aucun binaire de preuve n'y apparaît en clair (NFR-P6).
- Given le trousseau de chiffrement, when on cherche une clé dans le dépôt (code, migrations, compose, image), then elle n'y figure pas : elle provient exclusivement de l'environnement, et son absence refuse le démarrage en la nommant (AD-29, NFR-P1).
- Given une clé active `v1` et des données déjà chiffrées, when on ajoute `v2` au trousseau, bascule l'identifiant actif et redémarre, then les lignes de base sont re-chiffrées sous `v2`, les données antérieures restent toutes déchiffrables, et la procédure est décrite dans le runbook et prouvée par test.
- Given le déploiement de cette story sur un environnement existant, when l'application redémarre, then aucune preuve ni aucun secret existant ne devient illisible.

## Spec Change Log

## Review Triage Log

### 2026-07-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 10: (high 1, medium 6, low 3)
- defer: 4: (high 0, medium 3, low 1)
- reject: 3: (high 0, medium 0, low 3)
- addressed_findings:
  - `[high]` `[patch]` V7 supprimait `ck_partner_hmac_keys_secret_len` en déplaçant le plancher dans l'entité — or **aucun chemin applicatif n'admet une clé partenaire** (provisioning en SQL direct jusqu'à l'Epic 7), donc la garde ne couvrait plus aucun écrivain réel et un secret HMAC de 5 octets devenait insérable. Contrainte reformulée et conservée : `CHECK (secret_key LIKE 'esc:%' OR octet_length(secret_key) >= 32)`, doublée du garde-fou d'entité ; deux tests d'intégration verrouillent les deux branches.
  - `[medium]` `[patch]` L'en-tête d'enveloppe (version + identifiant de clé) était écrit hors GCM : réécrire `esc:1:v1:` en `esc:1:v2:` faisait croire au runner que la ligne était déjà pivotée, la requête de contrôle du runbook la comptait comme migrée, et retirer `v1` la rendait illisible. L'en-tête **relu** entre désormais dans l'AAD (texte et binaire) ; test dédié.
  - `[medium]` `[patch]` `cipher.encryptBytes`/`decryptBytes` étaient hors du `try` de l'adaptateur : un objet altéré ou une clé absente sortaient en 500 nu, sans le champ `code` que le client utilise pour classer transitoire/permanent (AD-10). Enveloppés en `EvidenceStorageException` (502), diagnostic conservé dans la cause.
  - `[medium]` `[patch]` Le balayage de scellement pouvait chiffrer un clair sous le plancher (le rendant faible **et** invisible) et échouait sans nommer la table fautive. Plancher par table revérifié avant scellement + message d'échec actionnable (table, cause probable, remède) ; deux tests, dont la preuve que la table reste intacte.
  - `[medium]` `[patch]` Le runbook annonçait « aucune interruption de service » avec un seul redémarrage : en multi-instance, les instances non redémarrées ne savent pas lire les enveloppes `v2` et échouent en vérification de signature. Procédure réécrite en **deux vagues** (élargir le trousseau partout, puis basculer).
  - `[medium]` `[patch]` La condition de retrait d'une ancienne clé (« plus aucun objet sous `v1` ») était invérifiable : rien ne recense l'identifiant de clé des objets stockés. Remplacée par une règle absolue et explicite (on ne retire pas une clé ayant chiffré un objet avant l'échéance de rétention ≥ 5 ans).
  - `[medium]` `[patch]` Le passage de `secret_key` en `TEXT` retirait le plafond implicite de 255 sur un champ alimenté par une API authentifiée. Plafond restauré explicitement (`@Size(max = 255)` sur `SubscriptionRequest.secretKey`) — restauration d'une borne perdue, pas un durcissement nouveau.
  - `[low]` `[patch]` Le runbook promettait qu'« une altération ne passe pas inaperçue » : faux pour le dépouillement d'enveloppe, que le repli legacy sert en clair. Limite désormais écrite noir sur blanc (confidentialité ≠ contrôle d'intégrité).
  - `[low]` `[patch]` Javadoc de `EvidenceService.download` devenue fausse (« never read into memory here »), corrigée avec la raison structurelle (GCM authentifie au tag final).
  - `[low]` `[patch]` `PartnerKeyStoreTest` avait perdu ses assertions de type (`hasStackTraceContaining` seul, qui passe sur n'importe quelle exception) : type d'exception ré-épinglé sur les deux cas.

## Design Notes

**Pourquoi chiffrement côté client plutôt que SSE serveur.** Le backend objet définitif n'est pas tranché (MinIO est un binaire orphelin, la bascule S3 est une exigence de lancement) : un SSE-S3/KES est une configuration d'infrastructure qui ne survit pas à la bascule et ne protège pas un `pg_dump`. Le chiffrement dans l'adaptateur est indépendant du fournisseur, testable en Testcontainers, et c'est la lecture littérale d'AD-29 (« côté stockage objet, **derrière `EvidenceStorage`** ») croisée avec AD-6 (rien hors de l'adaptateur ne connaît S3).

**Rotation dans cette story vs AR-P4 en 11.2.** L'`epic-1-context.md` paraît se contredire (« la rotation fait partie du livrable » `:30` vs « ne pas préempter l'outil » `:42`). Lecture retenue, non arbitraire : 1.7 livre la rotation **de la clé de chiffrement applicative** (trousseau multi-clés, enveloppe versionnée, pivot idempotent, runbook, test) ; 11.2 livre l'**outil** qui stocke et distribue ces clés (Vault/SOPS/KMS). Les deux sont compatibles : le trousseau lit des variables d'environnement, quel que soit ce qui les remplira demain.

**Périmètre AD-29 partiellement futur.** Trois des cinq catégories nommées par AD-29 n'ont aucune table aujourd'hui (coordonnées bancaires, secrets TOTP, résultats AML). Ce n'est pas un conflit story/spine mais un séquencement : `SecretCipher` est la primitive que 2.6, 3.5 et 4.9 réutiliseront, et la règle permanente d'AD-29 (« aucune nouvelle catégorie sensible persistée sans statuer son chiffrement ») s'appuiera dessus. Le contenu de `messages` (demandé par la revue de sécurité SEC-C2, omis d'AD-29) reste hors périmètre : conflit à remonter, pas à trancher ici.

**AAD (donnée authentifiée associée).** Pour les objets, AAD = `storageKey` : une enveloppe recopiée sous une autre clé de stockage ne se déchiffre pas — gratuit et utile (AD-12 garantit l'unicité de `storage_key`). Pour les colonnes, AAD = constante `escrow:db-secret` : elle empêche la transplantation d'une enveloppe d'un usage à l'autre, sans tenter de lier à la ligne (le converter ne voit que la valeur, et un attaquant capable d'écrire en base a déjà gagné). Ce choix est volontaire, pas un oubli.

**Format d'enveloppe** (texte, secrets de base) :
```
esc:1:v1:<base64(iv(12o) ‖ chiffré ‖ tag(16o))>
```
Binaire (objets) : `ESCX` ‖ version(1) ‖ longueur id(1) ‖ id ‖ iv(12) ‖ chiffré‖tag. Le préfixe sert **aussi** de détecteur de legacy : son absence signifie « écrit avant cette story ».

**Piège JPA à ne pas répéter.** Le dirty-checking Hibernate compare l'attribut *en clair* : après rotation, `entity.setSecretKey(sameValue)` puis `save` n'émet aucun UPDATE. C'est pourquoi le pivot passe par `JdbcTemplate` sur la valeur brute. De même, la validation « ≥ 32 octets » doit rester côté entité (elle voit le clair) et compter des **octets UTF-8**.

## Verification

**Commands:**
- `cd backend && ./mvnw test` -- attendu : BUILD SUCCESS, 0 échec, compteur ≥ celui relevé avant la story (Docker requis — Testcontainers Postgres + MinIO réels).
- `cd frontend && npm run test` -- attendu : 0 échec, compteur inchangé (aucune modification frontend ; garde anti-régression).
- `cd backend && ./mvnw -q compile` puis démarrage sans `ESCROW_CRYPTO_KEYS` -- attendu : refus de démarrer, message nommant `ESCROW_CRYPTO_KEYS`.
- `grep -rn "esc:1:\|ESCX" backend/src/main/resources infra/ Docs/` -- attendu : aucune clé ni enveloppe réelle versionnée (seulement de la documentation).

**Manual checks (if no CLI):**
- `db/migration/V7__…sql` est bien la migration suivante (V6 = dernière) et ne contient aucun secret.
- Le runbook de rotation décrit une procédure exécutable sans lire le code, et indique explicitement le moment où retirer l'ancienne clé du trousseau devient sûr.

## Auto Run Result

Status: done

### Changement livré

Chiffrement applicatif au repos (AD-29 / NFR-P6) : une primitive unique **AES-256-GCM**
(`SecretCipher`) alimentée par un trousseau de clés versionnées injectées par
`ESCROW_CRYPTO_KEYS`, branchée sur les deux seules catégories de données sensibles
existantes — les deux colonnes de secrets HMAC (via un `AttributeConverter` JPA) et les
binaires de preuves (chiffrement côté client dans l'adaptateur de stockage, derrière le
port `EvidenceStorage`). L'enveloppe porte sa version et l'identifiant de clé, tous deux
authentifiés ; la rotation est livrée avec (trousseau multi-clés, balayage idempotent au
démarrage qui pivote les lignes en JDBC brut, runbook opérationnel).

### Fichiers

| Fichier | Rôle |
| --- | --- |
| `backend/src/main/java/com/zlecaf/escrow/security/crypto/SecretCipher.java` | Primitive unique : trousseau, enveloppes texte et binaire, AES-256-GCM |
| `backend/src/main/java/com/zlecaf/escrow/security/crypto/EncryptedStringConverter.java` | Chiffrement transparent des colonnes, tolérant les valeurs legacy |
| `backend/src/main/java/com/zlecaf/escrow/config/SecretsEncryptionBootstrap.java` | Scellement et rotation des lignes déjà persistées (JDBC brut, idempotent) |
| `backend/src/main/resources/db/migration/V7__encrypted_secrets_at_rest.sql` | Colonnes en `TEXT`, contrainte de plancher reformulée pour tolérer l'enveloppe |
| `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` | Chiffre/déchiffre les objets, AAD = clé de stockage, erreurs en 502 neutre |
| `backend/src/main/java/com/zlecaf/escrow/domain/{PartnerHmacKey,WebhookSubscription}.java` | `@Convert`, plancher en octets UTF-8 (partenaire), `WRITE_ONLY` symétrique |
| `backend/src/main/java/com/zlecaf/escrow/config/RequiredSecretsEnvironmentPostProcessor.java` | 5ᵉ secret obligatoire : démarrage refusé sans trousseau |
| `backend/src/main/java/com/zlecaf/escrow/web/dto/WebhookDtos.java` | Plafond de taille restauré après le passage en `TEXT` |
| `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` | Javadoc du téléchargement corrigée (plus de streaming, raison structurelle) |
| `application.yml`, `test/resources/application.properties`, `infra/.env.example`, `infra/docker-compose.yml`, `README.md` | Configuration et provisionnement des clés |
| `Docs/runbook-rotation-cles-chiffrement.md` | Procédure de rotation en deux vagues, conditions de retrait d'une clé |
| 5 classes de test créées ou étendues | `SecretCipherTest`, `EncryptedSecretsIntegrationTest`, `SecretsEncryptionBootstrapTest`, `MinioEvidenceStorageTest`, `PartnerKeyStoreTest` (+ 7 tranches `@DataJpaTest` important les deux beans) |

### Revue

- **10 correctifs appliqués** (1 haut, 6 moyens, 3 bas) — détail dans le journal de triage.
- **4 reports** consignés au ledger : absence de contrôle d'intégrité au téléchargement ;
  plafond mémoire sous téléchargements concurrents ; AAD de colonne non liée à la table ;
  perte d'isolation des livraisons webhook si une clé est retirée à chaud.
- **3 rejets** : faux positif d'enveloppe sur un objet legacy (impossible — les magics
  JPEG/PNG/PDF admis ne valent jamais `ESCX`) ; longueur d'un secret publiée dans un
  message de configuration ; balayage démarrant après l'ouverture du port HTTP (sans
  conséquence, la lecture tolère les deux formes).

### Vérification

- `cd backend && ./mvnw test` → **403 tests, 0 échec, BUILD SUCCESS** (362 avant la story).
- `cd frontend && npm run test` → **174 tests, 0 échec** (compteur inchangé, aucune modification frontend).
- Démarrage sans `ESCROW_CRYPTO_KEYS` → refus nommant la variable.
- `grep -rn "esc:1:\|ESCX"` sur `resources/`, `infra/`, `Docs/` → uniquement de la
  documentation ; aucune clé ni enveloppe réelle versionnée.

### Risques résiduels

- **Effet de déploiement** : les lignes et objets antérieurs restent lisibles, mais les
  lignes ne sont scellées qu'au premier redémarrage ; les objets déjà déposés en clair ne
  sont **jamais** repris (report assumé), ce qui rend l'ancienne clé nécessaire à vie.
- **Perdre le trousseau, c'est perdre les données** : aucune copie n'existe en base, dans
  le stockage ni dans le dépôt. La sauvegarde des clés est une exigence d'exploitation,
  pas une bonne pratique — elle sera reprise par l'outillage AR-P4 (Story 11.2).
- Trois catégories nommées par AD-29 (coordonnées bancaires, secrets TOTP, résultats AML)
  n'ont pas encore de table : elles réutiliseront `SecretCipher` en 2.6, 3.5 et 4.9.
- Le contenu de `messages` (demandé par la revue de sécurité SEC-C2, absent d'AD-29) reste
  hors périmètre : contradiction à remonter au spine, non tranchée ici.
