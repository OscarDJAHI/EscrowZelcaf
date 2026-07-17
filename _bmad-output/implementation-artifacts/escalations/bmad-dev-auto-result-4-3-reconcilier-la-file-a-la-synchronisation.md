---
status: blocked
story: '4.3 — Réconcilier la file à la synchronisation'
date: '2026-07-17'
blocking_condition: 'intent gaps'
baseline_revision: '711f365'
---

# BMad Dev Auto Result — Story 4.3

Status: **blocked**
Blocking condition: **intent gaps** — la décision d'architecture AD-10 exige un contrat d'erreur que le back-end n'expose pas, et le périmètre de l'epic interdit de le lui ajouter. Les deux ne peuvent pas être vrais en même temps.

Aucun code n'a été écrit. Aucun spec n'a été promu en `ready-for-dev` : le planifier aurait exigé d'inventer une réponse à une question qui appartient à l'humain.

## Le conflit

La story 4.3 est le cœur de l'epic 4 : classer chaque échec de rejeu en **transitoire** (conserver + re-tenter) ou **permanent** (annuler l'optimiste, conserver le binaire, geler l'auto-retry, notifier).

AD-10 impose la méthode de classement, sans ambiguïté :

> « classer chaque échec **par le code applicatif de l'enveloppe d'erreur, jamais par la seule classe HTTP** — un `409` est ambigu. […] **Permanent** […] : un ensemble **énuméré** de codes applicatifs (ex. `DISPUTE_ALREADY_RESOLVED`, `EVIDENCE_INVALID`, `WINDOW_CLOSED`). »

AD-10 anticipe même exactement la situation rencontrée, et ordonne l'escalade plutôt que le contournement :

> « Si un code permanent nécessaire n'est pas exposé côté serveur, c'est un **conflit à remonter** — pas une raison de retomber sur la classe HTTP. »

Or le périmètre de l'epic ferme la seule porte de sortie :

> « **Aucune modification back-end n'est requise** par cet epic : les endpoints consommés sont déjà livrés. »

C'est cette contradiction qui bloque : AD-10 renvoie la question au back-end, le périmètre déclare le back-end hors sujet.

## Constats vérifiés (preuves)

Chacun a été confirmé directement sur le code, pas seulement rapporté.

1. **L'enveloppe d'erreur ne porte aucun code applicatif.**
   `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java:22-28` construit un `Map.of("timestamp", …, "status", …, "error", …, "message", …)` — quatre champs, pas de classe dédiée. `error` vaut `status.getReasonPhrase()` (« Conflict », « Bad Request ») : c'est la reason-phrase HTTP, strictement redondante avec `status`, et non un code métier. `grep '"code"\|errorCode'` sur la couche web : **zéro occurrence**.

2. **Les trois codes énumérés par AD-10 n'existent nulle part.**
   `grep -rniE 'DISPUTE_ALREADY_RESOLVED|EVIDENCE_INVALID|WINDOW_CLOSED'` sur `backend/src` **et** `frontend/src` : **zéro occurrence**. Ce ne sont pas des codes présents mais non exposés — ils n'ont jamais été écrits.

3. **Le seul discriminant disponible est le texte libre de `message`, et il est instable.**
   « Litige déjà résolu » n'a pas d'exception dédiée : le cas se présente comme un `ILLEGAL_TRANSITION` générique, message `"Event OPEN_DISPUTE is not permitted from state RELEASED"` (`EscrowStateMachine.java:64-66`) — une phrase anglaise **interpolée avec des noms d'enum**. « Fenêtre fermée » : `"Evidence cannot be deposited while the transaction is <STATE>"` (`EvidenceService.java:418-419`), également interpolée. « Preuve invalide » : **quatre chaînes distinctes**, noyées parmi ~14 autres messages 400 dont certains sont au contraire **transitoires** (`"Could not read the uploaded file"`), et dont certains sont émis par Spring lui-même (parts manquantes — texte non maîtrisé, dépendant de la version du framework). Un renommage d'état ou un changement de `PlatformLimits.MAX_FILES_PER_DEPOSIT` casserait la classification **silencieusement** : aucun test ne verrouille ces textes comme contrat.

4. **Le `409` optimistic-lock supposé par AD-10 n'existe pas — c'est un `500`.**
   `@Version` est bien présent (`EscrowTransaction.java:49-50`), mais **aucun `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)`** n'existe (`grep 'OptimisticLock'` sur `backend/src/main/java` ne remonte que le `@Version`). La collision échappe donc au `@RestControllerAdvice` et sort en **500 avec le body `/error` par défaut de Spring** — une forme différente, sans `message`. AD-10 range « `409` optimistic-lock » parmi les cas transitoires : ce `409` ne peut pas se produire. Pire pour le classement : la vraie collision arrive en `500`, statut que le front classerait transitoire **par accident**, au même titre que n'importe quel bug serveur non mappé.

