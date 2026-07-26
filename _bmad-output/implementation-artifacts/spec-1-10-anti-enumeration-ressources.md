---
title: 'Story 1.10 — Anti-énumération des ressources'
type: 'feature'
created: '2026-07-26'
status: 'done'
baseline_revision: 'd74709ddfe839a96d8f2f2ebbe606068fdb78d6a'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Sur toute la surface escrow, une ressource inexistante répond `404 / TRANSACTION_NOT_FOUND` tandis qu'une ressource appartenant à un tiers répond `403 / NOT_A_PARTY` : statut, `code` et `message` diffèrent, ce qui donne à tout porteur d'un JWT (ou d'une clé HMAC partenaire) un oracle d'énumération parfait de l'espace des transactions — risque consigné au ledger depuis le 2026-07-16 et explicitement conservé « pour un durcissement prod ».

**Approach:** Faire converger les deux cas vers **une seule réponse, produite par une seule fabrique** : `404` + `TRANSACTION_NOT_FOUND` + message constant sans identifiant. Le code `NOT_A_PARTY` est **retiré de l'enum** pour qu'aucune ressource future ne puisse rouvrir l'oracle par simple copier-coller, et la fabrique devient la convention que les douze stories aval (KYB, litiges, tickets, back-office, wallets) réutiliseront.

## Boundaries & Constraints

**Always:**
- Une réponse ne doit jamais révéler l'existence d'une ressource que l'appelant **n'est pas autorisé à connaître**. Corollaire : quand l'appelant *est* déjà autorisé à la connaître (l'autre partie d'une transaction dont il est partie, dont il voit déjà les pièces via `GET .../evidence`), un refus honnête et distinct reste permis — et doit être documenté et testé comme tel.
- Indistinguabilité = **statut + `code` + `message` + en-têtes identiques**, pas seulement le statut. Le `code` de l'enveloppe est un oracle au même titre que le statut (AD-10).
- Une seule fabrique par famille de ressource (`ApiExceptions.transactionNotFound()`, `ApiExceptions.evidenceNotFound()`) : deux littéraux indépendants dériveraient et casseraient la propriété en silence — c'est exactement le défaut déjà consigné au ledger pour le message d'auth partenaire.
- Les gardes restent centralisées dans `TransactionAccess` (AD-3) ; aucun contrôle d'appartenance recodé par endpoint.
- Tout changement de `code` est un acte coordonné backend + `ErrorCodeContractTest` + miroir frontend, dans le même commit (AD-10).

**Block If:**
- Le retrait de `NOT_A_PARTY` s'avère exiger un changement **fonctionnel** côté frontend (un chemin de code qui se branche sur ce code au-delà d'un libellé affiché) : ce serait une rupture du contrat client posé hier par la Story 1.9, à arbitrer, pas à absorber.
- Un test existant prouve qu'une écriture (audit, ligne, transition) **doit** accompagner un refus d'appartenance : le passage en 404 changerait alors un invariant métier et non seulement une réponse.
- L'oracle d'e-mail de `POST /api/v1/escrow` ne bloque pas cette story : il est routé au ledger et porté par les stories 2.4/2.5/2.6, qui en font une décision produit explicite.

**Never:**
- Ne pas créer d'entité, de table, d'endpoint ni de garde **wallet** : `WalletService.getOrCreate` relève d'AD-13/Epic 4 et n'existe pas.
- Ne pas toucher au `403` nu de Spring Security (`Http403ForbiddenEntryPoint`, sans enveloppe ni `code`) : la Story 1.9 en a fait le discriminant « session expirée » côté client. Un `403` **nu** déconnecte, un `404` **codé** ne déconnecte pas — c'est la propriété à préserver.
- Ne pas prétendre fermer le canal **temporel** : les deux branches ne font pas le même nombre de requêtes SQL. Hors périmètre, consigné au ledger.
- Ne pas uniformiser `WEAK_PASSWORD` (endpoint public, énumération des règles volontaire et documentée) ni les 409 de la machine à états.
- Ne pas traiter l'inondation de la table d'audit par les détections malware (routée ici par le ledger 1.8) : c'est de l'épuisement de ressource, pas de l'énumération. → re-routée avec motif écrit.

## I/O & Edge-Case Matrix

