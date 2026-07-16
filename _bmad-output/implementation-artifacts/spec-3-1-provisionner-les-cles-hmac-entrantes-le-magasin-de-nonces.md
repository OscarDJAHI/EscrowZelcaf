---
title: 'Story 3.1 — Provisionner les clés HMAC entrantes & le magasin de nonces'
type: 'feature'
created: '2026-07-16'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '37a5f7dce9c3f0eb853e34b9956cff1e77431fae'
final_revision: 'a7373a160232b88c75cc14e24cbf425203ad1763'
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** L'Epic 3 doit authentifier des dépôts partenaire machine-à-machine par HMAC-SHA256 et les protéger du rejeu, mais aucun stockage de clés entrantes dédiées ni magasin de nonces n'existe ; réutiliser le secret sortant des webhooks (`webhook_subscriptions.secret_key`) serait une faute de cloisonnement.

**Approach:** Poser le socle de persistance de la Story 3.2 : une migration Flyway `V4` créant une table de clés HMAC **entrantes** (par `key-id` résolvable vers une `companies`, distincte du secret sortant) et un magasin de nonces `(key_id, nonce, seen_at)` à unicité scindée par `key-id` ; plus les entités JPA et repositories associés (résolution de clé, test de rejeu, purge bornée par la rétention). Aucun endpoint ni vérification de signature ici — c'est la Story 3.2.

## Boundaries & Constraints

**Always:**
- La clé HMAC entrante vit dans une table dédiée (`partner_hmac_keys`), **distincte** de `webhook_subscriptions` : aucun code de cette story ne lit/écrit le secret sortant.
- `key_id` est **UNIQUE** et résolvable vers exactement une `companies` (FK `company_id NOT NULL`).
- Anti-rejeu **scindé par `key-id`** : contrainte composite `UNIQUE (key_id, nonce)` — même nonce autorisé pour deux `key-id` distincts, interdit deux fois pour le même.
- Rétention nonces **≥ fenêtre de validité** (±5 min, Story 3.2) : purge bornée dans le temps via `deleteBySeenAtBefore(cutoff)`, cutoff de la responsabilité de l'appelant (≤ `now - rétention`, rétention ≥ 5 min).
- Respecter les idiomes existants : migrations (`BIGSERIAL PK`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, FK `REFERENCES t (id)`, préfixes `idx_`/`uq_`/`ck_`, snake_case) et JPA (entités `domain`, repos `repository extends JpaRepository`, `@GeneratedValue(IDENTITY)`, `Instant` + `@PrePersist`, pas de Lombok).

**Block If:**
- La table `companies` ou la colonne `webhook_subscriptions.secret_key` attendues n'existent pas / diffèrent de l'investigation (contredit le socle Epic 1).

