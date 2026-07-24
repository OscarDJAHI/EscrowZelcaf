# Revue « vérification réalité » — ARCHITECTURE-SPINE Escrow ZLECAf (2026-07-24)

**Relecteur :** agent indépendant (lens reality-check)
**Date de revue :** 2026-07-24 (vérifications web effectuées ce jour)
**Objet :** `_bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` — section Stack, décisions AR-P*, Deferred.

## Méthode

1. **Réalité du projet** : chaque version « existant » de la table Stack confrontée à `backend/pom.xml`, `frontend/package.json` et `infra/docker-compose.yml`.
2. **Réalité du web** (juillet 2026) : EOL/support de chaque version épinglée vérifié via recherches web et pages officielles (rabbitmq.com, endoflife.date, etc.), sources citées par finding.

## Résultat de la confrontation au code (toutes exactes)

| Affirmation du spine | Réalité projet | Verdict |
| --- | --- | --- |
| Java 21 | `pom.xml` `<java.version>21</java.version>` | Exact |
| Spring Boot 3.3.5 existant | `spring-boot-starter-parent` 3.3.5 | Exact |
| PostgreSQL 16 | `postgres:16-alpine` (compose) | Exact |
| RabbitMQ 3.13 | `rabbitmq:3.13-management-alpine` (compose) | Exact |
| MinIO `RELEASE.2025-09-07T16-13-09Z` | même tag dans compose | Exact |
| Apache Tika 3.3.1 | `tika-core` 3.3.1 (version explicite, commentée) | Exact |
| Vue 3.5 / Pinia 2.3 / Vite 6 | `^3.5.13` / `^2.3.0` / `^6.0.7` | Exact |
| Vitest livré (Story 4.1) | `vitest ^4.1.10` + `fake-indexeddb`, script `test` | Exact |
| Flyway via BOM Boot (V1..V5) | `flyway-core` sans version (BOM) ; migrations V1..V5 présentes | Exact |

Aucune version « existant » affirmée de mémoire ne diverge du code. Le brownfield est fidèle.

## Findings

### CRITIQUE — F1 : RabbitMQ 3.13 est EOL depuis un an, épinglé sans mention ni exigence de migration

Le spine épingle « RabbitMQ 3.13 » dans la Stack et les diagrammes (`rabbitmq 3.13`) sans aucune note de support — contrairement à Spring Boot, où l'EOL est signalé et une migration exigée. Or :

- Le support communautaire de la série 3.13 a pris fin le **30 septembre 2024** ; toute la ligne 3.x est EOL open source depuis le **31 juillet 2025**. Les correctifs (y compris sécurité) ne sont fournis qu'aux détenteurs d'une licence commerciale Broadcom/VMware.
- Les séries supportées en juillet 2026 sont **4.2** (LTS, support communautaire jusqu'au 31/07/2026 → prolongé côté commercial) et **4.3** (dernière : 4.3.4 du 23/07/2026, support communautaire jusqu'au 30/11/2026).

Lancer en production un broker qui ne recevra plus jamais de correctif de sécurité communautaire contredit la posture du reste du spine (NFR sécurité, AD-22 s'appuie sur ce broker pour l'outbox). **Recommandation :** traiter RabbitMQ comme Spring Boot — exigence de lancement « migration 4.2 LTS ou 4.3 », story dans l'Epic 11 (le client Spring AMQP est compatible 4.x ; effort attendu faible, mais à vérifier au spike).