| Scénario | Entrée / État | Sortie attendue | Gestion d'erreur |
|---|---|---|---|
| Transaction inexistante | JWT valide, `GET /api/v1/escrow/999999` | `404`, `code=TRANSACTION_NOT_FOUND`, `message="Transaction not found"` | Aucun audit, aucune écriture |
| Transaction d'un tiers | JWT de C, `GET /api/v1/escrow/{txAB}` | **Octet pour octet identique** à la ligne précédente (hors `timestamp`) | Aucun audit, aucune écriture |
| Idem sur toute la surface | `POST /{id}/event`, `POST /{id}/dispute`, `POST /{id}/evidence`, `GET /{id}/evidence`, `GET /{id}/evidence/{eid}/download`, `POST /{id}/evidence/{eid}/withdraw` | Même paire indistinguable sur chaque endpoint | idem |
| Canal partenaire HMAC | Signature valide, société non partie vs `txId` inconnu | Même paire indistinguable (`404 / TRANSACTION_NOT_FOUND`) | Nonce consommé selon la règle 3.2 inchangée |
| Pièce inconnue vs pièce d'une autre transaction | Appelant partie, `GET /{txA}/evidence/{eidDeTxC}/download` | `404`, `code=RESOURCE_NOT_FOUND`, `message="Evidence not found"` | Requête scellée `findByIdAndTransactionId` inchangée |
| Binaire absent du stockage | Ligne présente, objet S3 manquant | Même `404 / RESOURCE_NOT_FOUND / "Evidence not found"` que ci-dessus | `log.warn` côté serveur avec la clé de stockage ; rien côté client |
| Pièce de l'autre partie, même transaction | Appelant partie, `POST /{id}/evidence/{eid}/withdraw` | `403`, `code=FORBIDDEN` — **exception assumée** : l'appelant voit déjà cette pièce dans `GET .../evidence` | Statut de la pièce inchangé, aucun audit |
| Sous-ressource inexistante | `GET /api/v1/escrow/1/inexistant` | `404`, `code=RESOURCE_NOT_FOUND` (aujourd'hui : `500 / INTERNAL_ERROR`, classé TRANSIENT) | Enveloppe standard |
| Identifiant non numérique | `GET /api/v1/escrow/abc` | `400`, `code=INVALID_REQUEST` (aujourd'hui : `500 / INTERNAL_ERROR`) | Enveloppe standard |
| Méthode non supportée | `DELETE /api/v1/escrow/1` | `405`, `code=INVALID_REQUEST` (aujourd'hui : `500`) | Enveloppe standard |
| Sans jeton / jeton révoqué | Aucun `Authorization` | `403` **nu**, sans enveloppe — **inchangé** | Contrat 1.9 préservé |

</intent-contract>

## Code Map

- `backend/.../web/ApiExceptions.java:39-42` -- `NotFoundException` et ses deux constructeurs ; `:54-57` `ForbiddenException`. C'est ici qu'atterrissent les fabriques.
- `backend/.../domain/ErrorCode.java:74-79` -- `NOT_A_PARTY` (à retirer) et `TRANSACTION_NOT_FOUND` ; `:144` `RESOURCE_NOT_FOUND` ; `:87` `AUTH_FAILED` dont le javadoc est le **précédent anti-oracle à imiter mot pour mot**.
- `backend/.../service/TransactionAccess.java:37-48` -- `resolveRole`, unique site de jet `NOT_A_PARTY` côté utilisateur ; `:59-66` `requireCompanyParticipant`, même jet côté partenaire ; `:14-19` le javadoc « deux endpoints avec des règles divergentes seraient un défaut ».
- `backend/.../service/EscrowService.java:108-110`, `:164-166`, `:225-227` -- les trois `orElseThrow` porteurs du message avec identifiant.
- `backend/.../service/EvidenceService.java:126-128`, `:159-161`, `:381-383`, `:429-431`, `:506-508` -- les cinq autres ; `:433-434` et `:513-514` (`"Evidence N not found"`), `:445-447` (`"Evidence binary not found for N"` — divergence D3), `:517-520` (garde « pièce à soi », exception assumée).
- `backend/.../web/GlobalExceptionHandler.java:60-63` (branche 404), `:75-78` (branche 403), `:161-165` (filet `Exception` qui avale aujourd'hui les 404/405/400 natifs de Spring), `:156-159` (portée déclarée, à ne pas contredire).
- `backend/src/test/.../domain/ErrorCodeContractTest.java:28-49` (`AD10_PERMANENT`), `:76` (`hasSize(11)`), `:110-122` (`partnerAuthHasExactlyOneCode`, gabarit de l'assertion anti-oracle à dupliquer).
- `backend/src/test/.../security/PasswordAndRevocationIntegrationTest.java:33-49` (gabarit `@SpringBootTest`+Testcontainers), `:222-229` (**seul** helper JWT réel du dépôt), `:125` (appel authentifié).
- `backend/src/test/.../service/EvidenceServiceTest.java:70-92` (gabarit `@DataJpaTest`+Postgres), `:220-236` (fixtures), `:292`, `:566`, `:670`, `:886` (non-partie → `ForbiddenException`, à retourner), `:576`, `:642`, `:654`, `:680`, `:696`, `:844` (404 déjà attendus). **Ce fichier n'est pas en UTF-8 valide : utiliser `grep -a`.**
- `backend/src/test/.../service/EscrowDisputeServiceTest.java:592-607` ; `service/TransactionAccessTest.java:78,85,95,105` ; `web/GlobalExceptionHandlerTest.java:131-136` ; `web/EscrowControllerDisputeTest.java:131` ; `web/EvidenceControllerWithdrawTest.java:75` ; `web/PartnerEvidenceControllerTest.java:96` -- les assertions qui figent l'ancien 403.
- `frontend/src/utils/replayFailure.js:97` (message `NOT_A_PARTY`), `:160` ; `frontend/src/utils/frozenEntry.js:41` (`NO_LINK_CODES` contient déjà `TRANSACTION_NOT_FOUND`) -- **aucun changement fonctionnel requis**, voir *Design Notes*.
- `_bmad-output/implementation-artifacts/deferred-work.md:42-44` -- l'entrée fondatrice de cette story ; `:133` (double message anti-oracle partenaire), `:282` (item malware routé ici par erreur).

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/zlecaf/escrow/web/ApiExceptions.java` -- ajouter deux fabriques statiques `transactionNotFound()` → `NotFoundException(TRANSACTION_NOT_FOUND, "Transaction not found")` et `evidenceNotFound()` → `NotFoundException(RESOURCE_NOT_FOUND, "Evidence not found")`, **sans identifiant dans le message** ; javadoc de classe énonçant la convention NFR-P9 pour toute ressource protégée future (wallet, ticket, fil de litige, dossier KYB) -- l'identifiant est déjà dans l'URL, l'en retirer rend la comparaison des deux réponses littérale au lieu d'être « à identifiant près », et une fabrique unique est ce qui empêche les deux littéraux de diverger comme l'a fait le message d'auth partenaire (`deferred-work.md:133`).
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java` -- **supprimer** `NOT_A_PARTY` ; enrichir le javadoc de `TRANSACTION_NOT_FOUND` sur le modèle exact d'`AUTH_FAILED` (« délibérément opaque : inexistante ET non accessible partagent ce code ») -- laisser une constante que plus rien n'émet est le footgun qui rouvre l'oracle à la prochaine ressource protégée ; la retirer rend l'erreur non compilable.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` -- `resolveRole` et `requireCompanyParticipant` lèvent `ApiExceptions.transactionNotFound()` au lieu de `ForbiddenException(NOT_A_PARTY, …)` ; javadoc réécrit (le `@throws` devient `NotFoundException`, avec la raison : ce n'est pas un mensonge, c'est le refus de confirmer l'existence) -- ces deux méthodes sont les **seuls** sites de jet, donc le seul endroit à corriger pour fermer l'oracle sur les huit endpoints.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/EscrowService.java` -- remplacer les trois `orElseThrow(() -> new NotFoundException(TRANSACTION_NOT_FOUND, "Transaction " + txId + " not found"))` par `orElseThrow(ApiExceptions::transactionNotFound)` -- l'égalité des deux réponses doit être une propriété de construction, pas une coïncidence entre deux littéraux.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- même remplacement sur les cinq `orElseThrow` de transaction ; les deux `new NotFoundException("Evidence N not found")` et le `"Evidence binary not found for N"` deviennent `ApiExceptions.evidenceNotFound()`, avec un `log.warn` portant la clé de stockage sur le chemin binaire-absent (le logger `log` existe déjà, `EvidenceService.java:59`) ; commentaire explicite au-dessus de la garde `:517-520` disant pourquoi ce 403 **reste** un 403 -- le message « binary not found » prouvait que la ligne existe et appartient bien à cette transaction (divergence D3) ; l'incident reste diagnosticable côté serveur, il cesse seulement d'être publié.
- [x] `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java` -- ajouter les branches `NoResourceFoundException` → 404 `RESOURCE_NOT_FOUND`, `HttpRequestMethodNotSupportedException` → 405 `INVALID_REQUEST`, `MethodArgumentTypeMismatchException` → 400 `INVALID_REQUEST`, `HttpMediaTypeNotSupportedException` → 415 `INVALID_REQUEST` ; messages fixes, aucun détail interne -- sur la surface qu'on prétend uniformiser, une sonde qui répond `500` est distinguable ; pire, `INTERNAL_ERROR` est **TRANSIENT**, donc la file offline rejouerait une URL fautive indéfiniment (AD-10). Aucune nouvelle valeur d'enum : les deux codes existent déjà et sont PERMANENT.
- [x] `backend/src/test/java/com/zlecaf/escrow/domain/ErrorCodeContractTest.java` -- retirer `NOT_A_PARTY` d'`AD10_PERMANENT`, passer `hasSize(11)` à `hasSize(10)` et corriger le `@DisplayName` ; ajouter un test `membershipRefusalHasNoDedicatedCode` calqué sur `partnerAuthHasExactlyOneCode:110-122` asservissant qu'aucun nom de code ne contient `NOT_A_PARTY` ; commenter la dissymétrie volontaire avec le miroir frontend -- ce test existe précisément pour rendre ce changement délibéré ; l'assertion négative est ce qui empêche la réintroduction du code sous le même nom.
- [x] `backend/src/test/java/com/zlecaf/escrow/security/AntiEnumerationIntegrationTest.java` -- **à créer** : `@SpringBootTest` + `@AutoConfigureMockMvc` + Testcontainers (gabarit `PasswordAndRevocationIntegrationTest:33-49`, helper JWT `:222-229`). Enregistrer A, B et C ; A crée une transaction avec B ; pour **chacun** des six endpoints utilisateur, capturer la réponse de C sur la transaction A↔B et celle de C sur un id inexistant, puis asserter l'égalité **du statut, du corps entier `timestamp` normalisé, et des en-têtes hors `Date`/`Content-Length`** ; ajouter la paire du canal partenaire HMAC (société non partie vs id inconnu) et la paire au niveau pièce (pièce inconnue vs pièce d'une autre transaction) -- c'est l'AC de la story, et le dépôt n'a **aucun** test anti-IDOR au niveau HTTP avec un vrai JWT : tout se joue aujourd'hui au niveau service, où le statut n'existe pas.
- [x] `backend/src/test/java/com/zlecaf/escrow/service/TransactionAccessTest.java`, `service/EvidenceServiceTest.java`, `service/EscrowDisputeServiceTest.java` -- retourner les attentes non-partie de `ForbiddenException` vers `NotFoundException` (`TransactionAccessTest:78,85,95,105` ; `EvidenceServiceTest:292,566,670,886` ; `EscrowDisputeServiceTest:601`) **sans affaiblir aucune assertion voisine** (« aucun audit », « aucune ligne », « statut inchangé » restent) ; ajouter dans `EvidenceServiceTest` un test asservissant que la garde « pièce à soi » lève toujours `ForbiddenException` **et** que la même pièce est bien visible par `list()` pour cet appelant -- c'est ce second test qui transforme l'exception assumée en propriété prouvée plutôt qu'en oubli.
- [x] `backend/src/test/java/com/zlecaf/escrow/web/GlobalExceptionHandlerTest.java`, `web/EscrowControllerDisputeTest.java`, `web/EvidenceControllerWithdrawTest.java`, `web/PartnerEvidenceControllerTest.java` -- `GlobalExceptionHandlerTest:131-136` : remplacer le cas `ForbiddenException(NOT_A_PARTY)` par `ForbiddenException` par défaut (403/`FORBIDDEN`, la garde « pièce à soi ») et ajouter les quatre nouvelles branches natives ; `EscrowControllerDisputeTest:131`, `PartnerEvidenceControllerTest:96` : `isForbidden()` → `isNotFound()` ; `EvidenceControllerWithdrawTest:75` : **scinder** le cas fourre-tout « not the owner / not a party » en deux tests distincts (non-partie → 404, non-propriétaire → 403) -- un test qui couvre deux causes sous une seule attente ne verra jamais l'une des deux diverger.
- [x] `frontend/src/utils/replayFailure.js` -- ajouter un commentaire au-dessus de l'entrée `NOT_A_PARTY:97` : code **hérité**, plus jamais émis par le backend depuis la Story 1.10, conservé parce qu'il peut rester gravé dans une entrée gelée d'IndexedDB antérieure au déploiement ; renvoi croisé vers `frozenEntry.js:41` -- aucun changement fonctionnel n'est nécessaire (`TRANSACTION_NOT_FOUND` est déjà PERMANENT et déjà dans `NO_LINK_CODES`), mais sans ce commentaire un nettoyage futur supprimerait la seule prise en charge des entrées gelées d'avant la bascule.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- (a) marquer l'entrée `:42-44` `RÉSOLU 2026-07-26 (Story 1.10)` en une ligne, sans toucher au texte d'origine ; (b) annoter `:282` d'une re-route motivée (épuisement de ressource ≠ énumération : relève du limiteur de la Story 1.3 ou d'une story d'opérabilité) ; (c) appendre une section datée avec quatre entrées nouvelles : **preuve wallet due** par la story wallet d'Epic 4, **canal temporel** non couvert (le chemin « tiers » du canal partenaire fait jusqu'à deux `users.findById` de plus que le chemin « inexistant »), **oracle d'e-mail** sur `POST /api/v1/escrow` (→ stories 2.4/2.5/2.6), **trou normatif du spine** : aucun AD ne dit quelle garde protège un wallet (AD-3 parle de « partie à une transaction », AD-13 scope le wallet à company+currency) -- le ledger est append-only et ces quatre points sont réels, bornés, et hors du périmètre livrable ici.
- [x] `_bmad-output/project-context.md` -- préciser la règle existante « 403/404 uniformisés (anti-énumération) » en nommant la fabrique et la règle d'« autorisation à connaître l'existence », avec l'exception documentée -- c'est le fichier que lit tout agent avant d'écrire du code, et la valeur de cette story est d'être reprise par une douzaine de stories aval sans être réimplémentée.

**Acceptance Criteria:**
- Given un utilisateur authentifié C qui n'est partie à rien, when il sonde n'importe lequel des six endpoints escrow/preuve sur une transaction A↔B existante puis sur un identifiant inexistant, then les deux réponses sont **identiques octet pour octet** hors `timestamp` — statut, `code`, `message` et en-têtes — et aucune écriture ni audit n'est produit dans les deux cas.
- Given le canal partenaire HMAC signé, when une société non partie et un identifiant de transaction inconnu sont sondés, then les deux réponses sont identiques selon le même critère — l'anti-énumération ne s'arrête pas au canal humain (angle mort SEC-L2).
- Given un appelant **partie** à la transaction, when il demande le retrait d'une pièce de l'autre partie, then il reçoit `403 / FORBIDDEN` et non un 404 — exception assumée et prouvée par un test qui vérifie que cette même pièce lui est déjà visible via `GET /{id}/evidence`, donc que le refus honnête ne révèle rien.
- Given le contrat client posé par la Story 1.9, when un non-partie reçoit désormais un 404 codé, then aucune déconnexion n'est déclenchée (seul un 401/403 **nu** l'est), l'entrée de file éventuelle est gelée comme permanente et aucun lien vers la transaction n'est rendu — les suites frontend passent **sans modification d'aucune assertion**.
- Given la suite backend existante, when elle est rejouée, then elle est verte, et les seules assertions modifiées sont celles qui figeaient explicitement l'ancien couple `403 / NOT_A_PARTY` — énumérées dans les tâches — aucune assertion voisine (absence d'audit, absence d'écriture, statut inchangé) n'étant affaiblie ni supprimée.
- Given la clause « et wallets » de l'AC d'epic, when la story se termine, then aucune ressource wallet n'a été créée, la fabrique réutilisable et sa convention sont documentées dans `project-context.md`, et la dette de preuve est nommément portée au ledger à la charge de la story wallet d'Epic 4 — voir *Design Notes*.

## Spec Change Log

## Review Triage Log

### 2026-07-26 — Review pass

- intent_gap: 0
- bad_spec: 0
- patch: 15: (high 1, medium 6, low 8)
- defer: 7: (high 1, medium 2, low 4)
- reject: 4
- addressed_findings:
  - `[high]` `[patch]` **L'énumération des rejets natifs de Spring était incomplète, et la javadoc la présentait comme complète.** Vérifié plutôt que supposé : `stores/escrow.js:75,125,177` met bien en file hors ligne `POST /escrow`, `POST /{id}/event` (corps JSON) et `POST /{id}/dispute` (multipart), qui sont donc rejoués. Or `HttpMessageNotReadableException` — cas réel : une valeur d'enum `event` retirée par un déploiement rend indésérialisable une entrée mise en file avant — tombait encore dans le filet `Exception`, donc **500 / `INTERNAL_ERROR`, classé TRANSIENT**, donc rejouée indéfiniment sans jamais être signalée à son propriétaire. C'est mot pour mot le mode d'échec que les quatre branches ajoutées disaient empêcher. Idem pour `MultipartException` (frontière corrompue sur `/dispute` et `/evidence`) et `HttpMediaTypeNotAcceptableException`. Trois branches ajoutées ; `MissingPathVariableException` laissée au 500 avec la raison écrite (elle signale un défaut de mapping serveur, le 500 est honnête).
  - `[medium]` `[patch]` **Le 405 perdait l'en-tête `Allow`, et sa propre justification était fausse.** La javadoc motivait le choix de 405 plutôt que 404 par « le conteneur pose de toute façon l'en-tête `Allow` » — faux : le handler n'étend pas `ResponseEntityExceptionHandler`, donc la `ResponseEntity` construite à la main **remplace** `DefaultHandlerExceptionResolver` au lieu de le compléter. `Allow` et `Accept` sont désormais posés depuis l'exception, et le test d'intégration asservit `Allow` sur la vraie chaîne.
  - `[medium]` `[patch]` **Le raisonnement n'allait que dans un sens.** Passer de 500 à 4xx **reclasse** ces échecs de transitoire à permanent côté client. Conséquence non pesée : une PWA au shell précaché rejouant contre un backend dont les routes ont bougé **gèle** l'entrée au lieu de guérir seule. C'est le bon comportement au sens d'AD-10 (l'entrée et son binaire sont conservés, l'utilisateur est notifié, la Story 4.5 récupère) — mais il fallait le dire au lieu de laisser croire que cette direction n'existait pas. Argumenté, et asservi par un test de classification.
  - `[medium]` `[patch]` **Les trois cas natifs du test d'intégration n'asservissaient pas le `message`.** Rien n'empêchait donc le message par défaut de Spring — qui nomme le chemin, les méthodes supportées, le paramètre et la valeur reçue — de revenir : la fuite exacte que ces branches existent pour fermer. Messages exacts asservis.
  - `[medium]` `[patch]` **Toutes les assertions migrées ne vérifiaient que le TYPE d'exception.** `TransactionAccessTest`, `EvidenceServiceTest` et `EscrowDisputeServiceTest` resteraient verts si un futur correctif remettait `"Transaction " + txId + " not found"` à un site de jet — la dérive précise que la fabrique unique existe pour empêcher. Toute la propriété littérale reposait sur la seule suite d'intégration, qui exige Docker. Assertions exactes sur `getCode()` et `getMessage()` ajoutées dans `TransactionAccessTest` (test unitaire pur, sans Docker), ce qui a demandé d'y couvrir `resolveRole` — le fichier n'éprouvait que la garde partenaire.
  - `[medium]` `[patch]` **La convention n'était pas tenue, seulement documentée.** Retirer la constante d'enum bloque le `code`, mais rien n'empêchait un futur endpoint de rouvrir l'oracle par le **message**. Nouveau `AntiEnumerationConventionTest` : liste blanche exacte des sites de construction directe (fabriques + garde « pièce à soi » + les deux littéraux d'identité propre de l'appelant), interdiction de toute concaténation dans un message de refus (propriété syntaxique, pas une intention), et le grep de l'ancien code promis par la javadoc devient une assertion au lieu d'une ligne de checklist.
  - `[medium]` `[patch]` **La propriété revendiquée n'était pas bornée.** Les javadoc énonçaient « indistinguable » sans dire de quoi. Elle porte sur le **contenu** de la réponse, pas sur sa **durée** : le chemin « tiers » fait un `findById` fructueux de plus (et jusqu'à deux `users.findById` sur le canal partenaire), et surtout `findByIdForUpdate` fait que deux sondes concurrentes sur un id **réel** se sérialisent sur un verrou `PESSIMISTIC_WRITE`, ce qui n'arrive pas sur un id fictif. Revendication bornée dans les deux javadoc, avec renvoi au ledger.
  - `[low]` `[patch]` Les sept handlers natifs recevaient un `ex` jamais lu : un contrôleur mal enregistré après un refactor rendait un 404 propre et silencieux là où il atteignait `LOG.error`. Journalisation en **DEBUG** — pas WARN : un balayage d'identifiants inonderait les journaux, exactement le vecteur que cette story a re-routé.
  - `[low]` `[patch]` `membershipRefusalHasNoDedicatedCode` filtre les noms d'enum sur `PARTY|MEMBER|OWNER|FOREIGN` et prétendait qu'un code ne survivrait pas « sous un autre nom » ; `ACCESS_DENIED`, `NOT_YOURS`, `NON_PARTICIPANT` passeraient tous. Commentaire réécrit pour dire ce qu'il garde **et ce qu'il ne garde pas**, avec renvoi au test de liste blanche qui, lui, ferme le trou. Six lignes de prose sur le miroir frontend, sans aucune assertion derrière dans un test Java, retirées (l'argument est déjà asservi côté Vitest).
  - `[low]` `[patch]` La javadoc d'`ErrorCode` promettait qu'« un grep de ce nom revient vide » — garantie tenue par une ligne de markdown, c'est-à-dire par personne dès la story suivante. Renvoie désormais au test qui l'asservit ; javadoc de `TRANSACTION_NOT_FOUND` resserrée (19 → 15 lignes, substance conservée).
  - `[low]` `[patch]` `AD10_PERMANENT` disait « les dix codes permanents que nomme AD-10 » : le texte d'AD-10 dans les artefacts de planification liste encore `NOT_A_PARTY` et ignore `WEAK_PASSWORD` et `EVIDENCE_MALWARE_DETECTED`. Le test censé détecter la dérive décrivait donc un document qui n'existe plus. Reformulé pour décrire ce que l'ensemble **est** ; la dérive du texte amont est portée au ledger.
  - `[low]` `[patch]` Trois justifications fausses ou incohérentes dans le test d'intégration : « la suite tourne sur un conteneur partagé » (un `@Container` statique de classe démarre un Postgres **dédié**), trois décomptes d'endpoints contradictoires dans un seul changement (huit / six / sept), et l'exclusion de `Content-Length` motivée circulairement (« il suit le corps, déjà comparé » — or le corps comparé est normalisé). Corrigées ; javadoc de `normalizeTimestamp`, posée sur `field(...)`, remise sur son membre.
  - `[low]` `[patch]` L'entrée de ledger « canal temporel » créée par la passe dev ne mentionnait que le surcoût de requêtes. Complétée par la variante **contention de verrou**, distincte et plus exploitable — un attaquant peut même **allonger** la fenêtre en tenant la transaction qui possède le verrou, donc amplifier le signal au lieu de seulement l'échantillonner.
  - `[low]` `[patch]` La garde frontend `expect(unknown).toEqual(LEGACY_CODES)` compare des tableaux et dépend donc de l'ordre d'insertion de `Object.keys` : verte aujourd'hui avec un seul élément, fragile au second. Rendue insensible à l'ordre **sans** relâcher l'égalité exacte — les deux directions de dérive restent rouges.
  - `[low]` `[patch]` Noms pleinement qualifiés en ligne dans le nouveau montage de `GlobalExceptionHandlerTest`, dans un fichier qui importe tout le reste normalement. Remplacés par des imports.

**Rejets, avec leur raison.** ① « Aucune entrée de ledger n'a été ouverte pour l'oracle d'e-mail de `POST /escrow` » : factuellement faux, l'entrée a été écrite par la passe dev et route le point vers les stories 2.4/2.5/2.6, où c'est une décision produit. ② « `noResourceFoundIs404` asserte `$.message` deux fois, la seconde étant impliquée par la première » : redondance inoffensive qui documente la fuite empêchée. ③ « Le même argument est écrit dans six endroits » : c'est la convention de commentaire du dépôt (expliquer le pourquoi et ce que la décision empêche) ; le reproche confond duplication de **code** — que la fabrique unique supprime — et répétition d'une **explication** à ses points d'usage. ④ « Deux fichiers modifiés ont été soustraits au diff soumis à la revue » : critique de procédé recevable mais sans objet ici, `deferred-work.md` et `project-context.md` ont été relus directement avant d'ouvrir la revue.

## Design Notes

**Pourquoi 404 partout plutôt que 403 partout.** Les deux uniformisent, mais seul le 404 ne confirme rien : un 403 uniforme dirait « cette ressource existe et t'est refusée », ce qui est faux dans un cas sur deux et reste un oracle dès qu'on le compare à la réponse d'un identifiant hors plage. C'est aussi ce que prescrivait déjà le ledger fondateur (`:44` : « renvoyer 404 uniformément pour inexistant ET non-partie, décidé au niveau plateforme »). Enfin, le 403 est **réservé** côté client : la Story 1.9 a fait du 403 nu le signal de session expirée ; multiplier les 403 applicatifs sur cette surface rapproche dangereusement deux vocabulaires que 1.9 vient de séparer.

**Pourquoi retirer `NOT_A_PARTY` et pas seulement cesser de l'émettre.** L'oracle ne vit pas dans le statut, il vit dans le `code` : `AD10_PERMANENT` distingue aujourd'hui `NOT_A_PARTY` de `TRANSACTION_NOT_FOUND`, si bien qu'un 404 uniforme portant deux codes différents ne changerait rien. Une constante que plus rien n'émet est par ailleurs l'outil que le prochain développeur saisira pour sa propre ressource protégée — c'est le mode d'échec que la story existe pour rendre impossible. Le projet a déjà tranché exactement ainsi pour le canal partenaire (`AUTH_FAILED` : clé inconnue, clé inactive, signature fausse, horodatage périmé et nonce rejoué partagent un seul code) ; cette story applique le même raisonnement au canal JWT, jamais fait.

**Pourquoi le frontend ne change pas fonctionnellement.** C'est le point qu'il faut vérifier avant de croire la story sûre, pas après : `NO_LINK_CODES` (`frozenEntry.js:41`) contient **déjà** `TRANSACTION_NOT_FOUND`, et ce code est **déjà** PERMANENT dans `replayFailure.js`. Un rejeu de non-partie était donc gelé sans lien avant la bascule, et le reste après — seul le libellé affiché change, de « You are not a party to this transaction. » à « This transaction no longer exists. », ce qui **est** l'opacité recherchée. La prise en charge de l'ancien code est conservée volontairement : un `code` est gravé dans l'entrée gelée persistée en IndexedDB, donc une file remplie avant le déploiement porte encore `NOT_A_PARTY`.

**L'exception assumée, et pourquoi elle n'en est pas une.** L'invariant réel n'est pas « tout refus devient 404 » mais « ne jamais révéler ce que l'appelant n'a pas le droit de savoir ». Une partie à une transaction voit déjà toutes ses pièces, celles de l'autre partie comprises, via `GET /{id}/evidence` : lui répondre 404 sur le retrait d'une pièce qu'elle vient de lire ne cache rien et dégrade un message d'erreur légitime. Le test jumeau (le refus **et** la visibilité par `list()`) est ce qui rend cette frontière vérifiable plutôt que déclarative. C'est aussi la formulation qui se transpose aux douze stories aval, où un opérateur a le droit de voir une file sans avoir le droit d'agir.

**Les wallets, honnêtement.** L'AC d'epic exige la preuve « sur transactions, preuves et wallets ». Aucune entité, table, endpoint ou garde wallet n'existe : `WalletService.getOrCreate` relève d'AD-13 et de l'Epic 4, et aucun AD ne dit aujourd'hui quelle garde protège un wallet — AD-3 est écrit en termes de « partie à une transaction », AD-13 scope le wallet à company+currency, ce qui pointe vers AD-30/AD-20 sans rien trancher. Fabriquer ici une ressource wallet pour pouvoir la tester serait préempter une décision d'architecture ouverte et déborder d'un epic entier. Ce qui est livrable maintenant l'est intégralement : la fabrique, la convention écrite là où les agents la lisent, et la dette de preuve nommément portée au ledger à la charge de la story wallet — plus le trou normatif remonté au spine, comme l'impose la règle « toute contradiction avec un AD est un conflit à remonter ».

## Verification

**Commands:**
- `cd backend && ./mvnw test` -- attendu : 0 échec (Docker requis). Le compteur monte d'au moins le nouveau test d'intégration et des tests ajoutés ; aucune suppression de test.
- `cd backend && grep -arn "NOT_A_PARTY" src/main` -- attendu : **aucune sortie**.
- `cd backend && grep -arn "throw new ForbiddenException" src/main` -- attendu : **exactement une** ligne, la garde « pièce à soi » d'`EvidenceService` (trois aujourd'hui).
- `cd backend && grep -arn '"[^"]*not found' src/main/java/com/zlecaf/escrow/service/ src/main/java/com/zlecaf/escrow/web/ApiExceptions.java` -- attendu : **quatre** lignes — les deux fabriques, plus les deux littéraux préexistants `AuthService:88 "User not found"` et `EscrowService:67 "Acting user not found"`, qui portent sur **l'identité propre de l'appelant** (résolue depuis son propre JWT) et n'énumèrent donc rien. Aucun message ne concatène d'identifiant.
- `cd frontend && npm run test` -- attendu : suite verte, **compteur inchangé** (240).
- `git diff --stat -- frontend/` -- attendu : **deux** fichiers. `src/utils/replayFailure.js` (commentaire seul) et `src/utils/__tests__/replayFailure.spec.js` — écart assumé consigné en `## Auto Run Result` : la garde de dérive de ce spec lit `ErrorCode.java` et exige que chaque clé de `FAILURE_LABELS` y soit déclarée, ce que le retrait de `NOT_A_PARTY` rend faux pour le libellé hérité que la story ordonne de conserver. L'assertion est **renforcée**, pas affaiblie (égalité exacte contre une liste `LEGACY_CODES` explicite).

## Auto Run Result

Status: done

### Ce qui a été livré

Sur toute la surface escrow, « cette transaction n'existe pas » et « cette transaction n'est pas la tienne » rendent désormais **la même réponse, octet pour octet** : `404`, `code = TRANSACTION_NOT_FOUND`, `message = "Transaction not found"`, mêmes en-têtes — sur les sept routes utilisateur **et** sur le canal partenaire signé. Même chose au niveau pièce (`RESOURCE_NOT_FOUND` / `"Evidence not found"`), y compris pour le binaire absent du stockage, dont le message trahissait jusqu'ici que la ligne existait et appartenait bien à cette transaction.

Trois décisions structurent le livrable :

1. **L'oracle vivait dans le `code` autant que dans le statut.** Un 404 uniforme portant deux codes différents n'aurait rien fermé — le client AD-10 classe sur ce champ, donc un attaquant le lit aussi. `NOT_A_PARTY` est **retiré de l'enum**, pas seulement cessé d'être émis : une constante que plus rien n'émet est l'outil que le prochain développeur saisira pour sa propre ressource protégée. Le projet avait déjà tranché ainsi pour le canal partenaire (`AUTH_FAILED`) ; c'est le même raisonnement appliqué au canal JWT, jamais fait.
2. **La convention est tenue par des tests, pas par de la documentation.** Une fabrique unique par famille de ressource (`ApiExceptions.transactionNotFound()` / `evidenceNotFound()`), sans identifiant dans le message ; `AntiEnumerationConventionTest` asservit la liste blanche exacte des sites de construction directe **et** interdit toute concaténation dans un message de refus. Sans quoi un futur endpoint rouvrait l'oracle par le message, avec un `code` irréprochable et une suite verte.
3. **La frontière est explicite, et prouvée.** L'invariant réel n'est pas « tout refus devient 404 » mais « ne jamais révéler ce que l'appelant n'a pas le droit de savoir ». Une partie voit déjà les pièces de l'autre via `GET /{id}/evidence` : lui répondre 404 sur le retrait d'une de ces pièces ne cacherait rien et dégraderait un message légitime. Ce 403 reste donc, avec un test **jumeau** qui vérifie la visibilité préalable — c'est ce qui en fait une frontière vérifiable plutôt qu'un oubli, et c'est la formulation qui se transpose aux douze stories aval.

### Fichiers modifiés

| Fichier | Changement |
| --- | --- |
| `backend/.../web/ApiExceptions.java` | Deux fabriques + la convention NFR-P9 en javadoc de classe pour toute ressource protégée future |
| `backend/.../domain/ErrorCode.java` | `NOT_A_PARTY` supprimé ; `TRANSACTION_NOT_FOUND` documenté comme délibérément opaque |
| `backend/.../service/TransactionAccess.java` | Les deux seules gardes d'appartenance (JWT et HMAC) basculent en 404 uniforme |
| `backend/.../service/EscrowService.java` | Trois `orElseThrow` passent par la fabrique |
| `backend/.../service/EvidenceService.java` | Cinq `orElseThrow` transaction + deux niveaux pièce + binaire absent ; `log.warn` serveur sur la clé de stockage |
| `backend/.../web/GlobalExceptionHandler.java` | Sept rejets natifs de Spring sortent dans l'enveloppe (404/405/400/415/400/400/406) au lieu de 500 `INTERNAL_ERROR` classé TRANSIENT ; en-têtes `Allow`/`Accept` ; journalisation DEBUG |
| `backend/.../test/security/AntiEnumerationIntegrationTest.java` | **Nouveau**, 9 tests : comparaison structurelle complète des deux réponses sur la vraie chaîne HTTP |
| `backend/.../test/web/AntiEnumerationConventionTest.java` | **Nouveau**, 3 tests : la convention devient exécutable |
| `backend/.../test/domain/ErrorCodeContractTest.java` | Ensemble permanent à 10, portée du filtre par mots-clés dite honnêtement |
| `backend/.../test/service/TransactionAccessTest.java` | Renommages + assertions littérales sur `code` et `message`, sans Docker |
| `backend/.../test/{service,web}/…` (6 fichiers) | Attentes retournées 403 → 404 ; cas fourre-tout du retrait scindé en deux |
| `frontend/src/utils/replayFailure.js` + son spec | Code hérité annoté ; garde de dérive insensible à l'ordre ; classification permanente asservie |
| `_bmad-output/project-context.md` | La convention écrite là où les agents la lisent avant d'écrire du code |
| `_bmad-output/implementation-artifacts/deferred-work.md` | Entrée fondatrice close, item malware re-routé avec motif, 11 entrées ouvertes |

### Revue

Deux relecteurs adverses en parallèle, sans contexte préalable. **0 intent_gap, 0 bad_spec, 15 patches (1 haute, 6 moyennes, 8 basses), 7 reports, 4 rejets.** Le constat le plus important : l'énumération des rejets natifs de Spring était incomplète alors que la javadoc la présentait comme close — vérification faite, `POST /{id}/event` et `/{id}/dispute` sont bien mis en file hors ligne et rejoués, si bien qu'un corps devenu indésérialisable après un déploiement partait en 500 `INTERNAL_ERROR`, classé TRANSIENT, donc rejoué indéfiniment sans jamais être signalé. Détail complet dans le Review Triage Log.

### Vérification

Exécutée par moi-même, pas seulement rapportée par les agents :

- `cd backend && ./mvnw test` → **476 tests, 0 échec, 0 erreur, 0 ignoré** sur 52 classes (`BUILD SUCCESS`). Baseline avant story : 452.
- `cd frontend && npm run test` → **241 tests, 11 fichiers, 0 échec**. Baseline : 240.
- `grep -arn "NOT_A_PARTY" backend/src/main` → aucune sortie.
- `grep -arn "throw new ForbiddenException" backend/src/main` → exactement une ligne, la garde documentée d'`EvidenceService`.
- Aucun test supprimé ni affaibli. Une seule assertion préexistante modifiée, dans `replayFailure.spec.js`, et elle est **renforcée** : voir l'écart ci-dessous.

### Écart assumé

`frontend/src/utils/__tests__/replayFailure.spec.js` a dû être modifié, contre l'attente initiale « un seul fichier frontend, aucune assertion touchée ». Sa garde de dérive lit `ErrorCode.java` et exige que chaque clé de `FAILURE_LABELS` y soit déclarée ; retirer `NOT_A_PARTY` de l'enum tout en conservant son libellé — ce que la spec ordonne, parce qu'un `code` est gravé dans une entrée gelée d'IndexedDB antérieure au déploiement — la rendait rouge. Traité en patch et non en `Block If` : rien de fonctionnel ne change côté client, et la règle *Always* prévoyait justement le miroir frontend dans le même commit. L'assertion est renforcée (égalité exacte, insensible à l'ordre, contre une liste `LEGACY_CODES` explicite), donc les deux directions de dérive restent rouges.