**Never:**
- Pas d'endpoint REST, pas de contrôleur, pas de vérification de signature, pas de logique anti-rejeu applicative (fenêtre timestamp, consommation de nonce) — tout cela est la Story 3.2.
- Pas de job de purge planifié (`@Scheduled`) : seulement la méthode repository bornée. Pas de chiffrement au repos du secret (POC, aligné sur le plaintext du secret sortant existant).
- Ne pas introduire de type ENUM natif Postgres (le projet utilise `VARCHAR`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Résolution clé active | `key_id` existant, `active=true` | `findByKeyIdAndActiveTrue` retourne la clé avec son `companyId` | Aucun |
| Clé désactivée | `key_id` existant, `active=false` | `findByKeyIdAndActiveTrue` retourne `Optional.empty()` | Aucun |
| Cloisonnement entrant/sortant | Même `companies` avec un `webhook_subscriptions.secret_key` sortant ET une `partner_hmac_keys` entrante de secret différent | Les deux coexistent, secrets indépendants dans deux tables ; résoudre l'entrant ne touche pas le sortant | Aucun |
| Rejeu même key-id | `(key_id, nonce)` déjà stocké | Réinsertion du même couple rejetée par la contrainte | `DataIntegrityViolationException` |
| Nonce réutilisé, key-id différent | Même `nonce`, `key_id` distinct | Insertion **autorisée** (unicité scindée par key-id) | Aucun |
| Purge bornée | Nonces d'âges variés, `cutoff` donné | Seuls les nonces `seen_at < cutoff` supprimés ; les plus récents (dans la fenêtre) conservés | Aucun |

</intent-contract>

## Code Map

- `backend/src/main/resources/db/migration/V4__partner_hmac_keys_and_nonce_store.sql` -- NOUVEAU. Migration créant `partner_hmac_keys` + `partner_key_nonces`.
- `backend/src/main/resources/db/migration/V1__init.sql` -- RÉFÉRENCE. Idiomes `companies`, `webhook_subscriptions.secret_key` (secret sortant à ne pas réutiliser).
- `backend/src/main/resources/db/migration/V3__evidence_files_constraints.sql` -- RÉFÉRENCE. Style CHECK/UNIQUE (`ck_`/`uq_`).
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` -- NOUVEAU. Entité clé entrante.
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerKeyNonce.java` -- NOUVEAU. Entité nonce.
- `backend/src/main/java/com/zlecaf/escrow/repository/PartnerHmacKeyRepository.java` -- NOUVEAU. Résolution de clé.
- `backend/src/main/java/com/zlecaf/escrow/repository/PartnerKeyNonceRepository.java` -- NOUVEAU. Test de rejeu + purge bornée.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` -- RÉFÉRENCE. Idiomes entité (`@PrePersist`, `@Column`, `Instant`).
- `backend/src/main/java/com/zlecaf/escrow/domain/Company.java` -- RÉFÉRENCE. Cible FK `companies`.
- `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` -- NOUVEAU. Test persistance (`@DataJpaTest` + Testcontainers).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceWithdrawConcurrencyTest.java` -- RÉFÉRENCE. Patron `@DataJpaTest`/Testcontainers à copier.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/resources/db/migration/V4__partner_hmac_keys_and_nonce_store.sql` -- Créer `partner_hmac_keys` (`id BIGSERIAL PK`, `key_id VARCHAR(100) NOT NULL UNIQUE`, `company_id BIGINT NOT NULL REFERENCES companies (id)`, `secret_key VARCHAR(255) NOT NULL`, `active BOOLEAN NOT NULL DEFAULT TRUE`, `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`) et `partner_key_nonces` (`id BIGSERIAL PK`, `key_id VARCHAR(100) NOT NULL REFERENCES partner_hmac_keys (key_id) ON DELETE CASCADE`, `nonce VARCHAR(255) NOT NULL`, `seen_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `CONSTRAINT uq_partner_key_nonces_key_nonce UNIQUE (key_id, nonce)`), plus `idx_partner_hmac_keys_company_id` et `idx_partner_key_nonces_seen_at`. -- Socle de persistance distinct du secret sortant.
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` -- Entité mappant `partner_hmac_keys` (`Long id` IDENTITY, `String keyId`, `Long companyId`, `String secretKey`, `boolean active`, `Instant createdAt` + `@PrePersist`). -- Clé HMAC entrante par partenaire.
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/PartnerKeyNonce.java` -- Entité mappant `partner_key_nonces` (`Long id`, `String keyId`, `String nonce`, `Instant seenAt` + `@PrePersist`). -- Nonce anti-rejeu.
- [x] `backend/src/main/java/com/zlecaf/escrow/repository/PartnerHmacKeyRepository.java` -- `extends JpaRepository<PartnerHmacKey, Long>` + `Optional<PartnerHmacKey> findByKeyIdAndActiveTrue(String keyId)`. -- Résolution key-id → clé/société.
- [x] `backend/src/main/java/com/zlecaf/escrow/repository/PartnerKeyNonceRepository.java` -- `extends JpaRepository<PartnerKeyNonce, Long>` + `boolean existsByKeyIdAndNonce(String keyId, String nonce)` + `int deleteBySeenAtBefore(Instant cutoff)` (bulk `@Modifying @Query`). -- Test de rejeu + purge bornée.
- [x] `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` -- Couvrir chaque ligne de l'I/O Matrix (`@DataJpaTest` + `@AutoConfigureTestDatabase(replace=NONE)` + Testcontainers `postgres:16-alpine`, Flyway activé). -- Preuve exécutable des invariants du store.

**Acceptance Criteria:**
- Given la migration `V4` appliquée sur un Postgres neuf, when Flyway migre, then les tables `partner_hmac_keys` et `partner_key_nonces` existent avec `key_id` UNIQUE, FK `company_id → companies(id)`, et `UNIQUE (key_id, nonce)`, sans réutiliser `webhook_subscriptions.secret_key`.
- Given une clé entrante provisionnée (une ligne `partner_hmac_keys` persistée) pour une société possédant par ailleurs un secret sortant, when on résout son `key_id` actif, then on obtient la clé entrante et son `companyId`, et le secret sortant reste inchangé et distinct.
- Given un couple `(key_id, nonce)` déjà stocké, when on réinsère le même couple, then la base rejette l'insertion (`DataIntegrityViolationException`) ; when on insère le même `nonce` pour un `key_id` distinct, then l'insertion réussit.
- Given des nonces d'âges variés, when `deleteBySeenAtBefore(cutoff)` s'exécute, then seuls les nonces antérieurs au `cutoff` sont supprimés et les plus récents sont conservés (rétention ≥ fenêtre respectée par un `cutoff` appelant borné).

## Spec Change Log

_(Aucun loopback bad_spec — vide.)_

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 2: (high 0, medium 1, low 1)
- defer: 2: (high 0, medium 0, low 2)
- reject: 9
- addressed_findings:
  - `[medium]` `[patch]` `deleteBySeenAtBefore` était une suppression dérivée Spring Data (select-puis-delete-chaque-ligne, chargement en mémoire) contredisant la promesse « purge bornée » → converti en `@Modifying @Query("delete … where seenAt < :cutoff")` (DELETE bulk en une instruction) ; retour `long`→`int` (contrat `@Modifying`). Test `boundedPurge` toujours vert.
  - `[low]` `[patch]` assertion tautologique `isNotEqualTo("OUTBOUND-WEBHOOK-SECRET")` retirée de `inboundOutboundIsolation` — le rechargement du webhook + `resolved==INBOUND` prouvent déjà l'indépendance des secrets.

