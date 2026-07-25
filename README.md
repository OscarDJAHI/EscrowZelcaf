# Escrow B2B Platform — POC (ZLECAf intra-African trade)

[![CI](https://github.com/OscarDJAHI/EscrowZelcaf/actions/workflows/ci.yml/badge.svg?branch=develop)](https://github.com/OscarDJAHI/EscrowZelcaf/actions/workflows/ci.yml)

Proof of Concept for a **B2B escrow (séquestre) platform** securing cross-border
trade under the African Continental Free Trade Area (ZLECAf). A buyer's funds are
held by the platform and only released to the seller once delivery is validated —
eliminating counterparty fraud risk for both sides.

This repository implements a **functional vertical slice**: the financial state
machine at the core, a JWT-secured REST API, an immutable audit trail, an async
notification fabric (RabbitMQ + signed webhooks), and an offline-capable Vue 3 PWA.

---

## Architecture

```
┌────────────────────┐        ┌───────────────────────────────┐        ┌──────────────┐
│   Vue 3 PWA         │  HTTPS │   Spring Boot 3 (Java 21)      │  JDBC  │  PostgreSQL  │
│  (offline queue,    │ ─────► │   Escrow Core Engine          │ ─────► │  (ACID +     │
│   stepper, Pinia)   │  JWT   │   • state machine             │        │   row locks) │
└────────────────────┘        │   • pessimistic locking       │        └──────────────┘
                              │   • immutable audit log       │
                              │   • HMAC-signed webhooks      │  AMQP   ┌──────────────┐
                              │   • after-commit fan-out      │ ─────►  │  RabbitMQ    │──► n8n
                              └───────────────────────────────┘         │ escrow.events│  (orchestrator)
                                                                        └──────────────┘
```

| Layer | Stack | Location |
|-------|-------|----------|
| Backend core | Java 21, Spring Boot 3.3, Spring Security (JWT), Spring Data JPA, Flyway | [`backend/`](backend/) |
| Database | PostgreSQL 16 (schema owned by Flyway migrations) | [`backend/src/main/resources/db/migration`](backend/src/main/resources/db/migration) |
| Messaging | RabbitMQ (topic exchange `escrow.events`) | [`RabbitConfig`](backend/src/main/java/com/zlecaf/escrow/config/RabbitConfig.java) |
| Frontend | Vue 3, Vite, Pinia, TailwindCSS, vite-plugin-pwa | [`frontend/`](frontend/) |
| Infra | Docker Compose | [`infra/docker-compose.yml`](infra/docker-compose.yml) |

---

## The escrow state machine

The heart of the system is a **pure, side-effect-free state machine**
([`EscrowStateMachine`](backend/src/main/java/com/zlecaf/escrow/service/EscrowStateMachine.java))
enforcing both transition legality and per-transaction role authorisation:

| From | Event | To | Authorised role |
|------|-------|-----|-----------------|
| `NONE` | `CREATE` | `INITIATED` | Buyer |
| `INITIATED` | `PAY_FUNDS` | `FUNDS_LOCKED` | Buyer / System |
| `FUNDS_LOCKED` | `SHIP_GOODS` | `SHIPPED` | Seller |
| `FUNDS_LOCKED` | `OPEN_DISPUTE` | `DISPUTED` | Buyer / Seller |
| `SHIPPED` | `DELIVERY_CONFIRMED` | `RELEASED` | Buyer / System |
| `SHIPPED` | `OPEN_DISPUTE` | `DISPUTED` | Buyer |
| `DISPUTED` | `RESOLVE_RELEASE` | `RELEASED` | Admin |
| `DISPUTED` | `RESOLVE_REFUND` | `REFUNDED` | Admin |

Any (state, event) pair not in this whitelist is rejected (`409`); a valid event
attempted by the wrong role is rejected (`403`). **Both outcomes are written to
the immutable `audit_logs` table** — successful transitions atomically with the
state change, rejected ones in a separate transaction so the attempt survives the
rollback.

### Financial-safety guarantees

- **Concurrency**: each event takes a `SELECT … FOR UPDATE` pessimistic row lock
  ([`findByIdForUpdate`](backend/src/main/java/com/zlecaf/escrow/repository/EscrowTransactionRepository.java)),
  serialising concurrent submissions and preventing lost-update / double-spend races.
  An `@Version` optimistic guard backs it up.
- **Atomicity**: state change + audit entry commit together (`@Transactional`).
- **Reliable notifications**: RabbitMQ publish and webhook dispatch fire only
  `AFTER_COMMIT`, so partners are never told about a transition that rolled back.
- **Webhook integrity**: every payload is signed HMAC-SHA256 in the
  `X-Escrow-Signature` header (constant-time verification).

---

## Quick start (full stack)

Requires Docker + Docker Compose.

```bash
# 1. Secrets locaux (une fois) — infra/.env n'est JAMAIS versionné :
cp infra/.env.example infra/.env   # puis remplacez chaque valeur (openssl rand -base64 48)

# 2. Stack complète :
docker compose -f infra/docker-compose.yml up --build
```

Without `infra/.env`, compose fails fast **naming the missing variable** — same
contract as the backend, which refuses to boot without its critical secrets
(`ESCROW_JWT_SECRET`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_PASSWORD`,
`ESCROW_STORAGE_SECRET_KEY`) and lists every missing one in a single error.

- PWA:            http://localhost:5173
- API:            http://localhost:8080
- Health:         http://localhost:8080/actuator/health
- RabbitMQ admin: http://localhost:15672  (credentials: your `infra/.env`)

> **Port note:** the backend publishes on host port `8080`. If another service
> already holds `8080` (e.g. a local nginx), stop it or change the mapping in
> `infra/docker-compose.yml`. Likewise a local PostgreSQL on `5432` shadows the
> container's published port for host tools — the containers themselves talk over
> the Docker network and are unaffected.

### Running components individually

**Backend** (needs a PostgreSQL; RabbitMQ optional — it degrades gracefully):
```bash
cd backend
mvn spring-boot:run          # or: mvn package && java -jar target/escrow-core-*.jar
```
Configuration via env vars (`SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`,
`ESCROW_JWT_SECRET`, `SERVER_PORT`) — see `application.yml`.

**Frontend**:
```bash
cd frontend
npm install && npm run dev   # http://localhost:5173
```

---

## API

| Method & path | Purpose | Auth |
|---|---|---|
| `POST /api/v1/auth/register` | Create account, returns JWT | public |
| `POST /api/v1/auth/login` | Authenticate, returns JWT | public |
| `POST /api/v1/escrow` | Buyer initiates a contract | JWT |
| `GET  /api/v1/escrow` | List the caller's transactions | JWT |
| `GET  /api/v1/escrow/{id}` | Transaction + audit trail | JWT (party/admin) |
| `POST /api/v1/escrow/{id}/event` | Drive the state machine (`{"event":"PAY_FUNDS"}`) | JWT |
| `POST /api/v1/webhooks/subscriptions` | Register a partner callback (HMAC secret) | JWT |
| `GET  /actuator/health` | Liveness | public |

### End-to-end example

```bash
API=http://localhost:8080
BUY=$(curl -s -X POST $API/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"buyer@ke.co","password":"secret123","role":"BUYER"}' | jq -r .token)
curl -s -X POST $API/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"seller@za.co","password":"secret123","role":"SELLER"}' > /dev/null

# Buyer creates the escrow, then pays; seller ships; buyer confirms → RELEASED.
ID=$(curl -s -X POST $API/api/v1/escrow -H "Authorization: Bearer $BUY" -H 'Content-Type: application/json' \
  -d '{"sellerEmail":"seller@za.co","amount":15000.50,"currency":"USD","description":"coffee"}' | jq -r .id)
curl -s -X POST $API/api/v1/escrow/$ID/event -H "Authorization: Bearer $BUY" \
  -H 'Content-Type: application/json' -d '{"event":"PAY_FUNDS"}'
```

---

## Tests

```bash
cd backend && mvn test
```

- `EscrowStateMachineTest` — full transition matrix, authorisation enforcement,
  illegal transitions, terminal states, UI `allowedEvents`.
- `HmacSignerTest` — deterministic signing, a known RFC vector, tamper rejection.

The state machine and HMAC signer are covered by fast, pure unit tests (no DB/broker).
An HTTP end-to-end scenario (register → escrow lifecycle → dispute → resolution,
plus audit-trail and RabbitMQ-fan-out assertions) was validated against a live
PostgreSQL + RabbitMQ stack.

> The backend suite needs a running **Docker** daemon (Testcontainers spins up
> PostgreSQL/MinIO). Frontend: `cd frontend && npm run test` (Vitest).

---

## CI (gate de tests)

Every push / pull request on `develop` and `main` runs
[`ci.yml`](.github/workflows/ci.yml):

| Job | What it runs | Gate |
|-----|--------------|------|
| **Backend (Maven + Testcontainers)** | `./mvnw -B verify` — full suite, real PostgreSQL via Docker | required check (branch protection: `main` strict, `develop` admin-bypassable) |
| **Frontend (Vitest + build PWA)** | `npm ci && npm run test && npm run build` | required check (idem) |
| **SBOM + scan vulnérabilités** | CycloneDX SBOMs (deps + Docker images, artefact `sbom`) + Trivy: secrets, deps, images — fail on unexempted CRITICAL/HIGH | advisory + weekly cron (until triage stabilises) |

**Reading a CI failure:**

- *Backend red* — open the job log and search `Tests run:` / `FAILURE`; a test
  that is green locally but red in CI is an environment issue (fix the workflow,
  not the test).
- *Frontend red* — Vitest prints the failing spec; `npm run build` failures are
  usually import/PWA config errors.
- *SBOM/scan red* — a new CRITICAL/HIGH CVE appeared. Either bump the dependency
  (trivial patch) or add a **dated, `exp:`-bounded** entry to [`.trivyignore-backend`](.trivyignore-backend) (backend-scoped only — frontend/images run without exemptions)
  (all current exemptions trace to the Boot 3.3.5 EOL debt, purged by Story 11.9).

---

## Mapping to the implementation plan

| Plan phase | Status in this POC |
|---|---|
| **1** Env, schema, JWT security | ✅ Flyway schema, Docker Compose, Spring Security + JWT |
| **2** State engine, locking, audit | ✅ `EscrowStateMachine`, pessimistic lock, immutable audit (success + failure) |
| **3** Async n8n & webhooks | ✅ RabbitMQ `escrow.events` publish + HMAC-SHA256 signed webhooks (after-commit). *n8n workflow wiring is left as an integration step against the live exchange.* |
| **4** PWA & offline mode | ✅ Vue 3 PWA, service worker, Pinia offline queue + auto-replay |
| **5** E2E, security, deploy | ◑ E2E happy-path + dispute verified locally; cloud/Cloudflare deploy out of POC scope |

---

## Repository layout

```
backend/     Spring Boot escrow core (Maven)
frontend/    Vue 3 PWA (Vite)
infra/       docker-compose.yml (postgres, rabbitmq, backend, frontend)
Docs/        Product & technical specifications (PRD, tech stack, schema, plan)
```
