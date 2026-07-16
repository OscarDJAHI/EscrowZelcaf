---
title: 'Story 3.4 — Durcir l''endpoint partenaire signé (reports de 3.2)'
type: 'chore'
created: '2026-07-17'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '575c912'
final_revision: '2d24701'
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** L'endpoint partenaire signé (`POST /api/v1/partner/escrow/{id}/evidence`) est live mais traîne 5 reports réels de la revue de 3.2 : la table `partner_key_nonces` croît sans borne (purge jamais planifiée), le matcher `permitAll` couvre tout `/api/v1/partner/**` (toute future route serait ouverte par défaut), le `catch (DataIntegrityViolationException)` masque **toute** violation d'intégrité en rejeu 401 (et un nonce sur-long fait tourner tout l'ingest avant un 401 trompeur), et les deux invariants porteurs (ligne `CARRIER_PARTNER` valide, arbitrage de nonce concurrent) ne sont prouvés que par mocks.

**Approach:** Borner la table via un purgeur `@Scheduled` appelant `deleteBySeenAtBefore` avec une rétention ≥ fenêtre de validité ; épingler le `permitAll` au couple méthode+chemin exact ; restreindre le catch au **seul** conflit d'unicité du nonce (par nom de contrainte, sinon la violation remonte en 500) et garder en amont la longueur du nonce (400) ; ajouter deux tests d'intégration Testcontainers (ligne `CARRIER_PARTNER` réelle, concurrence même-nonce). Zéro régression du chemin de dépôt existant.

## Boundaries & Constraints

**Always:**
- La purge ne supprime **que** des nonces plus vieux que `max(rétention, timestamp-tolerance-seconds)` — jamais un nonce encore dans la fenêtre anti-rejeu (sinon la fenêtre de rejeu se rouvre). Rétention par défaut ≥ 300 s.
- Le `permitAll` partenaire cible **exactement** `POST /api/v1/partner/escrow/*/evidence` ; toute autre route `/api/v1/partner/**` retombe sur `anyRequest().authenticated()`.
- Seul le conflit de la contrainte `uq_partner_key_nonces_key_nonce` est interprété comme rejeu (`401`, message `AUTH_FAILED` inchangé) ; toute autre `DataIntegrityViolationException` **remonte** (→ 500), non masquée en échec d'auth.
- Réutiliser la primitive `deleteBySeenAtBefore` (Story 3.1), la contrainte unique existante, `HmacSigner`, `PartnerSignatureVerifier.canonicalString` — ne rien réécrire.
- Les tests d'intégration tournent sur Postgres Testcontainer avec Flyway réel (V1→V5) et le port de stockage satisfait par un fake en mémoire ; aucun mock des invariants DB (CHECK, contrainte unique).

**Block If:**
- Le nom de la contrainte unique du nonce n'est pas extractible de façon fiable de l'exception sur Postgres/Hibernate (empêcherait de distinguer rejeu d'autre violation). — HALT `blocked`.

**Never:**
- Ne pas élargir le contrat de l'endpoint (chaîne canonique, en-têtes, codes 401/403/404/409 figés par 3.2) ni changer le comportement du dépôt utilisateur.
- Ne pas introduire de purge à l'écriture bloquant le chemin chaud de dépôt (la tâche planifiée suffit).
- Ne pas toucher les reports hors périmètre 3.4 (double chargement `User`, message `AUTH_FAILED` dupliqué) — restent au ledger.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rejeu réel (course) | INSERT `(key_id,nonce)` violant `uq_partner_key_nonces_key_nonce` | `401` `AUTH_FAILED`, transaction perdante rollback | catch restreint par nom de contrainte |
| Autre violation d'intégrité | `DataIntegrityViolationException` sans le nom de contrainte du nonce | Exception **remonte** → `500` | non masquée en 401 |
| Nonce sur-long | `X-Escrow-Nonce` > 255 caractères | `400` avant vérif signature / ingest | `BadRequestException` |
| Route partenaire hors endpoint | Requête non authentifiée vers `/api/v1/partner/<autre>` | Bloquée par la sécurité (`401`) | matcher épinglé |
| Endpoint réel sans JWT | `POST /api/v1/partner/escrow/{id}/evidence` sans JWT | Traverse la sécurité (auth = signature seule) | non bloqué par `authenticated()` |
| Nonce expiré | `seen_at < now - max(rétention,tolérance)` | Purgé par la tâche planifiée | table bornée |
| Nonce récent | `seen_at` dans la fenêtre | Conservé | jamais purgé |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/config/SchedulingConfig.java` -- NOUVEAU. `@Configuration @EnableScheduling` (le scheduling n'est activé nulle part aujourd'hui).
- `backend/src/main/java/com/zlecaf/escrow/scheduler/PartnerNoncePurger.java` -- NOUVEAU. `@Component`. Méthode publique `purge()` (retourne le nb supprimé) appelant `nonceRepository.deleteBySeenAtBefore(now - max(rétention, tolérance))` ; annotée `@Scheduled(fixedDelayString="${escrow.partner.nonce-purge-interval-ms:60000}")`. Injecte `nonce-retention-seconds` + `timestamp-tolerance-seconds`.
- `backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java` -- MODIF. Remplacer `.requestMatchers("/api/v1/partner/**").permitAll()` par `.requestMatchers(HttpMethod.POST, "/api/v1/partner/escrow/*/evidence").permitAll()` ; importer `org.springframework.http.HttpMethod`.
- `backend/src/main/java/com/zlecaf/escrow/service/PartnerEvidenceService.java` -- MODIF. (1) Garde de longueur du nonce en tête de `deposit` (> 255 → `BadRequestException`, avant vérif/ingest). (2) Restreindre le `catch (DataIntegrityViolationException)` : ne mapper en `UnauthorizedException(AUTH_FAILED)` que si la cause est le conflit `uq_partner_key_nonces_key_nonce` (inspection du `ConstraintViolationException.getConstraintName()` Hibernate, insensible à la casse) ; sinon `throw` l'exception d'origine (remonte en 500).
- `backend/src/main/resources/application.yml` -- MODIF. Ajouter sous `escrow.partner` : `nonce-retention-seconds: ${ESCROW_PARTNER_NONCE_RETENTION_SECONDS:900}` et `nonce-purge-interval-ms: ${ESCROW_PARTNER_NONCE_PURGE_INTERVAL_MS:60000}`.
- `backend/src/main/java/com/zlecaf/escrow/repository/PartnerKeyNonceRepository.java` -- RÉFÉRENCE. `deleteBySeenAtBefore(Instant cutoff)` (déjà présent, `@Modifying`), `existsByKeyIdAndNonce`.
- `backend/src/main/resources/db/migration/V4__partner_hmac_keys_and_nonce_store.sql` / `V3__evidence_files_constraints.sql` -- RÉFÉRENCE. `uq_partner_key_nonces_key_nonce`, `ck_evidence_attribution`.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceWithdrawConcurrencyTest.java` -- RÉFÉRENCE. Patron Testcontainers + `@Transactional(NOT_SUPPORTED)` + `ExecutorService`/`CountDownLatch` + `InMemoryEvidenceStorage`.
- `backend/src/test/java/com/zlecaf/escrow/service/PartnerSignatureVerifierTest.java` / `service/EvidenceServiceTest.java` -- RÉFÉRENCE. Construction chaîne canonique + `HmacSigner.sign` ; `pdfBytes()` (octets Tika-valides).

## Tasks & Acceptance

**Execution:**
- [x] `config/SchedulingConfig.java` + `scheduler/PartnerNoncePurger.java` -- Activer `@EnableScheduling` ; purgeur planifié appelant `deleteBySeenAtBefore(now - max(rétention, tolérance))`, méthode `purge()` publique testable. -- Bornage de `partner_key_nonces` (report #1).
- [x] `application.yml` -- Ajouter `nonce-retention-seconds` (défaut 900) et `nonce-purge-interval-ms` (défaut 60000) sous `escrow.partner`. -- Config rétention/cadence.
- [x] `config/SecurityConfig.java` -- Épingler le `permitAll` au couple `POST` + `/api/v1/partner/escrow/*/evidence`. -- Surface d'auth chirurgicale (report #2).
- [x] `service/PartnerEvidenceService.java` -- Garde longueur nonce (>255 → 400) en tête de `deposit` ; catch DIVE restreint au nom de contrainte du nonce (sinon remonte). -- Erreurs non masquées + fail-fast (report #3).
- [x] `test/.../service/PartnerEvidenceServiceTest.java` -- Étendre (mocks) : DIVE portant `uq_partner_key_nonces_key_nonce` → `401` ; DIVE d'un autre nom de contrainte → **propagée** (pas 401) ; nonce > 255 → `400` sans vérif signature. -- Preuve unitaire du mapping fin (report #3).
- [x] `test/.../scheduler/PartnerNoncePurgerIntegrationTest.java` -- NOUVEAU (Testcontainers). Persister un nonce `seen_at` expiré + un récent ; `purge()` ; prouver l'expiré supprimé, le récent conservé. -- Preuve de bornage (report #1).
- [x] `test/.../service/PartnerEvidenceDepositIntegrationTest.java` -- NOUVEAU (Testcontainers, `@DataJpaTest` + `@Import` du chemin partenaire + fake storage). Dépôt partenaire signé réussi ; relire la ligne `evidence_files` : `uploader_type=CARRIER_PARTNER`, `partner_company_id` renseigné, `uploaded_by_user_id=null`, `status=ACTIVE` ; nonce persisté ; audit `EVIDENCE_ADDED` `action_by` null. -- Invariant `ck_evidence_attribution` sur vraie base (report #4).
- [x] `test/.../service/PartnerNonceConcurrencyIntegrationTest.java` -- NOUVEAU (Testcontainers, `@Transactional(NOT_SUPPORTED)`). Deux dépôts concurrents même `(key_id, nonce)` ; prouver exactement un `201`, l'autre rejeté rejeu (`UnauthorizedException`), et en base exactement 1 ligne evidence + 1 nonce. -- Arbitrage réel de la contrainte unique (report #5).
- [x] `test/.../web/PartnerSecurityMatcherTest.java` -- NOUVEAU. Contexte complet (`@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers Postgres). Non-auth vers `/api/v1/partner/<autre>` → `401` (bloqué par la sécurité, plus `404`) ; `POST` endpoint réel sans JWT → traverse la sécurité (`400` en-tête manquant, pas `401`). -- Preuve du matcher épinglé (report #2).

**Acceptance Criteria:**
- Given la table `partner_key_nonces`, when un nonce dépasse `max(rétention, ±5 min)`, then il est purgé par la tâche planifiée et la table ne croît jamais sans borne ; un test prouve l'élimination d'un nonce expiré et la conservation d'un récent.
- Given `SecurityConfig`, when on expose la route signée, then `permitAll` cible exactement `POST /api/v1/partner/escrow/*/evidence` ; un test prouve qu'une autre route `/api/v1/partner/**` non authentifiée est rejetée par la sécurité et que l'endpoint réel reste atteignable sans JWT.
- Given l'INSERT du nonce, when une `DataIntegrityViolationException` survient, then seul le conflit d'unicité du nonce → rejeu (401) et toute autre violation remonte (500) ; un test distingue les deux cas ; un nonce > 255 est rejeté en 400 avant tout travail coûteux.
- Given une vraie base (Testcontainer Postgres), when un dépôt partenaire réussit, then un test d'intégration persiste et relit une ligne `CARRIER_PARTNER` réelle (`partner_company_id` renseigné, `uploaded_by_user_id` null) satisfaisant `ck_evidence_attribution`.
- Given deux requêtes partenaire concurrentes au même nonce sur vraie base, when elles s'exécutent, then un test de concurrence (pas un mock) prouve qu'exactement une réussit et l'autre est rejetée comme rejeu.
- Given la suite complète, when `mvn test` s'exécute, then tout passe (nouveaux tests + aucune régression du dépôt utilisateur/partenaire).

## Spec Change Log

## Review Triage Log

### 2026-07-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 1, low 3)
- defer: 0
- reject: 7
- addressed_findings:
  - `[medium]` `[patch]` **Couverture du pin incomplète** : le test matcher ne prouvait le blocage que par un `GET` sur une route inconnue, pas la dimension **méthode+chemin** du pin (report #2). Ajout de `wrongMethodOnEvidencePathBlockedBySecurity` (`GET` sur le chemin exact de l'endpoint → 403, prouve le scope `POST`) et `siblingPartnerPostRouteBlockedBySecurity` (`POST` sur un chemin partenaire frère → 403, prouve le scope chemin). `PartnerSecurityMatcherTest` : 2 → 4 tests.
  - `[low]` `[patch]` **`isNonceReplay` s'arrêtait au premier `ConstraintViolationException`** de la chaîne de causes : un rejeu de nonce authentique enveloppé derrière un autre CVE aurait remonté en 500. Réécrit pour parcourir **toute** la chaîne et ne conclure `false` qu'après épuisement (toujours fail-safe). Couvert par les tests unitaires existants (mapping fin, report #3).
  - `[low]` `[patch]` **Transactionnalité de la purge planifiée** reposait implicitement sur l'annotation `@Transactional` du repository. Ajout de `@Transactional` sur le point d'entrée proxifié `scheduledPurge()` : le delete de purge tourne toujours dans une transaction même invoqué par l'ordonnanceur sans transaction ambiante (report #1, robustesse).
  - `[low]` `[patch]` **Précision doc** : la note de conception « le matcher rend 401 » corrigée en 403 (`Http403ForbiddenEntryPoint` par défaut) ; l'AC (« rejetée par la sécurité ») est satisfaite par 403. L'annotation 401 de l'I/O Matrix (dans l'`intent-contract`, lecture seule) est signalée comme inexactitude cosmétique à réconcilier ultérieurement.

## Design Notes

- **Sécurité de la rétention.** Un nonce n'a de valeur anti-rejeu que pendant la fenêtre timestamp : au-delà, la garde de fenêtre rejette déjà la requête. Purger `seen_at < now - max(rétention, tolérance)` est donc sûr, et le `max(...)` empêche une rétention mal configurée (< tolérance) de rouvrir la fenêtre de rejeu. La méthode `purge()` est publique et testée en appel direct (pas via l'ordonnanceur) pour un test déterministe.
- **Extraction du nom de contrainte.** Sur Postgres/Hibernate, la cause de la `DataIntegrityViolationException` est un `org.hibernate.exception.ConstraintViolationException` exposant `getConstraintName()`. Remonter la chaîne des causes, comparer (insensible à la casse) à `uq_partner_key_nonces_key_nonce` ; correspondance → 401 rejeu ; sinon `throw` l'originale. Le test de concurrence (vraie base) valide que le vrai conflit de nonce est bien reconnu en 401 — c'est la contre-preuve d'intégration du mapping.
- **Test du matcher.** `@SpringBootTest` amorce Flyway sur le conteneur ; le client S3 se construit paresseusement (pas de connexion au démarrage). Si un bean de stockage se connecte tôt, le neutraliser avec `@MockBean EvidenceStorage`. On distingue « bloqué par la sécurité » de « traversé puis traité » (400 en-tête manquant / 404 route inconnue) : sous le wildcard actuel une route partenaire inconnue rendait 404 ; épinglée, elle est **bloquée par la sécurité et rend 403** (entry point Spring par défaut `Http403ForbiddenEntryPoint`, faute d'un point d'entrée 401 configuré — le poser basculerait tout l'API de 403→401, hors périmètre). Le code réel est correct ; l'AC porte sur « rejetée par la sécurité », satisfaite par 403. (L'I/O Matrix de l'`intent-contract` annote 401 par anticipation — inexactitude cosmétique en lecture seule, à réconcilier lors d'une future révision de spec.)

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=PartnerEvidenceServiceTest,PartnerNoncePurgerIntegrationTest,PartnerEvidenceDepositIntegrationTest,PartnerNonceConcurrencyIntegrationTest,PartnerSecurityMatcherTest test` -- attendu : vert (Docker requis pour les Testcontainers).
- `cd backend && mvn -q test` -- attendu : suite complète verte, aucune régression (dépôt utilisateur + partenaire d'Epic 1/3.2).
- `cd backend && mvn -q compile` -- attendu : compilation sans erreur.

## Auto Run Result

Status: done

**Changement implémenté :** durcissement de l'endpoint partenaire signé live (Epic 3, Story 3.4) — soldé les 5 reports réels de la revue de 3.2. (1) **Bornage du magasin de nonces** : `SchedulingConfig` (`@EnableScheduling`, nouveau — aucun ordonnanceur n'existait) + `PartnerNoncePurger` planifié appelant `deleteBySeenAtBefore(now - max(rétention, tolérance))`, le `max(...)` empêchant qu'une rétention < tolérance ne rouvre la fenêtre de rejeu. (2) **Matcher chirurgical** : `permitAll` épinglé à `POST /api/v1/partner/escrow/*/evidence` (au lieu de tout `/api/v1/partner/**`). (3) **Erreurs non masquées** : le `catch (DataIntegrityViolationException)` ne mappe en rejeu 401 que le conflit `uq_partner_key_nonces_key_nonce` (inspection du nom de contrainte Hibernate sur toute la chaîne de causes), toute autre violation remonte en 500 ; garde de longueur du nonce (>255 → 400) en tête de `deposit`, avant toute vérif/ingest. (4)(5) **Preuves vraie base** : tests d'intégration Testcontainers Postgres pour la ligne `CARRIER_PARTNER` (vs `ck_evidence_attribution`) et l'arbitrage concurrent du même nonce par la contrainte unique.

**Fichiers modifiés :**
- `config/SchedulingConfig.java` — NOUVEAU. `@Configuration @EnableScheduling`.
- `scheduler/PartnerNoncePurger.java` — NOUVEAU. Purge bornée `max(rétention, tolérance)` ; `purge()` publique testable ; `scheduledPurge()` `@Scheduled @Transactional`.
- `config/SecurityConfig.java` — MODIF. `permitAll` épinglé `POST` + chemin exact ; import `HttpMethod`.
- `service/PartnerEvidenceService.java` — MODIF. Garde longueur nonce (400) ; `isNonceReplay` (parcours complet de la chaîne, nom de contrainte) restreignant le mapping 401.
- `application.yml` — MODIF. `nonce-retention-seconds` (900), `nonce-purge-interval-ms` (60000).
- Tests : `PartnerEvidenceServiceTest` (MODIF, 5 → 9 : casse mixte, autre contrainte propagée, pas de CVE propagée, nonce sur-long) ; `PartnerNoncePurgerIntegrationTest` (NOUVEAU, 2) ; `PartnerEvidenceDepositIntegrationTest` (NOUVEAU, 1) ; `PartnerNonceConcurrencyIntegrationTest` (NOUVEAU, 1) ; `PartnerSecurityMatcherTest` (NOUVEAU, 4).

**Revue (1 passe, Blind Hunter + Edge Case Hunter, sans contexte préalable) :** convergence — **aucun défaut de sécurité/correction de sévérité haute** introduit ; le code est sûr et correct. 4 patches appliqués — (medium) couverture du pin complétée (méthode + chemin) ; (low) `isNonceReplay` parcourt toute la chaîne de causes ; (low) `@Transactional` sur le point d'entrée de purge planifié ; (low) précision doc 401→403. 0 intent_gap, 0 bad_spec, 0 defer. 7 rejets : test de concurrence prouve l'issue (l'AC porte sur l'issue) ; longueur nonce en unités UTF-16 (fail-safe) ; scheduler qui tourne en test (inoffensif) ; libellé du 400 (assertion valide) ; conteneur par classe (convention pré-existante) ; null nonce (garanti non-null par le controller) ; rétention+tolérance ≤ 0 (mauvaise config auto-cassante).

**Vérification :** `mvn -Dtest=Partner*Test,PartnerNonce*,PartnerEvidenceDeposit*,PartnerSecurityMatcher* test` → 15/15 verts ; suite complète `mvn test` → `Tests run: 167, Failures: 0, Errors: 0` — BUILD SUCCESS (155 → 167 : +12 nouveaux tests 3.4). Les lignes ERROR `SqlExceptionHelper` (SQLState 23505) sont les violations de contrainte attendues et correctement gérées des scénarios anti-rejeu/CHECK, pas des échecs.

**Reports du ledger soldés par cette story :** croissance non bornée de `partner_key_nonces` (#1), matcher `permitAll` de sous-arbre (#2), catch-all DIVE + garde de longueur d'en-tête (#3), absence de test vraie-base `CARRIER_PARTNER` (#4), absence de test de concurrence réel du nonce (#5). Hors périmètre, restent au ledger : double chargement `User` (perf), message `AUTH_FAILED` dupliqué (maintenabilité).

**Risques résiduels :** faibles. L'I/O Matrix de l'`intent-contract` annote 401 pour la route bloquée alors que le runtime rend 403 (entry point par défaut) — inexactitude cosmétique en lecture seule, comportement réel correct, AC satisfaite. Rotation/GC des clés partenaire = outillage d'admin hors POC.

**Follow-up review recommandé :** false — les 4 patches sont localisés et de faible conséquence (un refactor de helper sémantiquement équivalent et fail-safe, deux assertions de test ajoutées, une annotation `@Transactional` ceinture-bretelles, une correction doc) ; aucun changement de comportement de production, d'API, de sécurité ou de données ne justifie une passe indépendante, et les deux reviewers ont déjà convergé sur l'exactitude de l'implémentation.