## Design Notes

- **Cloisonnement entrant/sortant** = invariant structurel, pas discipline applicative : deux tables séparées, aucun code de la Story 3.1 ne lit `webhook_subscriptions`. La Story 3.2 consommera `PartnerHmacKeyRepository` pour la vérification `HmacSigner.verify(...)` (déjà constant-time, réutilisé tel quel — non modifié ici).
- **Nonce scindé par key-id** : le nonce référence le `key_id` textuel (pas l'`id` numérique), conformément à l'AC de l'epic ; la FK `ON DELETE CASCADE` nettoie les nonces si une clé est supprimée. La colonne `key_id` UNIQUE de `partner_hmac_keys` est la cible FK.
- **Rétention** : la table ne s'auto-purge pas ; par défaut les nonces persistent (rétention non bornée ⇒ trivialement ≥ fenêtre). `deleteBySeenAtBefore` est l'outil de purge borné ; l'ordonnancement et la constante de fenêtre (±5 min) appartiennent à la Story 3.2.
- **Idiome entité** : calquer `EvidenceFile.java` — `@Id @GeneratedValue(IDENTITY) Long id`, `@Column(name=...)` explicites, `boolean active` mappé sur `active`, `Instant createdAt/seenAt` avec `@PrePersist onCreate()` posant la valeur si null (le DEFAULT DB reste le filet de sécurité).

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=PartnerKeyStoreTest test` -- attendu : vert, tous les cas de l'I/O Matrix passent (Testcontainers Postgres actif, Flyway `V4` appliquée).
- `cd backend && mvn -q compile` -- attendu : compilation sans erreur.

## Auto Run Result

Status: done

**Changement implémenté :** socle de persistance de l'authentification partenaire (Epic 3) — migration Flyway `V4` créant `partner_hmac_keys` (clés HMAC **entrantes** par `key-id` UNIQUE, FK vers `companies`, distinctes du secret sortant `webhook_subscriptions.secret_key`) et `partner_key_nonces` (magasin anti-rejeu à unicité composite `(key_id, nonce)`), plus entités JPA, repositories et test de persistance. Aucun endpoint/signature (Story 3.2).

**Fichiers modifiés :**
- `backend/src/main/resources/db/migration/V4__partner_hmac_keys_and_nonce_store.sql` — 2 tables + index, idiomes Flyway existants (BIGSERIAL, TIMESTAMPTZ, FK, `uq_`/`idx_`).
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerHmacKey.java` — entité clé entrante (`keyId` unique, `companyId`, `secretKey`, `active`, `createdAt` + `@PrePersist`).
- `backend/src/main/java/com/zlecaf/escrow/domain/PartnerKeyNonce.java` — entité nonce (`keyId`, `nonce`, `seenAt` + `@PrePersist`).
- `backend/src/main/java/com/zlecaf/escrow/repository/PartnerHmacKeyRepository.java` — `findByKeyIdAndActiveTrue` (résolution key-id → clé/société, actives seulement).
- `backend/src/main/java/com/zlecaf/escrow/repository/PartnerKeyNonceRepository.java` — `existsByKeyIdAndNonce` (sonde de rejeu) + `deleteBySeenAtBefore` (purge bornée bulk `@Modifying`).
- `backend/src/test/java/com/zlecaf/escrow/repository/PartnerKeyStoreTest.java` — `@DataJpaTest` + Testcontainers `postgres:16-alpine`, 6 cas couvrant l'I/O Matrix.

**Revue (1 passe) :** 2 patches appliqués — (medium) purge dérivée → DELETE bulk `@Modifying @Query`, retour `long`→`int` ; (low) retrait d'une assertion de test tautologique. 2 items différés (ledger, sévérité faible) — garde longueur/entropie + `@JsonIgnore` du secret côté provisioning ; cycle de vie clé en soft-delete-only pour préserver l'historique de nonces face au `ON DELETE CASCADE`. 9 constats rejetés (chiffrement-au-repos POC sanctionné par la spec, garde du cutoff = responsabilité 3.2, IDENTITY conventionnel, double autorité timestamp = idiome projet, validation d'entrée = 3.2, aucune dérive d'image, borne `Before` exclusive sûre, FK garanties DB, longueur `@Column` garantie DB).

**Vérification :** `mvn -Dtest=PartnerKeyStoreTest test` → `Tests run: 6, Failures: 0, Errors: 0` — BUILD SUCCESS (Testcontainers Postgres 16 actif ; la violation `uq_partner_key_nonces_key_nonce` du cas rejeu apparaît dans les logs comme attendu). `mvn compile` vert.

**Risques résiduels :** faibles et suivis au ledger (secrets en clair au repos — choix POC ; purge non gardée jusqu'à ce que 3.2 en fournisse l'appelant). Aucun impact API/comportement externe : les patches sont internes au primitive de purge et au test.

**Follow-up review recommandé :** false — 2 correctifs localisés à faible conséquence, sans impact sécurité/données/API sur le contrat livré.
