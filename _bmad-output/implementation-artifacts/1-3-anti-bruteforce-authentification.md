---
baseline_commit: 77cd3ffe8ded419fed2d0964e2cbea07ed22fb07
---
# Story 1.3: Anti-bruteforce sur l'authentification

Status: done

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


### Review Findings (code review 2026-07-25, 3 relecteurs — 33 findings bruts, convergence critique triple)

Les 3 relecteurs ont indépendamment identifié les MÊMES trois failles critiques — signal fort ayant justifié une refonte du limiteur plutôt que des retouches.

- [x] [Review][Patch] CRIT1 — Contournement par URI encodée/matrice : `getRequestURI()` brut comparé par egalité → chemin normalisé via UrlPathHelper (urlDecode + removeSemicolon) ; le `;` est de toute façon bloqué en amont par StrictHttpFirewall (prouvé)
- [x] [Review][Patch] CRIT2 — Réarmement du quota par succès contrôlé : `recordSuccess(remove)` supprimé → `recordAccountSuccess` n'efface QUE la salve du compte prouvé, jamais un verrou d'origine (test dédié : l'origine se verrouille malgré les succès intercalés)
- [x] [Review][Patch] CRIT3 — Fuite mémoire des états sous-seuil : éviction sur `lastSeen > rétention` quel que soit le compteur + plafond dur `max-entries` avec purge LRU (tests éviction + cap)
- [x] [Review][Patch] MAJ1 — Clé compte manquante (Task 1 non tenue) : clé `account|email` ajoutée (corps mis en cache via CachedBodyRequestWrapper puis rejoué au contrôleur) — bruteforce distribué sur un compte freiné ; clé origine unifiée login+register (budget commun, plus de ×2)
- [x] [Review][Patch] MAJ2 — Clé IP pulvérisable en IPv6 : normalisation en préfixe /64
- [x] [Review][Patch] MAJ3 — Audit à chaque 429 (amplification DB) : `recordFailure` retourne « justLocked » → audit UNE fois par pose de verrou, jamais sur requête déjà bloquée (test : le compte d'audit n'augmente plus sur une 2e requête bloquée) ; try/catch pour qu'un échec DB ne casse pas le 429
- [x] [Review][Patch] MAJ4 — Comptage sur `status>=400` trop grossier : compte 401 (identifiants) + 409 (conflit) ; exclut 400 (validation) et 5xx (incident). **Échec de login basculé 400→401** (UnauthorizedException, code AUTH_FAILED, message opaque anti-énumération inchangé) — meilleure pratique et signal précis
- [x] [Review][Patch] MOY1 — Troncature retryAfter : arrondi au supérieur (ne rouvre plus ~1 s trop tôt)
- [x] [Review][Patch] MOY2 — Overflow backoff si base mal configurée : multiplication saturante
- [x] [Review][Patch] MOY3 — Ordre du filtre implicite : `@Order(LOWEST_PRECEDENCE)` explicite (après la chaîne Security)
- [x] [Review][Patch] MOY4 — Fenêtre de comptage : échecs trop espacés ne s'additionnent plus (atténue NAT/CGNAT, n'accumule pas éternellement)
- [x] [Review][Patch] MOY5 — Pollution du singleton entre @SpringBootTest : `reset()` package-private appelé @BeforeEach + horloge remise à l'origine
- [x] [Review][Patch] MIN1 — Enveloppe 429 alignée sur le contrat standard (timestamp, status, error, code, message + retryAfterSeconds ; test de forme)
- [x] [Review][Patch] MIN2 — Config externalisée (application.yml escrow.auth.ratelimit.*)
- [x] [Review][Defer] DEF1 — Race check-then-act (rafale concurrente obtient un burst borné avant verrou) : inhérent à un filtre servlet ; l'attaquant gagne un burst puis est bloqué ; atténué (synchronized par État, remove hors lock supprimé) — durcissement (verrouillage atomique/token bucket) déféré au ledger
- [x] [Review][Defer] DEF2 — Stratégie trusted-proxy / X-Forwarded-For : dépend DUREMENT du reverse proxy TLS de la **Story 1.4** (sans lui l'IP réelle est soit le proxy soit spoofable) — à traiter en 1.4 ; sans quoi, derrière proxy, l'IP unique du proxy = clé partagée. Lien de séquencement noté.
- [x] [Review][Defer] DEF3 — Limitation distribuée multi-instances : post-Story 11.3 (état en mémoire process, correct en mono-instance MVP)
- Rejeté : bean Clock « mal placé » dans SchedulingConfig — c'est une dépendance de scheduling légitime, le @Primary de test est un idiome de test standard.

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

- 2026-07-25 (post-review) : refonte du limiteur sur 3 critiques triplement confirmées + 11 patchs majeurs/mineurs ; 3 reports déférés (race, trusted-proxy→1.4, distribué→11.3). Login raté 400→401. Backend 13 unit + 5 intégration ; suite complète re-verte.
- 2026-07-25 : Story implémentée — anti-bruteforce applicatif complet (AC1: 429+backoff progressif+audit ; AC2: rétablissement auto prouvé sur horloge pilotée).
