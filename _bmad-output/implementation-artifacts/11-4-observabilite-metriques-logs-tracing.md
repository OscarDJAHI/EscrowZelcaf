# Story 11.4: Observabilité — métriques, logs corrélés, tracing et alerting

Status: ready-for-dev

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

## ⚠️ Questions ouvertes — À TRANCHER AVANT T1

Ces quatre points commandent le périmètre livrable. Les trancher en ouverture, comme la Story 2.7 l'a fait pour ses trois questions, plutôt que de les découvrir à mi-parcours.

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

- [ ] **T0 — Lire avant d'écrire, et faire trancher Q1→Q4** (préalable bloquant)
  - [ ] Faire trancher les quatre questions ci-dessus par le PO et consigner chaque décision dans ce fichier avant d'écrire une ligne.
  - [ ] Lire `backend/src/main/resources/application.yml` (bloc `management:` ligne 193, exposition limitée à `health,info`) et `application-prod.yml`.
  - [ ] Lire `security/JwtAuthFilter.java` et `security/AuthRateLimitFilter.java` — la chaîne de filtres existante détermine **où** s'insère le filtre de corrélation, et son ordre est load-bearing (voir Dev Notes §Sécurité).
  - [ ] Lire `service/AuditService.java` et `domain/AuditLog.java` : l'AC1 exige que l'identifiant de corrélation atteigne **l'écriture d'audit**. Décider s'il est porté par le MDC (log) seulement, ou aussi persisté sur `AuditLog` — ce second cas est un changement de schéma (migration Flyway) et doit être dit.
  - [ ] Relever la base de tests backend et frontend avant toute modification (chiffre exact, pour que les deltas soient vérifiables).

- [ ] **T1 — Métriques exposées et scrapées** (AC: 1)
  - [ ] Ajouter `micrometer-registry-prometheus` au `backend/pom.xml`. L'actuator est **déjà présent** (`spring-boot-starter-actuator`, pom ligne 71) — ne pas le redéclarer.
  - [ ] Exposer `prometheus` dans `management.endpoints.web.exposure.include` **sans élargir le reste** : l'exposition actuelle est `health,info` et cette parcimonie est un acquis de sécurité, pas un oubli.
  - [ ] Vérifier que les quatre familles de l'AC sont bien présentes dans la sortie : JVM, HTTP (`http.server.requests`), pool Hikari (`hikaricp.*`), files du broker. ⚠️ **Les métriques RabbitMQ ne sortent pas toutes seules** — `spring-boot-starter-amqp` est présent (pom ligne 67) mais la profondeur de file côté broker n'est pas une métrique client. Décider et écrire : métriques client Spring AMQP, ou exporteur RabbitMQ, ou plugin `rabbitmq_prometheus`.
  - [ ] Ajouter Prometheus au compose et le faire scraper le backend. **Ports** : 9090 est libre ; 9000/9001 sont pris par MinIO, 5432/8081/5050/5672/15672 aussi (voir Dev Notes).

- [ ] **T2 — Logs structurés JSON et identifiant de corrélation** (AC: 1)
  - [ ] ⚠️ **Il n'y a AUCUNE configuration de logging aujourd'hui** : `application.yml` ne contient pas une seule clé `logging.*`, et le backend écrit donc au motif console par défaut de Logback. Tout est à poser.
  - [ ] Boot 3.3.5 n'a pas le logging structuré natif (arrivé en 3.4) → encodeur JSON explicite. Regrouper la configuration pour que 11-9 la migre d'un bloc.
  - [ ] Identifiant de corrélation propagé : Boot alimente `traceId`/`spanId` dans le MDC dès que Micrometer Tracing est présent (T3), et `logging.pattern.correlation` en contrôle le rendu. **Décider si le tracing est le porteur de la corrélation** (une seule notion) ou si un `X-Request-Id` distinct est introduit (deux notions à garder en accord — coût récurrent).
  - [ ] ⚠️ **Aucun `MDC`, `correlationId`, `traceId` ni `X-Request-Id` n'existe dans tout `backend/src/main/java`** (vérifié 2026-08-11). Il n'y a rien à réutiliser et rien à casser, mais **rien non plus pour rattraper un oubli**.
  - [ ] Faire atteindre l'identifiant à l'**écriture d'audit** (exigence explicite de l'AC1), selon la décision de T0.
  - [ ] Le MDC doit être **nettoyé en fin de requête**, y compris sur le chemin d'erreur : un pool de threads réutilise ses threads, et un MDC non vidé attribue les lignes de la requête suivante à la trace précédente. C'est un défaut silencieux — les logs restent lisibles, ils sont simplement **faux**.

- [ ] **T3 — Trace de bout en bout du parcours chaud** (AC: 2)
  - [ ] Dépendances Boot 3.3.5 : `io.micrometer:micrometer-tracing-bridge-otel` + `io.opentelemetry:opentelemetry-exporter-otlp`. Propriétés sous `management.otlp.tracing.*` (**renommées en 4.x**, voir Q4).
  - [ ] Collecteur + interface de consultation dans le compose.
  - [ ] Le parcours chaud nommé par l'AC est le **versement de preuve, du contrôleur au stockage objet** : `web/EvidenceController.java` → `service/*` → MinIO. Vérifier que la trace couvre bien l'appel au stockage objet et pas seulement la couche web.
  - [ ] ⚠️ **Échantillonnage** : le défaut de Boot est 10 %. Une trace « consultable » pour l'AC suppose de le savoir — soit 100 % en développement, soit une requête ciblée. Écrire le choix, ne pas le subir.
  - [ ] Corrélation trace ↔ logs par le même identifiant : c'est ce qui rend l'AC2 vérifiable, et c'est l'objet du test de T7.

- [ ] **T4 — Dashboard** (AC: 1)
  - [ ] « Restituées dans un dashboard » : dashboard **versionné dans le dépôt** (provisioning déclaratif), pas construit à la main dans une interface. Un tableau de bord qui ne vit que dans un volume Docker n'est pas livrable et disparaît au premier `docker compose down -v`.
  - [ ] ⚠️ Identifiants d'administration de l'outil : **aucun secret en clair** (Story 1-2, externalisation des secrets). Variables d'environnement, et pas de mot de passe par défaut laissé en place.

- [ ] **T5 — Alerting : canal et règles minimales** (AC: 3)
  - [ ] Canal selon Q2, avec écrit noir sur blanc ce qu'il n'est pas (pas une décision AR-P6).
  - [ ] Les trois règles minimales de l'AC : **taux de 5xx**, **DLQ non vide**, **saturation disque/pool**. Chacune doit reposer sur une métrique réellement exposée par T1 — vérifier plutôt que supposer, une règle sur une série inexistante ne se déclenche jamais et **ne rougit jamais non plus**.
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

### Debug Log References

### Completion Notes List

### File List

## Change Log

| Date | Tâche | Résumé | Commit |
|---|---|---|---|
| 2026-08-11 | — | Story créée (bmad-create-story). Quatre questions ouvertes posées en tête, à trancher avant T1. | _à venir_ |