### Ce qui n'a pas été livré, et pourquoi

**La clause « et wallets » de l'AC d'epic.** Aucune entité, table, endpoint ni garde wallet n'existe : `WalletService.getOrCreate` relève d'AD-13 et de l'Epic 4. En fabriquer une ici pour pouvoir la tester aurait préempté une décision d'architecture encore ouverte — aucun AD ne dit quelle garde protège un wallet, AD-3 parlant de « partie à une transaction » et AD-13 scopant le wallet à (company, currency). Tout le reste a été livré intégralement, la dette de preuve est nommément portée au ledger à la charge de la story wallet, et le trou normatif est remonté au spine comme l'impose la règle du projet.

### Risques résiduels

- **Canal temporel et contention de verrou.** Les deux réponses sont indistinguables en contenu, pas en durée : le chemin « tiers » exécute un `findById` fructueux de plus (jusqu'à deux `users.findById` sur le canal partenaire), et `findByIdForUpdate` fait que deux sondes concurrentes sur un id réel se sérialisent sur un verrou `PESSIMISTIC_WRITE` — qu'un attaquant peut allonger à volonté en tenant la transaction. Explicitement hors périmètre, consigné.
- **Un balayage ne laisse aucune trace.** Le refus uniforme n'écrit ni audit ni journal — et le test l'asservit, à raison : une trace écrite pour un cas et pas pour l'autre serait le même oracle décalé d'un canal. `AuthRateLimitFilter` ne couvre que les trois routes d'authentification, donc rien ne borne le débit sur la surface escrow. L'oracle de contenu est fermé, la détection ne l'est pas. Contrainte notée au ledger : toute journalisation future des refus devra être **symétrique**.
- **`GET /api/v1/webhooks/subscriptions` liste les abonnements de toutes les sociétés** à tout utilisateur authentifié (`findAll()` sans garde). Préexistant, hors périmètre de cette story, sévérité haute, porté au ledger.
- **Identifiants denses et monotones** (`GenerationType.IDENTITY`) : masquer l'existence laisse toute partie lire le compteur global sur son propre identifiant et garde l'espace de sondage balayable.