Sources : [RabbitMQ Release Information](https://www.rabbitmq.com/release-information), [endoflife.date/rabbitmq](https://endoflife.date/rabbitmq), [Broadcom — RabbitMQ Support Lifecycle](https://knowledge.broadcom.com/external/article/440401/rabbitmq-support-lifecycle-long-term-su.html), [ksolves — RabbitMQ 3.x EOL](https://www.ksolves.com/blog/big-data/rabbitmq-3-x-end-of-life)

### HAUTE — F2 : le pin MinIO est un binaire orphelin d'un projet archivé ; « déféré » n'est pas un statut tenable pour un lancement production

Le spine **a bien vérifié la réalité** : il note « MinIO OSS archivé — hérité AD-6 » et défère le backend objet définitif. Mais la conséquence n'est pas tirée jusqu'au bout :

- MinIO Inc. a cessé de publier images Docker et binaires communautaires en **octobre 2025**, mis le dépôt en maintenance en **décembre 2025**, puis l'a **archivé (lecture seule) le 25 avril 2026**. Le tag épinglé `RELEASE.2025-09-07T16-13-09Z` est l'une des dernières builds communautaires : il ne recevra **jamais** de correctif de sécurité.
- La console d'administration avait déjà été amputée des fonctions de gestion (utilisateurs/politiques) dans les dernières versions communautaires.

Pour un POC c'était acceptable ; pour la plateforme production (preuves WORM AD-25, rétention ZLECAf 5 ans), le choix du backend objet est une **exigence de lancement**, pas un « Deferred » sans story porteuse. **Recommandation :** symétrie avec la migration Spring Boot — story Epic 11 : soit S3/stockage objet managé (le port AD-6 rend le swap trivial), soit un fork maintenu (ex. écosystème post-MinIO), décision motivée et datée.

Sources : [Blocks & Files — MinIO community edition](https://blocksandfiles.com/2025/06/19/minio-removes-management-features-from-basic-community-edition-object-storage-code/), [It's FOSS — MinIO moves away from open source](https://itsfoss.com/news/minio-moves-away-from-open-source/), [minio/minio releases (archivé)](https://github.com/minio/minio/releases), [Storm Developments — MinIO archived, what still runs in 2026](https://stormdevelopments.ca/blog/minio-s-community-edition-is-archived-what-still-runs-in-2026/)

### MOYENNE — F3 : frontend une génération majeure derrière (Vite 6, Pinia 2.3), ratifié sans plan de rattrapage

« Vue / Pinia / Vite — 3.5 / 2.3 / 6 (existant) » est exact côté code, mais le spine ne dit pas que :

- **Vite 7** est sorti en juin 2025 (Vite 6 n'est plus la ligne active ; Vitest 4.1 — celui du repo — vise déjà nativement Vite 8) ;
- **Pinia 3** est la ligne courante (Pinia 2.x n'est plus la cible des évolutions) ;
- Vue 3.5 reste supporté (3.5.31 en mars 2026, 3.6 en beta) — pas de problème ici.

Ratifier l'existant est légitime en brownfield, mais l'asymétrie avec le backend (où l'obsolescence Spring Boot déclenche une exigence de lancement) montre que le frontend n'a pas subi la même vérification de support. **Recommandation :** ajouter au Deferred une ligne « rattrapage Vite 7+/Pinia 3 » (effort faible, breaking changes limités) ou motiver explicitement le gel.

Sources : [Vite 7.0 announcement](https://vite.dev/blog/announcing-vite7), [Vue School — Vue 2025 in review](https://vueschool.io/articles/news/vue-js-2025-in-review-and-a-peek-into-2026/)

### BASSE — F4 : Apache Tika 3.3.1 — un patch de retard, ligne 4.x imminente

La dernière stable est **3.3.2** ; Tika **4.0.0-beta-1** est publié et la roadmap officielle prévoit la fin du support 3.x ~6 mois après la GA 4.x. Pour une brique d'analyse de contenu non fiable (uploads), rester sur le dernier patch est une hygiène de sécurité. **Recommandation :** bump 3.3.2 immédiat (changement trivial, `pom.xml` a déjà la version explicite commentée) ; surveiller Tika 4 dans la story de migration Boot 4.1.

Sources : [Apache Tika — Download](https://tika.apache.org/download.html), [Tika Roadmap 2.x/3.x/4.x](https://cwiki.apache.org/confluence/display/TIKA/Tika+Roadmap+--+2.x,+3.x+and+Beyond)

### BASSE — F5 : PostgreSQL 16 — supporté, mais N-2 ; à motiver plutôt qu'affirmer

PostgreSQL 16 est supporté jusqu'au **9 novembre 2028** : aucun risque pour le lancement, le choix est défendable. Mais la version courante est **18** (sortie 25/09/2025, 18.4 en mai 2026) et le spine épingle « 16 » sans note de vérification ni d'horizon. **Recommandation :** annoter « PG16 — support jusqu'au 2028-11, vérifié 2026-07 » ; envisager 17/18 pour l'environnement de production neuf (AR-P5) tant qu'aucune donnée n'existe — le coût d'un démarrage direct en 17/18 est quasi nul aujourd'hui et une majeure de marge en plus. Attention connexe : la version Flyway issue du BOM Boot 3.3.5 (Flyway 10.x) ne certifie pas PG18 ; la migration Boot 4.1 (Flyway 11+) lèvera ce point.

Sources : [endoflife.date/postgresql](https://endoflife.date/postgresql), [HeroDevs — PostgreSQL EOL dates](https://www.herodevs.com/blog-posts/postgresql-eol-dates-every-versions-release-end-of-life-timeline)

### INFO — F6 : la décision Spring Boot est correctement vérifiée ; périmètre de migration à élargir d'un cran

La seule affirmation du spine étiquetée « vérifié web 2026-07 » est **exacte** : la ligne 3.x est EOL open source depuis le **30/06/2026** (fin de 3.5) — et 3.3.5, la version du repo, l'était déjà avant. La cible **4.1.x est la bonne** : 4.0 (20/11/2025) atteint son EOL OSS le 31/12/2026, tandis que 4.1 (10/06/2026, Spring Framework 7) est supportée jusqu'au **31/07/2027**. Deux dépendances hors-BOM du `pom.xml` devront entrer dans la portée de la story de migration : `springdoc-openapi 2.6.0` (ligne 3.x requise pour Boot 4) et `hypersistence-utils-hibernate-63` (artefact lié à Hibernate 6.3 ; Boot 4 embarque Hibernate 7). Vitest/Playwright : pas de version épinglée dans le spine (cibles), rien à contester.

Sources : [HeroDevs — Spring Boot versions & EOL (juillet 2026)](https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026), [eosl.date — Spring Boot](https://eosl.date/eol/product/spring-boot/), [isitpatched — Spring Boot EOL](https://www.isitpatched.com/eol/spring-boot)

## Verdict

Le spine est **fidèle à la réalité du code** (9/9 versions « existant » exactes) et sa décision la plus structurante (Spring Boot 4.1.x) est **vérifiée et juste**. En revanche, deux composants d'infrastructure engagés — RabbitMQ 3.13 (EOL depuis un an) et le pin MinIO (projet archivé, binaire sans correctifs) — reprennent des versions POC **sans que leur support ait été re-vérifié sous l'angle production**, alors que le pivot du 2026-07-18 exigeait précisément ce re-triage. Les deux se corrigent par le même mécanisme déjà utilisé pour Spring Boot : une exigence de lancement portée par une story Epic 11.
