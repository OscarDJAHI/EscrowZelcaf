---
baseline_commit: 77cd3ffe8ded419fed2d0964e2cbea07ed22fb07
---
# Story 1.3: Anti-bruteforce sur l'authentification

Status: review

## Story

As a utilisateur,
I want que mon compte résiste aux attaques par force brute,
So that mes fonds et mes données restent protégés même si mon email est connu.

## Acceptance Criteria

1. **Given** N tentatives échouées sur `/auth/login` ou `/register` depuis une même origine, **When** le seuil est franchi, **Then** les tentatives suivantes reçoivent 429 avec backoff progressif (NFR-P2), **And** l'événement est journalisé dans `audit_logs`.
2. **Given** un utilisateur légitime après la fenêtre de limitation, **When** il se reconnecte avec le bon mot de passe, **Then** l'accès est rétabli sans intervention manuelle.

## Tasks / Subtasks

- [x] Task 1 — `AuthRateLimiter` (service pur, Clock injectable) : compteur d'échecs par origine (IP + email pour login, IP pour register), seuil configurable (défaut 5), verrou à backoff progressif doublant (30 s → cap 15 min), reset sur succès, expiration auto (AC2), éviction périodique (@Scheduled, SchedulingConfig existant).
- [x] Task 2 — `AuthRateLimitFilter` (OncePerRequestFilter, POST /api/v1/auth/login|register uniquement) : bloqué → 429 enveloppe JSON {code: RATE_LIMITED} + Retry-After ; sinon chaîne puis enregistre échec (status >= 400) / succès (2xx). IP = remoteAddr (X-Forwarded-For = territoire 1.4/11.3, spoofable sans proxy de confiance — documenté).
- [x] Task 3 — Code d'erreur : `ErrorCode.RATE_LIMITED(TRANSIENT)` + mise à jour DÉLIBÉRÉE du contrat (ErrorCodeContractTest) + miroir frontend `replayFailure.js` (TRANSIENT_CODES) + tests frontend.
- [x] Task 4 — Audit : `AuditService.recordAuthRateLimited(path, clientIp)` REQUIRES_NEW, transaction_id null (colonne nullable V1), payload {event: AUTH_RATE_LIMITED}.
- [x] Task 5 — Tests : unitaires limiter (seuil, doublement, cap, expiration, reset) + intégration MockMvc (5 échecs → 429 + Retry-After + ligne d'audit ; bon mot de passe après fenêtre → 200 ; register limité aussi) ; suites backend + frontend complètes vertes.

## Dev Notes

- Endpoints réels : `/api/v1/auth/login|register` (AuthController, permitAll). Enveloppe d'erreur = GlobalExceptionHandler.body(status, ErrorCode, message).
- Pas de nouvelle dépendance (pas de Caffeine/bucket4j — ConcurrentHashMap + éviction planifiée suffisent au MVP mono-instance ; la limitation distribuée = post-11.3, noté au ledger si besoin).
- audit_logs.transaction_id nullable (V1) ; idiome payload ObjectNode + REQUIRES_NEW (cf. recordFailure).
- Contrat ErrorCode : le test est CONÇU pour casser — le mettre à jour fait partie du changement coordonné (backend + miroir frontend + messages).
- Ne pas limiter les autres endpoints (JWT les protège) ; ne pas toucher au harnais Vitest.

### References

[Source: epics.md#Story-1.3] · [Source: project-context.md] · [Source: AD-10/ErrorCode + replayFailure.js]

## Dev Agent Record

### Agent Model Used

claude-fable-5, 2026-07-25

### Completion Notes List

- `AuthRateLimiter` : état par origine (clé = chemin|IP), seuil 5, verrous 30 s doublés jusqu'au cap 900 s, reset sur succès, expiration auto (AC2), éviction planifiée. Horloge injectable — bean `Clock` ajouté à SchedulingConfig (premier du repo).
- `AuthRateLimitFilter` : @Component auto-enregistré (PAS dans la chaîne Security — évite la double exécution ; s'exécute après la chaîne, avant le contrôleur : le hachage de mot de passe est court-circuité en cas de blocage). 429 + Retry-After + enveloppe {code: RATE_LIMITED, message, retryAfterSeconds}. IP = remoteAddr, X-Forwarded-For documenté hors périmètre (1.4/11.3).
- `ErrorCode.RATE_LIMITED(TRANSIENT)` : mise à jour COORDONNÉE du contrat — test backend (partition exacte à 4) + miroir frontend TRANSIENT_CODES (le spec frontend qui parse ErrorCode.java valide la synchro) ; 170 tests frontend verts.
- Audit : `recordAuthRateLimited` REQUIRES_NEW, colonnes null (V1 nullable), payload {event: AUTH_RATE_LIMITED, path, clientIp} — prouvé en intégration (count croît).
- Tests : 9 unitaires limiter (horloge contrôlée : seuil, doublement 30→60→120, cap, expiration, reset, indépendance, éviction) + 3 intégration Testcontainers (429+Retry-After+audit+rétablissement AC2 via Clock @Primary pilotable ; origines indépendantes ; register limité).
- Limitation distribuée (multi-réplicas) hors périmètre MVP mono-instance — à réévaluer avec la stack 11.3.

### File List

- backend/src/main/java/com/zlecaf/escrow/security/AuthRateLimiter.java (nouveau)
- backend/src/main/java/com/zlecaf/escrow/security/AuthRateLimitFilter.java (nouveau)
- backend/src/main/java/com/zlecaf/escrow/config/SchedulingConfig.java (bean Clock)
- backend/src/main/java/com/zlecaf/escrow/service/AuditService.java (recordAuthRateLimited)
- backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java (RATE_LIMITED)
- backend/src/test/java/com/zlecaf/escrow/security/AuthRateLimiterTest.java (nouveau, 9 tests)
- backend/src/test/java/com/zlecaf/escrow/security/AuthRateLimitIntegrationTest.java (nouveau, 3 tests)
- backend/src/test/java/com/zlecaf/escrow/domain/ErrorCodeContractTest.java (partition TRANSIENT à 4)
- frontend/src/utils/replayFailure.js (miroir TRANSIENT_CODES)
- _bmad-output/implementation-artifacts/sprint-status.yaml + ce fichier

## Change Log

- 2026-07-25 : Story implémentée — anti-bruteforce applicatif complet (AC1: 429+backoff progressif+audit ; AC2: rétablissement auto prouvé sur horloge pilotée).
