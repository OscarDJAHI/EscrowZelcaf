---
title: 'Story 3.3 — Durcir le stockage des clés HMAC partenaire'
type: 'refactor'
created: '2026-07-16'
status: 'done'
review_loop_iteration: 0
baseline_revision: '3f9edd7'
final_revision: '6001ff14fb6dd954991781e5fa7edaef2489e11c'
followup_review_recommended: false
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Le socle de persistance des clés HMAC entrantes (Story 3.1) admet un secret arbitrairement faible (les tests persistent `"S"`), expose `secret_key` via un getter simple sans garde de sérialisation, et laisse la FK `partner_key_nonces.key_id → partner_hmac_keys ON DELETE CASCADE` — un hard-delete de clé efface silencieusement tout l'historique anti-rejeu et, `key_id` étant réutilisable, rouvre une fenêtre de rejeu. Ces trois failles doivent être fermées **avant** que la Story 3.2 ne s'appuie sur ces clés pour vérifier les signatures.

**Approach:** Durcir la couche **stockage** (pas d'endpoint) via une migration Flyway `V5` : (1) contrainte DB `CHECK (octet_length(secret_key) >= 32)` rejetant toute clé trop courte à l'admission ; (2) FK des nonces basculée de `ON DELETE CASCADE` à `ON DELETE RESTRICT` pour rendre structurellement impossible la destruction d'historique par suppression de clé. Plus `@JsonIgnore` sur `PartnerHmacKey.getSecretKey()` (le secret ne peut fuiter par sérialisation), et mise à niveau des fixtures/tests.

## Boundaries & Constraints

**Always:**
- Garde de longueur au niveau **stockage** : `CHECK (octet_length(secret_key) >= 32)` (≥ 32 octets, cohérent avec la convention du secret JWT `escrow.jwt.secret`). Une clé trop courte est rejetée à l'INSERT (`DataIntegrityViolationException`).
- Non-divulgation : `@JsonIgnore` sur `PartnerHmacKey.getSecretKey()`. Toute sérialisation JSON de l'entité omet le secret (nom de champ ET valeur absents).
- Préservation de l'historique anti-rejeu : FK `partner_key_nonces.key_id → partner_hmac_keys (key_id)` en `ON DELETE RESTRICT` (plus `CASCADE`). Un hard-delete d'une clé ayant des nonces est bloqué par la DB. La révocation reste le **soft-delete** (`active=false`) — seul flux supporté.
- `key_id` reste **UNIQUE** au niveau table (invariant de résolution key-id → clé ; déjà posé en V4, préservé, non redéclaré).
- Idiomes existants respectés : migration additive `V5` (préfixes `ck_`/`fk_`, snake_case, `ALTER TABLE`), pas de Lombok, pas d'ENUM natif, pas de modification rétroactive de V4.

**Block If:**
- La FK `partner_key_nonces_key_id_fkey` (nom Postgres par défaut de la FK mono-colonne de V4) est absente ou porte un nom différent de l'investigation — la migration `V5` ne peut la remplacer proprement.

**Never:**
- Pas d'endpoint, contrôleur, DTO d'exposition, vérification de signature, consommation de nonce ni fenêtre timestamp (tout cela = Story 3.2).
- Pas de chiffrement au repos du secret (choix POC sanctionné, aligné sur le plaintext du secret sortant).
- Pas de génération de secret ni d'outillage de provisioning/admin.
- Ne pas toucher l'entité/table du secret **sortant** `WebhookSubscription` (hors périmètre ; déjà protégé par projection DTO `SubscriptionDto`). Application symétrique = defer éventuel.
- Pas de validation d'entropie applicative : la longueur ≥ 32 octets est le proxy sanctionné par l'epic.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Admission clé valide | INSERT `partner_hmac_keys`, `secret_key` ≥ 32 octets | Ligne persistée | Aucun |
| Admission clé faible | `secret_key` < 32 octets | Insertion rejetée | `DataIntegrityViolationException` (`ck_partner_hmac_keys_secret_len`) |
| Secret non sérialisé | `objectMapper.writeValueAsString(partnerHmacKey)` | JSON sans clé `secretKey` ni la valeur du secret | Aucun |
| Hard-delete clé avec historique | `delete(key)` alors que des `partner_key_nonces` référencent son `key_id` | Suppression rejetée, historique conservé | `DataIntegrityViolationException` (FK RESTRICT) |
| Révocation supportée | `active=false` sur la clé | `findByKeyIdAndActiveTrue` → `empty` ; nonces conservés | Aucun |
| key_id dupliqué | 2ᵉ INSERT même `key_id` | Insertion rejetée | `DataIntegrityViolationException` (UNIQUE) |

</intent-contract>

## Code Map

- `backend/src/main/resources/db/migration/V5__harden_partner_hmac_key_storage.sql` -- NOUVEAU. `CHECK` longueur secret + bascule FK nonces en `RESTRICT`.
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` -- MODIF. `@JsonIgnore` sur `getSecretKey()` (+ import Jackson).
- `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` -- MODIF. Fixtures ≥ 32 octets + 3 nouveaux tests (clé faible, secret non sérialisé, delete bloqué par historique).
- `backend/src/main/resources/db/migration/V4__partner_hmac_keys_and_nonce_store.sql` -- RÉFÉRENCE. Schéma V4 durci (FK `CASCADE`, `key_id UNIQUE`).
- `backend/src/main/java/com/zlecaf/escrow/domain/WebhookSubscription.java` + `web/dto/WebhookDtos.java` -- RÉFÉRENCE. Précédent de non-divulgation par projection DTO (secret sortant).
- `backend/src/main/java/com/zlecaf/escrow/security/JwtService.java` -- RÉFÉRENCE. Convention secret ≥ 32 octets.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V5__harden_partner_hmac_key_storage.sql` -- (a) `ALTER TABLE partner_hmac_keys ADD CONSTRAINT ck_partner_hmac_keys_secret_len CHECK (octet_length(secret_key) >= 32);` (b) `ALTER TABLE partner_key_nonces DROP CONSTRAINT partner_key_nonces_key_id_fkey;` puis `ADD CONSTRAINT fk_partner_key_nonces_key_id FOREIGN KEY (key_id) REFERENCES partner_hmac_keys (key_id) ON DELETE RESTRICT;` -- Garde longueur + préservation d'historique, au niveau stockage.
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` -- Annoter `getSecretKey()` avec `@com.fasterxml.jackson.annotation.JsonIgnore` (import en tête). Aucun autre changement d'entité. -- Le secret ne peut fuiter par sérialisation.
- [x] `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` -- Remplacer tous les secrets de fixtures (`"S"`, `"SA"`, `"SB"`, `"SP"`, `"INBOUND-SECRET"`, `"INBOUND-HMAC-SECRET"`) par une constante ≥ 32 octets (et corriger les assertions d'égalité correspondantes) ; ajouter `weakSecretRejected` (INSERT < 32 octets → `DataIntegrityViolationException`), `secretNotSerialized` (`new ObjectMapper().writeValueAsString(key)` ne contient ni `"secretKey"` ni la valeur), `keyDeleteRestrictedByNonceHistory` (persister clé + nonce, `delete(key)` + flush → `DataIntegrityViolationException`). -- Preuve exécutable des trois durcissements.

**Acceptance Criteria:**
- Given la migration `V5` appliquée sur un Postgres neuf, when Flyway migre, then `ck_partner_hmac_keys_secret_len` existe, la FK `partner_key_nonces.key_id → partner_hmac_keys(key_id)` est en `ON DELETE RESTRICT` (plus `CASCADE`), et `key_id` reste `UNIQUE`.
- Given une clé HMAC entrante persistée avec un secret ≥ 32 octets, when on sérialise l'entité en JSON, then la sortie ne contient ni le champ `secretKey` ni la valeur du secret ; when on tente d'insérer une clé au secret < 32 octets, then la base rejette (`DataIntegrityViolationException`).
- Given une clé ayant au moins un nonce en base, when on tente un hard-delete de la clé, then la base bloque la suppression et l'historique de nonces est conservé ; le soft-delete (`active=false`) reste le flux de révocation fonctionnel.
- Given la suite de tests complète, when `mvn test` s'exécute, then tout passe (fixtures mises à niveau + 3 nouveaux tests), sans régression sur les autres tests.

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 1, low 3)
- defer: 1: (high 0, medium 0, low 1)
- reject: 7
- addressed_findings:
  - `[medium]` `[patch]` `@JsonIgnore` sur `getSecretKey()` est bidirectionnel (bloque aussi la désérialisation) : il aurait rendu `secretKey` non liable en entrée, un piège pour le provisioning de la Story 3.2 → remplacé par `@JsonProperty(access = WRITE_ONLY)` (l'« équivalent » autorisé par l'AC) : le secret n'est jamais sérialisé en sortie mais reste liable en entrée. `secretNotSerialized` toujours vert.
  - `[low]` `[patch]` `weakSecretRejected` n'assertait que le type large `DataIntegrityViolationException` (satisfiable par NOT NULL/UNIQUE/FK) → ajout de `.hasStackTraceContaining("ck_partner_hmac_keys_secret_len")` pour prouver que c'est bien le CHECK de longueur qui déclenche.
  - `[low]` `[patch]` Ajout de `secretLengthBoundaryEnforced` (32 octets accepté, 31 rejeté) pour épingler le seuil exact `>= 32` contre une régression `>`/`>=`. (Ordre accept-puis-reject : le rejet abortant la transaction `@DataJpaTest`, il doit être la dernière instruction.)
  - `[low]` `[patch]` Ajout de `nonceFreeKeyIsDeletable` (contre-preuve positive) pour que le test RESTRICT prouve le blocage de la perte d'historique, et non un simple « clés indestructibles ».

Rejets (7) : longueur `octet_length` = proxy sanctionné par l'epic, la convention JWT du projet stocke le secret en octets bruts (pas de base64/hex à décoder) ; `DROP CONSTRAINT` par nom Postgres déterministe (schéma 100 % Flyway) échoue bruyamment, `IF EXISTS` masquerait un vrai problème ; `ObjectMapper` jetable représentatif (aucune config Jackson custom/mixin dans le projet) ; assertion sous-chaîne `doesNotContain` suffisante pour cette petite entité ; `ADD CHECK` sur table vide (aucun endpoint) sans besoin de `NOT VALID`/`VALIDATE` ; conventions de nommage mixtes V4/V5 cosmétiques ; module jsr310 déjà couvert par `findAndRegisterModules()`.

## Design Notes

- **Longueur = proxy d'entropie** (décision de l'epic : « ≥ 32 octets »). `octet_length` (pas `char_length`) pour compter des octets, conforme à l'AC. Aucun secret JWT explicite n'impose ≥ 32 aujourd'hui (jjwt lève implicitement `WeakKeyException`) : cette contrainte DB est net-new et devient la référence de longueur du projet côté partenaire. La génération à haute entropie relève de l'outillage de provisioning (hors périmètre).
- **`@JsonIgnore` vs projection DTO** : le projet protège le secret sortant par projection (`SubscriptionDto` omet `secretKey`). Pour la clé entrante, aucune DTO/endpoint n'existe encore ; `@JsonIgnore` sur le getter est la garde defense-in-depth qui protège **dès maintenant**, avant l'endpoint 3.2. C'est un nouveau précédent assumé, pas une entorse.
- **FK RESTRICT plutôt que « soft-delete only » documenté** : garantie structurelle DB > discipline applicative (cohérent avec les invariants de la Story 3.1). RESTRICT n'empêche que le hard-delete destructeur d'historique ; la révocation par `active=false` (déjà en place, résolue par `findByKeyIdAndActiveTrue`) demeure le flux supporté et n'est pas affectée.
- **Remplacement de FK en V5** : `DROP CONSTRAINT partner_key_nonces_key_id_fkey` (nom Postgres déterministe d'une FK mono-colonne inline de V4) puis `ADD CONSTRAINT fk_partner_key_nonces_key_id … ON DELETE RESTRICT`. Migration additive, V4 inchangée.

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=PartnerKeyStoreTest test` -- attendu : vert, tous les cas de l'I/O Matrix passent (Testcontainers Postgres 16, Flyway `V4`+`V5` appliquées ; les violations `CHECK`/`RESTRICT`/`UNIQUE` attendues apparaissent dans les logs).
- `cd backend && mvn -q test` -- attendu : suite complète verte, aucune régression.
- `cd backend && mvn -q compile` -- attendu : compilation sans erreur.

## Auto Run Result

Status: done

**Changement implémenté :** durcissement du **stockage** des clés HMAC entrantes du partenaire (Epic 3), fermant les deux failles différées de la Story 3.1 avant que la Story 3.2 ne s'en serve pour vérifier les signatures. Trois gardes, toutes au niveau stockage/entité : (1) longueur minimale du secret via `CHECK (octet_length(secret_key) >= 32)` ; (2) non-divulgation du secret via `@JsonProperty(access = WRITE_ONLY)` sur `getSecretKey()` (jamais sérialisé en sortie, reste liable en entrée pour le provisioning 3.2) ; (3) préservation de l'historique anti-rejeu via la bascule de la FK des nonces de `ON DELETE CASCADE` en `ON DELETE RESTRICT`. `key_id` reste `UNIQUE`. Aucun endpoint/signature (Story 3.2).

**Fichiers modifiés :**
- `backend/src/main/resources/db/migration/V5__harden_partner_hmac_key_storage.sql` — NOUVEAU. `CHECK` de longueur (`ck_partner_hmac_keys_secret_len`) + FK nonces re-créée en `RESTRICT` (`fk_partner_key_nonces_key_id`). Migration additive, V4 inchangée.
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` — `getSecretKey()` porte `@JsonProperty(access = WRITE_ONLY)` (import Jackson ajouté).
- `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` — fixtures mises à niveau ≥ 32 octets (constante `VALID_SECRET`) ; 5 nouveaux tests : `weakSecretRejected`, `secretLengthBoundaryEnforced`, `secretNotSerialized`, `keyDeleteRestrictedByNonceHistory`, `nonceFreeKeyIsDeletable`.

**Revue (1 passe) :** 4 patches appliqués — (medium) `@JsonIgnore`→`@JsonProperty(WRITE_ONLY)` (évite de casser le binding d'entrée de 3.2) ; (low×3) assertion du nom de contrainte dans `weakSecretRejected`, test de borne 31/32 octets, contre-preuve `nonceFreeKeyIsDeletable`. 1 item différé (ledger) : procédure/outil « purge-puis-delete » pour la GC physique d'un partenaire sous RESTRICT (hors périmètre, admin). 7 constats rejetés (voir Review Triage Log).

**Vérification :** `mvn -Dtest=PartnerKeyStoreTest test` → `Tests run: 11, Failures: 0, Errors: 0`. Suite complète `mvn test` → `Tests run: 129, Failures: 0, Errors: 0` (aucune régression). La violation `ck_partner_hmac_keys_secret_len` (SQLState 23514) et le blocage FK RESTRICT apparaissent dans les logs comme attendu.

**Risques résiduels :** faibles. La longueur ≥ 32 octets est un proxy d'entropie (décision epic) ; la génération à haute entropie et la GC physique des clés relèvent de l'outillage de provisioning/admin (hors périmètre). Aucun impact sur une API externe (aucun endpoint n'expose encore ces entités).

**Follow-up review recommandé :** false — 4 correctifs localisés à 2 fichiers, dont un seul medium (swap d'annotation bien compris et couvert par test), tous vérifiés verts par la suite complète.
