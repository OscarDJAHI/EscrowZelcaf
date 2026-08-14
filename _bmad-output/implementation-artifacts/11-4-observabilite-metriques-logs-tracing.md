# Story 11.4: Observabilité — métriques, logs corrélés, tracing et alerting

Status: in-progress <!-- T0→T4 livrés (2026-08-11 → 2026-08-14). Reste T5 (alerting), T6 (invariant AD-16), T7 (tests/mutations), T8 (barrières). -->

<!-- ⚠️ TENUE DE LIVRES RATTRAPÉE LE 2026-08-14. Les commits T1→T3 des 11 et 12 août
     n'avaient mis à jour ni les cases ci-dessous, ni le Dev Agent Record, ni le Change
     Log : le fichier annonçait `ready-for-dev` alors que trois tâches étaient poussées.
     C'est le même écart que le suivi de sprint a déjà porté deux fois (chiffres périmés).
     Les cases cochées ci-dessous l'ont été en relisant les COMMITS, pas la mémoire. -->


<!-- Créée le 2026-08-11 par bmad-create-story. Quatre questions ouvertes sont listées en
     tête et DOIVENT être tranchées avant T1 : elles commandent le périmètre livrable. -->

## Story

As a exploitant,
I want des métriques, des logs corrélés, du tracing et des alertes actionnables,
so that je détecte et diagnostique les incidents — y compris tout écart de ségrégation des fonds — sans dépendre des signalements clients (NFR-P13, alerte de l'invariant NFR-P25).

## Acceptance Criteria

**AC1 — Métriques et logs corrélés**
**Given** l'application en fonctionnement
**When** on interroge la pile d'observabilité
**Then** des métriques (JVM, HTTP, pool Hikari, files du broker) sont exposées et scrapées, restituées dans un dashboard
**And** les logs sont structurés (JSON) avec un identifiant de corrélation propagé — la recherche par cet identifiant restitue toutes les lignes d'une même requête, écriture d'audit comprise.

**AC2 — Trace de bout en bout du parcours chaud**
**Given** un parcours chaud (versement de preuve, du contrôleur au stockage objet)
**When** une requête le traverse
**Then** une trace de bout en bout est consultable dans l'outil de tracing, corrélée aux logs par le même identifiant.

**AC3 — Alerting délivré, règles minimales actives**
**Given** le canal d'alerting configuré
**When** une alerte de test est déclenchée
**Then** elle est délivrée sur le canal opérateur (preuve de bout en bout)
**And** les règles minimales sont actives : taux de 5xx, DLQ non vide, saturation disque/pool.

**AC4 — Alerte de l'invariant de ségrégation (NFR-P25 / AD-16)**
**Given** la règle d'alerte de l'invariant de ségrégation des fonds (écart grand livre ↔ compte cantonné)
**When** une valeur d'écart non nulle est injectée sur la métrique de rapprochement (valeur factice de test)
**Then** une alerte de sévérité critique est délivrée immédiatement
**And** la règle est en place avant la mise en service du circuit financier (Epic 4), qui n'aura qu'à émettre la métrique.

**AC5 — Preuve par mutation** (convention projet, `project-context.md` §Testing Rules)
Toute garde livrée par cette story se prouve en la retirant et en constatant le rouge, puis en restaurant. Consigner chaque résultat.

---

## ✅ Décisions d'Oscard — 2026-08-11 (les quatre questions sont closes)

Tranchées en ouverture, sur les défauts recommandés. Le détail du raisonnement de chacune reste lisible ci-dessous ; ce bloc dit ce qui est retenu.

| | Décision |
|---|---|
| **Q1** | La pile monte dans `infra/docker-compose.yml`. Le **déploiement production est un écart NOMMÉ**, routé vers la Story 11-3. L'AC3 est donc tenable : la preuve de délivrance se fait sur la pile locale. |
| **Q2** | Canal d'alerte = **SMTP vers le Mailpit déjà présent au compose**. Ne préempte PAS le spike AR-P6 : SMTP est un protocole, pas un fournisseur (précédent 2-4 du 2026-08-10). |
| **Q3** | Jauge de rapprochement **pilotée par injection de dépendance**, jamais par un endpoint. Un fournisseur de valeur par défaut rend « pas de circuit financier » tant que l'Epic 4 n'a rien à émettre ; le test injecte son propre fournisseur. **Aucune surface de production n'est ajoutée** (interdiction 1-10 / 2-4 T0). |
| **Q4** | Configuration d'observabilité **regroupée** et commentée pour que 11-9 la migre d'un bloc. **Aucun bump de Spring Boot** dans cette story. |

**Décision complémentaire, prise en T0 et non couverte par les quatre questions : le tracing PORTE la corrélation.** Micrometer Tracing alimente `traceId`/`spanId` dans le MDC ; aucun `X-Request-Id` concurrent n'est introduit. Motif : deux notions d'identifiant se désaccordent au premier refactor, et l'AC2 exige de toute façon que trace et logs partagent le **même** identifiant — en créer un second obligerait à prouver leur égalité en permanence. L'échantillonnage n'y change rien : Micrometer ouvre un span pour chaque requête et alimente le MDC, l'échantillonnage ne décide que de l'**export** de la trace.

**Constat de T0 qui change une tâche : `AuditService` n'émet AUCUN log.** Il persiste des lignes `AuditLog` via son repository, sans logger. L'AC1 exige que la recherche par identifiant restitue les lignes « écriture d'audit comprise » — il faut donc que l'écriture d'audit **émette une ligne de log** corrélée. Ce n'est pas un changement de schéma : aucune colonne n'est ajoutée à `AuditLog`, la corrélation vit dans le MDC de la ligne de log.

---

## ⚠️ Questions ouvertes — TRANCHÉES LE 2026-08-11 (conservées pour le raisonnement)

Ces quatre points commandaient le périmètre livrable. Les trancher en ouverture, comme la Story 2.7 l'a fait pour ses trois questions, plutôt que de les découvrir à mi-parcours.

### Q1 — Où tourne la pile d'observabilité, puisque AR-P5 n'est pas tranché ?

Le préambule de l'Epic 11 dit que les spikes AR-P4/AR-P5 « débloquent […] l'observabilité ». Ils sont au backlog (Stories 11-2, 11-3). Mais la **contrainte dure** du spine ne parle pas d'eux : elle dit « 11.4 (alerte AD-16) **avant mise en service du circuit financier** » (SOLUTION-DESIGN §110). La dépendance à AR-P5 concerne le **déploiement de production** de la pile, pas sa construction.

Découpage proposé : la pile se monte dans `infra/docker-compose.yml`, à côté de Postgres/RabbitMQ/MinIO qui y sont déjà, et **son déploiement en production est un écart NOMMÉ, routé vers la Story 11-3**. L'instrumentation applicative (métriques, logs JSON, corrélation, spans) est de toute façon indépendante de la cible : c'est du code Spring.

**À confirmer par le PO.** Si la réponse est « on attend 11-3 », cette story se réduit à l'instrumentation et l'AC3 (preuve de délivrance d'alerte) devient impossible à tenir — il faut alors la scinder explicitement plutôt que de la déclarer faite.

### Q2 — Quel canal d'alerte opérateur, sans préempter le spike AR-P6 ?

L'AC3 exige une **preuve de bout en bout** : une alerte de test réellement délivrée. Or le choix du fournisseur e-mail/SMS est le spike 8-1 (AR-P6), non tranché.

Précédent applicable, à réutiliser tel quel : la décision du 2026-08-10 sur la Story 2-4 a livré un adaptateur SMTP + Mailpit en établissant que **« SMTP est un protocole, pas un fournisseur »** — le choix du prestataire restait entier. Le même raisonnement vaut ici : un webhook ou un SMTP vers le Mailpit **déjà présent dans le compose** prouve la chaîne sans rien préempter.

**À confirmer.** Et à écrire dans la story : ce que ce canal N'EST PAS (pas le canal de production, pas une décision AR-P6).

### Q3 — Comment injecter l'écart factice de l'AC4 sans ouvrir une porte en production ?

L'AC4 demande d'injecter « une valeur d'écart non nulle sur la métrique de rapprochement (valeur factice de test) ». L'Epic 4 n'existe pas : il n'y a ni grand livre, ni compte cantonné, ni job de rapprochement — seul `scheduler/PartnerNoncePurger.java` peuple le paquet `scheduler`.

⚠️ **Le piège est nommé d'avance.** La Story 1-10 (anti-énumération) et la décision T0 de la 2-4 ont toutes deux posé la même interdiction : **aucun endpoint de test ne s'ajoute à la surface de production** pour rendre un scénario observable. Un `POST /actuator/reconciliation-deviation` serait exactement cela. Les pistes qui respectent la règle :
- une jauge Micrometer alimentée par un composant dont la **valeur source** est nulle tant qu'Epic 4 ne l'implémente pas, et que le TEST pilote en injectant son fournisseur (pas en appelant un endpoint) ;
- ou la preuve de la règle d'alerte au niveau de l'outil (test de règle Prometheus sur série temporelle synthétique), l'émission réelle restant à Epic 4.

**À trancher** : l'AC4 exige-t-elle que l'alerte parte d'une métrique réellement scrapée, ou que la RÈGLE soit prouvée ? Les deux sont défendables ; elles ne coûtent pas la même chose.

### Q4 — Instrumenter sur Boot 3.3.5 en sachant que 11-9 migre vers 4.x

Le dépôt est en **Spring Boot 3.3.5**. La Story 11-9 (« migrations runtime Boot 4.1 / RabbitMQ 4.2 LTS ») est **bloquante avant le lancement production**. Deux coûts concrets, vérifiés dans la documentation Spring le 2026-08-11 :

| Sujet | Boot 3.3.5 (aujourd'hui) | Boot 4.x (cible 11-9) |
|---|---|---|
| Propriétés OTLP tracing | `management.otlp.tracing.*` | `management.opentelemetry.tracing.export.otlp.*` |
| Logs structurés JSON | **non natif** — exige `logstash-logback-encoder` | natif depuis 3.4 (`logging.structured.format.*`) |

Écrire la configuration en connaissance de cause et **la regrouper** pour que 11-9 la migre en un seul endroit. Ne PAS bumper Spring Boot dans cette story : c'est le territoire de 11-9 (précédent posé par la Story 11-1, `pom.xml` limité au strict nécessaire).

---

## Tasks / Subtasks

- [x] **T0 — Lire avant d'écrire, et faire trancher Q1→Q4** (préalable bloquant) — *2026-08-11, commit `02b9ed2`*
  - [x] Faire trancher les quatre questions ci-dessus par le PO et consigner chaque décision dans ce fichier avant d'écrire une ligne. → tableau des décisions en tête, plus **une cinquième décision** non prévue : le tracing PORTE la corrélation (pas de `X-Request-Id` concurrent).
  - [x] Lire `application.yml` / `application-prod.yml`.
  - [x] Lire `JwtAuthFilter` / `AuthRateLimitFilter`. **Conséquence non prévue** : aucun filtre de corrélation n'a finalement été écrit — Micrometer Tracing alimente le MDC lui-même, et ajouter un filtre aurait créé la seconde notion d'identifiant que la décision T0 refuse.
  - [x] Lire `AuditService` / `AuditLog`. **Constat qui a changé une tâche** : `AuditService` n'émettait AUCUN log. Corrélation portée par le MDC seul → **aucune migration Flyway**, aucune colonne ajoutée.
  - [x] Relever la base de tests : **514 backend** à l'ouverture.

- [x] **T1 — Métriques exposées et scrapées** (AC: 1) — *2026-08-11, commit `fef3b03`*
  - [x] Ajouter `micrometer-registry-prometheus` au `backend/pom.xml`, sans redéclarer l'actuator.
  - [x] Exposer `prometheus` sans élargir le reste → `include: health,info,prometheus`. **Trois couches de cantonnement** et non une : liste d'exposition, règle de sécurité épinglée au chemin EXACT (jamais `/actuator/**`), port de management séparé (9091).
  - [x] **Trois obstacles, dont deux muets** : 403 (Spring Security), 404 (`@ConditionalOnEnabledMetricsExport` → drapeau `management.prometheus.metrics.export.enabled` explicite — sans lui tout est en place, rien n'est exposé, et RIEN ne le dit), et `http_server_requests_seconds` qui naît du TRAFIC et non de la configuration.
  - [x] Familles JVM / HTTP / Hikari vérifiées par nom de métrique précis dans `ObservabilityIntegrationTest`.
  - [x] **Famille « files du broker » — DÉCIDÉE en T1, CÂBLÉE en T4 (close).** Décision écrite en T1 (`ObservabilityIntegrationTest` §javadoc) : la profondeur de file est un fait du BROKER, aucune dépendance ajoutée à cette application ne la ferait apparaître — elle vient du plugin `rabbitmq_prometheus`. L'écart a été nommé plutôt que maquillé par une assertion sur une métrique cliente. Le câblage (plugin activé + cible de scraping) est livré en T4, où le tableau de bord en a besoin.
  - [x] Prometheus au compose, scraping du **port de management** (9091) et jamais du port applicatif.

- [x] **T2 — Logs structurés JSON et identifiant de corrélation** (AC: 1) — *2026-08-12, commit `9d88c5f`*
  - [x] Configuration de logging posée de zéro : `logback-spring.xml` + `logstash-logback-encoder` 8.0.
  - [x] Regroupée et commentée pour la migration 11-9 (l'encodeur est la **première dépendance à retirer**, et `logback-spring.xml` disparaît avec elle).
  - [x] Le tracing porte la corrélation (décision T0) : `traceId`/`spanId` du MDC, aucun `X-Request-Id` concurrent.
  - [x] Identifiant porté jusqu'à l'**écriture d'audit** — le point le PLUS PROFOND du parcours : s'il est atteint, tout ce qui est moins profond l'est aussi.
  - [x] Nettoyage du MDC prouvé par un test dédié (fuite d'un fil à l'autre).
  - [x] **Correction d'un défaut de T1, trouvée en T2** : le découpage par profil gardait un motif lisible sous `test`, si bien que la branche `test` n'attachait pas l'appender JSON — **supprimer TOUT `logback-spring.xml` laissait la suite verte**. Une exigence que rien ne peut falsifier n'est pas une exigence. Un seul format désormais, partout, et le test interroge l'encodeur QUI TOURNE.

- [x] **T3 — Trace de bout en bout du parcours chaud** (AC: 2) — *2026-08-12, commit `cd6ca54`*
  - [x] `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`, propriétés `management.otlp.tracing.*` avec le renommage 4.x signalé sur la clé.
  - [x] Jaeger au compose (collecteur OTLP **et** interface), endpoint OTLP **injecté** et non codé en dur.
  - [x] **Le SDK AWS v2 n'est PAS instrumenté par Micrometer** : sans intervention, la trace s'arrêtait à la couche web et le poste le plus lent du parcours — l'écrit réseau vers le stockage objet — restait invisible. Observation posée dans `MinioEvidenceStorage`, autour de l'appel S3 **et de lui seul** : le chiffrement d'enveloppe reste dehors, l'englober ferait passer un ralentissement CPU pour une lenteur réseau.
  - [x] Échantillonnage écrit et non subi : 100 % en développement, 0 % sous la suite — ce qui **démontre au passage** que l'échantillonnage ne gouverne que l'EXPORT, jamais l'alimentation du MDC.
  - [x] Corrélation prouvée par câblage réel (application entière + vrai MinIO + vrai versement multipart), en comparant le traceId vu DANS le stockage à celui vu dans l'écriture d'audit. **Un test d'adaptateur isolé aurait prouvé que l'observation est créée, pas qu'elle est BRANCHÉE.**
  - [x] **Le 409 a appris une règle métier** : la fenêtre de versement n'ouvre qu'à partir de `FUNDS_LOCKED` (FR-8/AD-2). Le financement a été ajouté au montage plutôt que l'état écrit directement en base.

- [x] **T4 — Dashboard** (AC: 1) — *2026-08-14*
  - [x] Tableau de bord versionné (`infra/observability/grafana/dashboards/escrow-observabilite.json`), provisionné en déclaratif, monté en **lecture seule**. Verrou prouvé par écriture réelle : HTTP 400 « Cannot save provisioned dashboard », titre inchangé. ⚠️ `meta.canSave` rend `true` — c'est la permission de l'utilisateur, pas le verrou.
  - [x] Aucun secret en clair : `GF_SECURITY_ADMIN_PASSWORD` en `:?` (le compose **refuse de démarrer** sans la variable — prouvé par mutation) et non en `:-`, sans quoi l'image resterait sur `admin/admin` sans que rien ne le dise. `infra/.env` gitignoré, aucune occurrence du mot de passe dans les fichiers suivis.
  - [x] **La quatrième famille de l'AC1 est câblée ici** (elle avait été décidée en T1 et nommée comme écart) : plugin `rabbitmq_prometheus` activé, cible de scraping dédiée.
  - [x] **Deux cibles de scraping sur le même broker, et ce n'est pas un doublon.** Mesuré : `/metrics/per-object` n'est PAS un sur-ensemble de `/metrics` — il perd les trois métriques d'alarme du nœud, exactement la matière de la règle « saturation disque » de T5. Une seule cible aurait coûté une règle de l'AC3, et le manque ne se serait vu qu'en écrivant la règle — ou jamais.
  - [x] **Les 13 requêtes des panneaux vérifiées une par une contre un Prometheus vivant.** 12 rendaient des points, **1 rendait le vide** : la part de 5xx laissait le panneau BLANC en l'absence d'erreurs, indistinguable d'une requête cassée. Corrigée par `or vector(0)`, les deux branches prouvées (à vide → 0 ; numérateur non vide → la vraie valeur, non écrasée).
  - [x] **Défaut trouvé en montant la pile, et qui ne venait pas de T4** : la cible `escrow-backend` était `down` (connexion refusée sur 9091) et les logs n'étaient pas en JSON — **l'image du backend était périmée**, piège déjà connu de ce projet. Le scraping de bout en bout n'avait jamais tourné : T1 et T3 s'étaient prouvés par des tests JVM, qui ne montent pas le compose.

- [ ] **T5 — Alerting : canal et règles minimales** (AC: 3)
  - [ ] Canal selon Q2, avec écrit noir sur blanc ce qu'il n'est pas (pas une décision AR-P6).
  - [ ] Les trois règles minimales de l'AC : **taux de 5xx**, **DLQ non vide**, **saturation disque/pool**. Chacune doit reposer sur une métrique réellement exposée par T1 — vérifier plutôt que supposer, une règle sur une série inexistante ne se déclenche jamais et **ne rougit jamais non plus**.
  - [ ] ⚠️ **T4 a déblayé deux des trois règles et BUTÉ sur la troisième.** Les expressions de *taux de 5xx* (avec son `or vector(0)`) et de *saturation* (alarmes du nœud + `hikaricp_connections_pending`) sont posées au tableau de bord et vérifiées rendre des points. Mais **il n'y a AUCUNE DLQ dans ce dépôt** : `RabbitConfig` ne déclare qu'une file, `escrow.events.queue`, sans `x-dead-letter-exchange` ni file de rebut. La règle « DLQ non vide » n'a donc rien à surveiller — à trancher en ouverture de T5, et pas à mi-parcours : soit la DLQ est déclarée ici (changement de topologie du broker, hors périmètre annoncé de la story), soit la règle est écrite ET nommée comme inerte jusqu'à ce qu'une DLQ existe. Une règle sur une file inexistante est le cas d'école de la preuve creuse.
  - [ ] Preuve de bout en bout : déclencher une alerte de test et **constater sa réception** sur le canal. Consigner la preuve.

- [ ] **T6 — Alerte de l'invariant de ségrégation** (AC: 4)
  - [ ] Formule AD-16, à ne pas réinventer : `wallets + séquestres + réservations + transit = miroir cantonnement` (SOLUTION-DESIGN §25). L'écart est la différence ; **tout écart non nul est une alerte critique**.
  - [ ] Poser la métrique de rapprochement et la règle d'alerte **sévérité critique, sans délai de tolérance** — l'AC dit « immédiatement ».
  - [ ] Selon Q3 : injecter l'écart factice **sans ajouter d'endpoint à la surface de production** (interdiction posée par 1-10 et par T0 de la 2-4).
  - [ ] Écrire, à l'endroit du code, le contrat que l'Epic 4 devra honorer : « n'aura qu'à émettre la métrique ». Nommer la métrique et son unité pour que 4-3 (grand livre) la trouve.
  - [ ] La fréquence de rapprochement est **un paramètre par corridor** (SOLUTION-DESIGN §101) — ne pas graver une constante globale.

- [ ] **T7 — Tests et preuve par mutation** (AC: 5)
  - [ ] Test d'intégration : l'endpoint de métriques répond et **contient nommément** les quatre familles de l'AC1. Assertion par présence de métriques précises, pas par « la réponse n'est pas vide ».
  - [ ] Test : deux lignes de log d'une **même** requête portent le **même** identifiant, et deux requêtes distinctes en portent de **différents**. ⚠️ **Assertion négative appariée à une positive** — c'est la cinquième occurrence du motif sur ce projet.
  - [ ] Test du nettoyage du MDC : une seconde requête sur le **même thread** ne porte pas l'identifiant de la première. C'est le test que l'on oublie, et le défaut qu'il attrape est invisible à l'œil.
  - [ ] Test : l'écriture d'audit porte bien l'identifiant de la requête qui l'a provoquée (AC1, « écriture d'audit comprise »).
  - [ ] Mutations obligatoires, une par garde : retrait du filtre de corrélation, retrait du nettoyage MDC, retrait de l'exposition `prometheus`, règle d'alerte AD-16 neutralisée. Pour chacune : la suite **entière** relancée, et vérification que ce sont bien **les tests visés** qui rougissent.
  - [ ] ⚠️ Piège récurrent : une règle d'alerte ou un dashboard **ne sont pas couverts par la suite JVM**. Si l'on ne peut pas les faire rougir, le dire — et préférer une vérification outillée (test de règle) à une preuve creuse.

- [ ] **T8 — Barrières** (toutes bloquantes en CI)
  - [ ] `mvn test` backend — relever le delta contre la base de T0.
  - [ ] `npm run test`, `npm run lint`, `npm run build` frontend — **inchangés attendus** si la story ne touche pas le frontend ; le vérifier plutôt que le supposer.
  - [ ] `python3 scripts/check-encoding.py` — le français avec ses accents, y compris dans les YAML de configuration et les règles d'alerte.
  - [ ] Les nouveaux services du compose démarrent `healthy` et la CI reste verte (les jobs `backend`, `frontend`, `hygiene`, `supply-chain` sont requis sur `main` et `develop`).
  - [ ] ⚠️ **Le scan de vulnérabilités de la CI (Trivy CRITICAL/HIGH) couvre les images Docker.** Ajouter des images au compose peut faire rougir `supply-chain`. L'anticiper, et ne pas « régler » un scan rouge par une exemption non datée — `.trivyignore` est versionné et revu.

---

## Dev Notes

### Contraintes d'architecture (spine 2026-07-24, contrat liant)

- **NFR-P13** porté par cette story ; **NFR-P25** (invariant de ségrégation) porté par son AC4.
- **AD-16** — « Rapprochement de ségrégation : invariant contrôlé et bloquant » (ARCHITECTURE-SPINE §69). La règle d'alerte **doit être opérationnelle avant que le circuit financier ne serve** : « c'est un critère d'entrée du plan de sprint, pas une option » (SOLUTION-DESIGN §25).
- Cette story est le **critère d'entrée dur de l'Epic 4** : aucun flux d'argent réel sans métriques, logs corrélés et alerting. Neuf stories en dépendent — c'est le plus gros bloc gelé du backlog.
- Le job de rapprochement vit dans `scheduler/` (ARCHITECTURE-SPINE §36, §256), aux côtés des jobs SLA et du re-screening AML.
- Enums et codes machine **en anglais** (convention spine) ; libellés et documentation en français.

### État du dépôt (vérifié 2026-08-11)

- **Backend** : Spring Boot **3.3.5**, Java 21, Maven. `spring-boot-starter-actuator` **présent** (`pom.xml:71`), `spring-boot-starter-amqp` présent (`:67`). **Aucune** dépendance Micrometer registry, OpenTelemetry, ni encodeur de logs.
- `application.yml:193` : `management.endpoints.web.exposure.include: health,info`. **Aucune clé `logging.*`** dans le fichier. `application-prod.yml` existe séparément.
- **Aucun `MDC`, `correlationId`, `traceId` ou `X-Request-Id`** dans `backend/src/main/java`. Tout est à créer.
- Filtres existants : `security/JwtAuthFilter.java`, `security/AuthRateLimitFilter.java`. Audit : `service/AuditService.java`, `domain/AuditLog.java`, `repository/AuditLogRepository.java`.
- `scheduler/` ne contient qu'un job : `PartnerNoncePurger.java`. **Ni grand livre, ni wallet, ni rapprochement** — l'Epic 4 est entier devant nous, ce qui est précisément la difficulté de l'AC4.
- **Compose** : `infra/docker-compose.yml` — postgres, adminer, pgadmin, rabbitmq, minio, minio-init, clamav, mailpit, backend, frontend. **Ports déjà pris** : 5050, 5432, 5672, 8081, 9000, 9001, 15672 (+ 8025/1025 Mailpit, 8080 backend). 9090 et 3000 sont libres.
- **CI** : `.github/workflows/ci.yml`, jobs `hygiene`, `backend`, `frontend`, `supply-chain` — les trois premiers sont **requis** sur `main` et `develop` (protection de branche exportée et versionnée).

### Sécurité — ce que les AC ne disent pas mais que le système exige

<!-- Le workflow create-story l'impose : « une story doit laisser le système fonctionnel de
     bout en bout, pas seulement satisfaire ses AC écrites ». -->

- ⚠️ **`/actuator/prometheus` est une surface d'attaque, pas un détail de configuration.** Il expose la topologie interne, les volumes de trafic et les noms d'endpoints. Trois décisions antérieures convergent : Story 1-5 a **fermé Swagger en production**, Story 1-4 a posé TLS et les en-têtes de sécurité, et l'AC3 de la **Story 11-3** exige qu'« aucune console d'administration ne soit exposée publiquement » (NFR-P4). L'endpoint de métriques doit être joignable **par le scraper, pas par Internet** — port de management séparé et/ou règle réseau, et surtout **non publié par le reverse-proxy**. Écrire la décision ; ne pas la laisser au hasard de la configuration de 11-3.
- ⚠️ **Aucun endpoint de test n'entre en production** (interdiction posée par la Story 1-10 anti-énumération, et reconduite par la décision T0 de la 2-4). Elle s'applique en plein à l'injection de l'AC4 (voir Q3).
- ⚠️ **Les logs structurés ne doivent pas devenir une fuite.** Passer au JSON change ce qui est capturé : un log de requête naïf embarque en-têtes `Authorization`, jetons, et adresses e-mail — or l'Epic 1 a passé dix stories à fermer exactement ces oracles. Poser une règle de rédaction et **la tester**.
- Identifiants d'administration des nouveaux outils : externalisés (Story 1-2), jamais de mot de passe par défaut.

### Pièges connus (mémoire projet)

- **Vérification par mutation obligatoire** sur toute garde de sécurité : retirer, constater le rouge, restaurer, consigner (`project-context.md` §Testing Rules). Trois correctifs de l'Epic 1 étaient corrects en code et creux en preuve.
- **Octets NUL invisibles à `grep`** : dépister avec `file`, pas `iconv`. Le défaut s'est produit **deux fois** sur ce projet et rend des fichiers entiers invisibles aux greps de vérification. La garde d'encodage en CI l'attrape désormais.
- **Conflits de ports en local** : un nginx sur 8080 et un Postgres sur 5432 entrent en collision avec le compose. Choisir des ports libres et le documenter.
- **Bases de test** : ne jamais annoncer un total sans l'avoir relevé avant ET après (leçon récurrente ; le suivi de sprint a déjà porté des chiffres périmés).
- **Ne pas bumper Spring Boot** : territoire exclusif de 11-9 (précédent 11-1).

### Testing standards

- Backend : JUnit + Testcontainers, conteneur Postgres mutualisé (`PostgresTestSupport`, 18 suites) — **ne pas instancier un conteneur par suite**, le plafond de connexions a déjà été touché une fois.
- Une assertion négative est **toujours** appariée à une positive, avec des comptes exacts : un sélecteur ou une métrique inexistants rendent 0, ce qui satisfait « au plus un » par le vide. **Quatre occurrences** de ce motif sur ce projet.
- Une garde non falsifiable est signalée comme telle, pas maquillée en preuve — la Story 2.7 a retiré deux correctifs pour cette raison.

### Project Structure Notes

- `backend/pom.xml` : ajouts de dépendances d'observabilité **uniquement**, aucun bump applicatif.
- Configuration d'observabilité **regroupée** dans `application.yml` (et `application-prod.yml` pour ce qui diffère), pour que 11-9 la migre d'un seul bloc.
- Filtre de corrélation dans `backend/src/main/java/com/zlecaf/escrow/security/` ou un paquet `observability/` dédié — trancher et s'y tenir ; l'ordre dans la chaîne de filtres est load-bearing.
- Pile d'observabilité dans `infra/docker-compose.yml` ; règles d'alerte et dashboards **versionnés** sous `infra/` (provisioning déclaratif).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-11.4] (AC verbatim)
- [Source: _bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md#AD-16] (invariant, §69 ; scheduler §36/§256 ; opérabilité §293/§303)
- [Source: _bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/SOLUTION-DESIGN.md] (formule de rapprochement §25 ; fréquence par corridor §101 ; contrainte de plan de sprint §110)
- [Source: _bmad-output/implementation-artifacts/11-1-pipeline-ci-cd-gate-tests.md] (jobs CI, protection de branche, précédent « ne pas bumper les dépendances »)
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml] (contrainte 11-4 avant Epic 4 ; décision de séquencement du 2026-08-10)
- [Source: _bmad-output/implementation-artifacts/deferred-work.md] (registre — trois entrées ouvertes issues de la Story 2.7)
- Documentation Spring Boot 3.3 consultée le 2026-08-11 (via Context7) : dépendances OTLP `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`, propriétés `management.otlp.tracing.*`, `logging.pattern.correlation`, `micrometer-registry-prometheus`. Renommage 4.x confirmé : `management.opentelemetry.tracing.export.otlp.*`.

## Dev Agent Record

### Agent Model Used

claude-opus-5 (Claude Code).

### Debug Log References

- **T1 — 403 puis 404 sur `/actuator/prometheus`.** Le 404 n'était pas un défaut de configuration mais une condition d'auto-configuration non satisfaite : le rapport d'évaluation des conditions montrait `PrometheusMetricsExportAutoConfiguration` trouvant sa classe et échouant sur `@ConditionalOnEnabledMetricsExport`, `management.defaults.metrics.export.enabled` étant évalué à false. **Aucune erreur au démarrage, aucun avertissement** — c'est ce silence qui vaut d'être consigné.
- **T3 — 409 au premier montage du test de trace.** La fenêtre de versement de preuve n'ouvre qu'à partir de `FUNDS_LOCKED` (`EscrowState.allowsEvidenceMutation`, FR-8/AD-2). Découvert par un refus plutôt que contourné en écrivant l'état en base.
- **CI 2026-08-12 — job `supply-chain` rouge** (run 31568941048). Diagnostic dans le commit `8b0b73e` : dérive de CVE, aucune introduite par la story ; vérifié contre l'artefact SBOM du dernier run vert (30391134522).

### Completion Notes List

1. **Le tracing porte la corrélation, et aucun filtre n'a été écrit.** La conception initiale (T2, Dev Notes) prévoyait un filtre de corrélation à insérer dans la chaîne Spring Security, avec la question de son ordre. Micrometer Tracing alimentant lui-même le MDC, ce filtre aurait créé la seconde notion d'identifiant que la décision T0 refuse : la tâche disparaît par sa décision, elle n'est pas oubliée.
2. **Aucune migration Flyway, aucune colonne ajoutée à `AuditLog`.** La corrélation vit dans le MDC de la ligne de log — mais `AuditService` n'émettait AUCUN log, ce qui rendait l'AC1 (« écriture d'audit comprise ») intenable quel que soit le format. Une ligne de log a donc été ajoutée à l'écriture d'audit.
3. **Un défaut de T1 corrigé par T2, et il rend l'ampleur du piège mesurable** : le découpage par profil laissait la branche `test` sans appender JSON — **supprimer TOUT `logback-spring.xml` laissait la suite verte**. Le format est désormais unique, et le test interroge l'encodeur qui tourne.
4. **L'échantillonnage ne gouverne que l'EXPORT.** Affirmé en T1, éprouvé en T2 : les tests de corrélation tournent à 0 % d'échantillonnage et passent.
5. **Le SDK AWS v2 n'est pas instrumenté par Micrometer** — sans l'observation posée en T3, la trace s'arrête à la couche web et le poste le plus lent du parcours reste invisible. Le chiffrement d'enveloppe est délibérément HORS de l'observation.
6. **Écart nommé, à ne pas relire comme livré** : la famille « files du broker » de l'AC1 a été DÉCIDÉE en T1 (plugin `rabbitmq_prometheus`, l'application ne peut pas produire cette métrique) et CÂBLÉE en T4.
7. **Le rouge de la CI n'appartenait pas à cette story** et a quand même été absorbé par elle (7 CVE triées, 1 corrigée à la source). Deux entrées au registre en sont sorties, dont une qui contredit le motif d'exemption collectif écrit en 11.1.
8. **T4 a fait tourner la pile pour de vrai, et c'est ce qui a trouvé les défauts.** T1 et T3 s'étaient prouvés par des tests JVM — qui ne montent pas le compose. Monter la pile a révélé (a) que l'image du backend était périmée, donc que la cible de scraping était `down` et les logs non JSON, et (b) qu'une requête du tableau de bord sur treize rendait le vide. Aucun des deux n'était visible depuis la suite de tests.
9. **La règle « DLQ non vide » de T5 n'a rien à surveiller** : `RabbitConfig` ne déclare qu'une file, sans exchange de rebut. Constat posé en T4 et routé vers l'ouverture de T5 (voir la tâche), pas découvert à mi-parcours.

### File List

**T1 → T3** (commits `fef3b03`, `9d88c5f`, `cd6ca54`) :

| | Fichier |
|---|---|
| A | `backend/src/main/resources/logback-spring.xml` |
| A | `backend/src/test/java/com/zlecaf/escrow/observability/ObservabilityIntegrationTest.java` |
| A | `backend/src/test/java/com/zlecaf/escrow/observability/LogCorrelationIntegrationTest.java` |
| A | `backend/src/test/java/com/zlecaf/escrow/observability/StorageTracingIntegrationTest.java` |
| A | `infra/observability/prometheus.yml` |
| M | `backend/pom.xml` |
| M | `backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java` |
| M | `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` |
| M | `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` |
| M | `backend/src/main/resources/application.yml` |
| M | `backend/src/test/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorageTest.java` |
| M | `backend/src/test/resources/application.properties` |
| M | `infra/docker-compose.yml` |

**Correctif CI** (commit `8b0b73e`) : `.trivyignore-backend`, `frontend/package-lock.json`, `deferred-work.md`.

**T4** :

| | Fichier |
|---|---|
| A | `infra/observability/grafana/dashboards/escrow-observabilite.json` |
| A | `infra/observability/grafana/provisioning/datasources/datasources.yml` |
| A | `infra/observability/grafana/provisioning/dashboards/dashboards.yml` |
| A | `infra/observability/rabbitmq-enabled-plugins` |
| M | `infra/observability/prometheus.yml` |
| M | `infra/docker-compose.yml` |
| M | `infra/.env.example` |

## Change Log

| Date | Tâche | Résumé | Commit |
|---|---|---|---|
| 2026-08-11 | — | Story créée (bmad-create-story). Quatre questions ouvertes posées en tête, à trancher avant T1. | `02b9ed2` |
| 2026-08-11 | T0 | Les quatre questions tranchées par le PO, plus une cinquième décision non prévue (le tracing porte la corrélation). Constat qui change une tâche : `AuditService` n'émet aucun log. Base de tests relevée : 514. | `02b9ed2` |
| 2026-08-11 | T1 | Métriques exposées et scrapables. Trois obstacles dont deux muets (403 sécurité, 404 par condition d'auto-configuration, série HTTP qui naît du trafic). Cantonnement en trois couches réelles. Mutations : 3 gardes retirées → 3 rouges chacune. **518 tests** (+4). | `fef3b03` |
| 2026-08-12 | T2 | Logs JSON corrélés jusqu'à l'écriture d'audit. Correction d'un défaut de T1 : le découpage par profil rendait `logback-spring.xml` supprimable sans un seul rouge. Mutations : 4, dont une qui reste verte à raison (le test de fuite MDC garde le nettoyage, pas la présence). **522 tests** (+4). | `9d88c5f` |
| 2026-08-12 | T3 | Trace du contrôleur au stockage objet — le SDK AWS n'étant pas instrumenté, l'observation est posée autour du seul appel S3. Preuve par câblage réel (application entière + vrai MinIO), traceId du stockage comparé à celui de l'audit. **524 tests** (+2). | `cd6ca54` |
| 2026-08-14 | — | **Rouge CI traité : dérive, pas régression.** Vérifié contre le SBOM du dernier run vert. nanoid corrigé à la source ; 7 CVE backend exemptées et datées ; 2 entrées au registre, dont une qui contredit le motif d'exemption collectif de la 11.1. Cinq scans rejoués verts localement avec le Trivy de la CI. | `8b0b73e` |
| 2026-08-14 | — | **Consolidation** : la tenue de livres avait trois tâches de retard (fichier à `ready-for-dev`, Change Log et Dev Agent Record vides). Cases cochées en relisant les commits ; une seule laissée ouverte, celle qui l'était vraiment. | `bcc5361` |
| 2026-08-14 | T4 | **Tableau de bord provisionné, quatrième famille de l'AC1 close.** Grafana en lecture seule (verrou prouvé par écriture réelle → HTTP 400), mot de passe en `:?` (prouvé par mutation). Plugin `rabbitmq_prometheus` activé, deux cibles de scraping justifiées par une mesure (`/metrics/per-object` perd les alarmes du nœud). 13 requêtes vérifiées contre un Prometheus vivant, **1 rendait le vide** → corrigée. Deux défauts trouvés en montant la pile, dont l'image backend périmée qui laissait la cible `down` et les logs non JSON. | _ce commit_ |