5. **Un précédent existe déjà côté serveur, et il est jeté.**
   `TransitionException.Reason` (`TransitionException.java:3-14`) est une enum explicitement documentée comme « machine-readable reason ». Elle est **uniquement** consultée pour choisir le statut HTTP (`GlobalExceptionHandler.java:32-34`) et **jamais sérialisée**. Le code machine que réclame AD-10 existe donc côté back-end — il est détruit avant d'atteindre le client. C'est le point d'appui naturel d'une généralisation.

## Ce qui n'est PAS bloquant (résolu à l'investigation)

- **`flush()` et son `break` aveugle** (`frontend/src/stores/offlineQueue.js:173-174`) : arrête toute la boucle au premier échec, conserve l'entrée et tout le reliquat, sans rien lire du body. Le point d'insertion du classement est clair et non ambigu.
- **L'entrée de file ne porte aucun état d'échec** (ni `retries`, ni `status`, ni `frozen` — `offlineQueue.js:123-131`) : à ajouter, mais c'est du travail normal, pas une question ouverte.
- **L'annulation de l'optimiste** : l'état d'origine (`FUNDS_LOCKED`) est **écrasé** au marquage et sauvegardé nulle part (`escrow.js:164-173`). Ce n'est pas bloquant pour autant — `flush()` ne s'exécute qu'en ligne, donc un refetch (`loadTransactionDetail` + `loadTransactions`) restaure la vérité serveur, et c'est déjà le mécanisme qui efface les marqueurs sur le chemin heureux via `escrow:sync`. Un `meta.previousState` reste une option si un rollback sans réseau devenait nécessaire.
- **Pas d'auto-retry programmé** (ni interval, ni backoff) : `flush()` n'est déclenché que par le listener `online`, la fin de `init()`, ou un appel de test. « Geler l'auto-retry » d'une entrée est donc peu coûteux à implémenter.

Autrement dit : **tout le reste de la story est prêt à être planifié.** Seul le critère de classement manque, et c'est précisément le critère qui définit la story.

## Question à trancher (humain)

Comment le front doit-il obtenir un verdict **fiable et stable** de rejet permanent, sachant qu'aucun code applicatif n'existe côté serveur ?

Les options ne sont pas équivalentes — elles engagent des périmètres différents :

- **Option A — Étendre le contrat d'erreur back-end** (recommandée, et conforme à la lettre d'AD-10). Ajouter un champ `code` à l'enveloppe (`GlobalExceptionHandler.java:22-28`), faire porter un code stable par chaque exception métier en généralisant le précédent `TransitionException.Reason`, distinguer l'`ILLEGAL_TRANSITION` sur état terminal de l'illégalité ordinaire, et mapper explicitement `ObjectOptimisticLockingFailureException` sur un `409` codé retryable. **Coût** : contredit « aucune modification back-end » et déborde l'epic 4 (front-end) — probablement une story back-end distincte, à ordonnancer **avant** 4.3. **Bénéfice** : c'est la seule option qui rend l'invariant central de l'epic (« aucune perte silencieuse ») réellement démontrable, et 4.4/4.5 en dépendent tout autant.
- **Option B — Classer sur la classe HTTP seule.** Explicitement interdit par AD-10 (« un `409` est ambigu »), et factuellement faux ici : `400` mélange définitif et transitoire, `500` mélange collision concurrente et bug serveur. Non recommandée.
- **Option C — Matcher le texte des `message`.** Contourne AD-10 en substituant du texte à un code. Trois des chaînes clés sont interpolées (noms d'enum, constantes numériques) ; aucun test ne les fige. Une classification qui casse en silence sur un renommage est précisément le mode de défaillance que l'epic interdit (perte silencieuse). Non recommandée.

Question subsidiaire, à trancher avec la principale : **quels états/motifs sont réellement « permanents » ?** Les trois exemples d'AD-10 sont donnés comme illustratifs (« ex. ») ; l'énumération faisant foi reste à arrêter — notamment le sort de `"Could not read the uploaded file"` (400 mais re-tentable) et des `400` émis par Spring lui-même.

## Reprise

Une fois la décision prise, relancer :

```
/bmad-dev-auto 4-3-reconcilier-la-file-a-la-synchronisation
```

Si l'option A est retenue, la story back-end de codification doit être livrée **avant** cette relance : 4.3 ne peut pas produire de verdict de réconciliation fiable sans elle, et 4.4 (notification du motif) comme 4.5 (écran de récupération) consomment ce même verdict.
