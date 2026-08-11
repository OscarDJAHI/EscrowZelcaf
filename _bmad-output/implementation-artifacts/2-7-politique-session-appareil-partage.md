---
baseline_commit: c4063040bab3eb3120720f3dd03cb600d2517cdf
---

# Story 2.7: Politique de session sur appareil partagé (NFR-P8)

Status: in-progress

<!-- Contexte créé le 2026-08-10. Faits vérifiés contre le code à cette date. -->

## Story

As a personne utilisant la plateforme depuis un poste partagé,
I want que ma session ne survive pas à mon départ,
so that la personne suivante ne se retrouve pas silencieusement authentifiée à ma place, avec mes transactions et mes preuves (NFR-P8).

**Origine :** décision produit **D4** tranchée le 2026-07-28 par Oscard — *les deux mesures, pas l'une ou l'autre*. Le mode de défaillance DOMINANT de l'appareil partagé — fermer l'onglet sans se déconnecter — est resté entier après la Story 1.9 : `ttl-seconds: 86400`, jeton en `localStorage`, aucune expiration d'inactivité. Cette story porte la politique **et ses quatre conséquences directes**, qui sont des implémentations de cette politique et non des correctifs indépendants (ex-bundle SESSION-PARTAGÉE du ledger).

---

## Acceptance Criteria

### AC1 — Le jeton meurt avec l'onglet
**Given** un utilisateur qui se connecte sans cocher « rester connecté »
**When** il ferme l'onglet puis qu'une autre personne rouvre l'application sur le même poste
**Then** aucune session n'est restaurée — le jeton vit en `sessionStorage` et meurt avec l'onglet
**And** l'option « rester connecté », explicite et **non cochée par défaut**, bascule le stockage en `localStorage` pour les appareils personnels.

### AC2 — Expiration d'inactivité, minuterie ET démarrage
**Given** une session active laissée sans interaction
**When** le délai d'inactivité est dépassé, que l'onglet soit resté ouvert ou que l'application soit rouverte après ce délai
**Then** la session est terminée par le chemin existant `endSession` (purge locale complète avant l'appel réseau) et l'utilisateur est renvoyé vers l'authentification **avec un motif affiché**
**And** le contrôle s'exerce aussi **AU DÉMARRAGE**, pas seulement par minuterie — sinon un onglet rouvert échappe à la mesure.

### AC3 — Propagation inter-onglets
**Given** plusieurs onglets ouverts sur le même appareil
**When** l'utilisateur se déconnecte, ou que sa session expire, dans l'un d'eux
**Then** les autres onglets terminent leur session sans intervention et cessent d'afficher une interface authentifiée
**And** le mécanisme retenu est **documenté avec sa règle d'autorité entre onglets**.

### AC4 — Déconnexion à attente bornée
**Given** une déconnexion sur un réseau qui ne répond pas (portail captif, DNS suspendu)
**When** l'utilisateur clique sur « se déconnecter »
**Then** la navigation vers l'écran d'authentification n'attend pas indéfiniment la révocation serveur — l'attente est **bornée**, `keepalive` garantissant que la requête aboutit même après la navigation
**And** l'hygiène locale reste inchangée : tout le local est purgé **AVANT** l'appel réseau, ordre déjà prouvé par test.

### AC5 — Profil illisible traité explicitement
**Given** un jeton présent dont le profil `escrow_user` est illisible
**When** l'application démarre
**Then** cet état incohérent est traité explicitement — soit le jeton est effacé, soit la file signale « session incohérente » — au lieu d'être lu comme « personne n'est connectée », ce qui fait naître des entrées hors-ligne orphelines, supprimées à la déconnexion suivante.

### AC6 — Époque de session contre les courses A→B
**Given** une réponse de lecture émise pendant la session de A
**When** elle se résout après la connexion de B
**Then** elle est rejetée : une **époque de session monotone**, portée par `stores/session.ts`, est capturée à l'émission et comparée à la résolution
**And** le compteur anti-course d'`evidence` ne peut plus être rembobiné à 0 par un `$reset()` au point de rendre une réponse de A égale à la première lecture de B.

### AC7 — Preuve par mutation, y compris du câblage
**Given** la politique livrée
**When** la suite de tests est exécutée
**Then** chaque garde est prouvée **par mutation** (règle du `project-context.md`) — en particulier le test du **bouton de déconnexion** et du **câblage de `main.ts`**, aujourd'hui sans aucune couverture alors que ce sont les deux seuls appelants de production des primitives de session.

> **Correction de l'AC7 par rapport à `epics.md` :** l'AC dit « câblage de `main.js` ». **Ce fichier n'existe plus** — la migration TypeScript (commits `47c075f`→`35e1a0c`) l'a renommé `main.ts`. Voir le tableau de conversion des chemins en Dev Notes ; tous les chemins `.js` des artefacts amont sont périmés dans leur extension, **aucun ne l'est sur le fond**.

---

## Décisions de spécification

Aucune source amont ne tranchait les points suivants. Ils sont tranchés **ici** ; deux attendent confirmation d'Oscard (marqués ⚠️, listés en fin de fichier).

### D-A — L'expiration d'inactivité tombe dans la sémantique `expired`, pas `logout`

`endSession` a **déjà deux modes aux périmètres différents** (Story 1.9, `session.ts:100-213`) :
- `logout` — purge tout : mémoire, identifiants, **IndexedDB de l'utilisateur**, cache de lecture, marqueur `escrow_last_user`, **puis** révocation serveur.
- `expired` — purge mémoire et identifiants seulement, sort avant IndexedDB (`session.ts:158`).

L'inactivité prend la sémantique **`expired`**. Motif : détruire la file offline parce que quelqu'un est parti déjeuner, c'est perdre du travail non envoyé — **AD-9 interdit la perte silencieuse de fichier**, et **UX-DR32 exige que la file survive à la ré-authentification**. L'hygiène d'appareil partagé est assurée par la mort du jeton et par le fait que la file est **scopée par utilisateur** (B ne peut pas rejouer les entrées de A). Ce qui protège, c'est le scoping, pas la destruction.

> **CONFIRMÉ par Oscard le 2026-08-10** (question Q2, close). Décision normative, à ne pas rouvrir en développement ni en revue sans la reposer explicitement.

Le nouveau mode s'appelle **`'idle'`** et non `'expired'` : il doit être distinguable pour afficher son propre motif (AC2), alors que `'expired'` reste le 403 nu.

### D-B — `BroadcastChannel` est obligatoire, l'écouteur `storage` ne suffit pas

L'évidence du ledger (entrée E1) proposait « un écouteur `storage` sur la clé de jeton ». **Ce correctif est périmé par D4 elle-même** : l'événement `storage` **ne se déclenche pas entre onglets pour `sessionStorage`**, qui est cloisonné par onglet. Avec AC1, le chemin par défaut n'émettrait aucun événement.

Mécanisme retenu : **`BroadcastChannel('escrow-session')`**, avec repli silencieux si l'API est absente (la propagation dégrade, elle ne casse pas).

**Règle d'autorité entre onglets (AC3) :** l'onglet où l'action a lieu est l'**émetteur unique** de la révocation serveur. Les onglets récepteurs exécutent une terminaison **purement locale** — ils n'appellent ni `logoutUser` ni aucun endpoint. Motif : **NFR-P2** rate-limite `/auth/*` ; N onglets produisant N révocations transformeraient une déconnexion en rafale anti-bruteforce contre l'utilisateur lui-même.

### D-F — Veto du récepteur sur `'idle'` (tranchée par Oscard le 2026-08-11)

Défaut trouvé EN livrant T4, pas prévu par les AC. L'horodatage d'inactivité vit dans le substrat du jeton, donc dans `sessionStorage` par défaut : il est **propre à chaque onglet**. Un onglet laissé en arrière-plan expire au bout de 15 minutes, diffuse `'idle'`, et **tue la session d'un onglet où l'utilisateur est en train de travailler**.

Deux aggravants : le cas ne se produit **pas** en mode « rester connecté » (horodatage partagé en `localStorage`), donc la politique se comporte différemment selon le mode de stockage ; et c'est le genre de défaut qui remonte comme bug le lendemain de la mise en service.

**Décision : veto du récepteur.** À la réception d'un message `'idle'`, un onglet qui **n'est pas lui-même inactif** ignore le message et poursuit sa session.

- Appareil réellement abandonné → tous les onglets sont inactifs → tous terminent. NFR-P8 est intact, c'est le scénario de la story.
- Onglet actif → il survit. L'utilisateur n'est pas puni d'avoir laissé un second onglet ouvert.
- **Le veto ne vaut QUE pour `'idle'`.** `'logout'` et `'expired'` traversent toujours : la déconnexion est un geste délibéré et le 403 nu est un verdict serveur — ni l'un ni l'autre ne se discute au niveau du récepteur.

Motif du choix contre l'horodatage partagé entre onglets, qui serait conceptuellement plus juste : il rouvrirait une décision de T3 déjà livrée et prouvée, pour un gain que le veto obtient sans toucher au substrat.

### D-C — Le bouton de déconnexion migre dans les shells

`sprint-status.yaml:206` affirme « 2-3 done, le bouton de déconnexion a sa place définitive ». **Cette affirmation est fausse et vérifiée fausse** : `ClientShell.vue` et `DesktopShell.vue` ne portent aucune déconnexion (`grep -rn "logout\|endSession" frontend/src/layouts` → 0). Le bouton est resté dans `views/DashboardView.vue:101-106`.

Conséquence non relevée jusqu'ici : **les rôles ARBITRATOR et ADMIN n'ont aujourd'hui aucun moyen de se déconnecter par l'interface** — `DesktopShell` sert leurs deux espaces et ne porte pas le bouton. UX-DR20 pose « une seule auth pour trois espaces » ; une politique de session qui ne s'applique qu'à un espace n'est pas une politique.

Le bouton migre donc dans les deux shells. Il est **tokenisé au passage** (aujourd'hui `border-gray-300 text-gray-600 hover:bg-gray-50`, classes brutes non conformes à 2-1) et reçoit `type="button"` (règle 2-2).

### D-D — Le périmètre de la purge est explicite, et trois choses survivent

Aucune source ne définissait « purge locale complète ». Elle **exclut nommément** :
1. `escrow_locale` — préférence d'**appareil**, pas de session. Décision de la Story 2.1, commentée en `session.ts:189-199` : *« ne pas ajouter `escrow_locale` à la purge sans rouvrir la décision »*. Verrouillé par `session.spec.ts:155`.
2. Le **cache de lecture** en mode `expired`/`idle` — AD-27 : il survit à la ré-authentification.
3. Les **entrées IndexedDB des autres utilisateurs** du même appareil — invariant 1.9, verrouillé par `session.spec.ts:211`.

Et elle **inclut** en mode `logout` : mémoire des stores, identifiants, IndexedDB de l'utilisateur qui part + les orphelines, cache de lecture, marqueur `escrow_last_user`.

### D-E — Ce que la story ne revendique PAS

**Interdiction d'écrire une AC absolue.** Deux chemins de fuite restent ouverts et sont **hors périmètre**, routés vers la Story 11-3 / revue PWA :
- **E2** — l'index d'expiration Workbox conserve les URL `/api/` du partant (`vite.config.ts:66-71`) ; `purgeReadCache()` ne le touche pas.
- **E9** — une réponse `/api/` en vol au moment de la purge **recrée** `escrow-api-cache` derrière elle (stratégie `NetworkFirst`, écriture par le service worker hors de tout store Pinia). **L'époque de session de l'AC6 ne couvre pas ce chemin.**

Ne pas écrire « aucune donnée de A ne survit ». L'AC1 de la Story 1.9 disait « à aucun instant du parcours » et a été relevée en revue comme non tenue au sens strict. **Écrire l'écart plutôt que le cocher.**

---

## Tasks / Subtasks

- [x] **T0 — Lire avant d'écrire** (préalable bloquant)
  - [x] `frontend/src/stores/session.ts` en entier — il porte **deux avertissements « Story 2.7 réécrira ce fichier »** (`:198-199`) et ses commentaires sont *load-bearing*.
  - [x] `frontend/src/api/client.ts:19-26` (intercepteur requête), `:35` (verrou `sessionExpiryAnnounced`), `:61` (`concernsCurrentSession`), `:89` (`envelopeWasParsed`), `:111-126` (intercepteur réponse).
  - [x] `frontend/src/stores/auth.ts:32` (`loadStoredUser`), `:51` (state), `:63-68` (`persist`), `:181` (`clearSession`), **`:213-224`** (l'avertissement sur `logout()` — chemin hérité, aucun appelant de production).
  - [x] `frontend/src/stores/__tests__/session.spec.ts:243-293` — le test d'ordonnancement. **Le comprendre avant de toucher `endSession`.**
  - [x] `frontend/src/i18n/index.ts:22-56` — le patron de lecture résiliente du stockage (la simple LECTURE lève sous Safari « bloquer tous les cookies »).

- [x] **T1 — Substrat de stockage commutable** (AC: 1)
  - [x] Créer une indirection **unique** pour le jeton et le profil. Aujourd'hui **3 points de lecture** (`client.ts:20`, `client.ts:62`, `auth.ts:51`) et **2 d'écriture** (`auth.ts:64-65`) accèdent directement à `localStorage`. Aucun ne doit subsister.
  - [x] Le choix de substrat est lui-même persisté en `localStorage` (il doit survivre à la fermeture de l'onglet pour que « rester connecté » tienne au retour) sous une clé dédiée. **Il ne contient aucune donnée personnelle.**
  - [x] Reproduire la **résilience d'accès** de `i18n/index.ts` pour `sessionStorage` : l'accès à la propriété peut lever, pas seulement la méthode.
  - [x] ⚠️ **`client.ts:61` (`concernsCurrentSession`) est un correctif de la Story 1.9, pas un détail de lecture.** Il compare l'en-tête `Authorization` de la requête échouée au jeton **stocké**, pour empêcher un 401/403 nu **retardataire de la session précédente** de détruire la session **neuve** — et il **échoue ouvert**. Si la lecture ne suit pas le changement de substrat, ce correctif redevient inopérant en silence : la garde lira un stockage vide, ne reconnaîtra plus la session courante, et le défaut de 1.9 se rouvre **avec une suite verte**. Migrer cette lecture **et** vérifier par mutation que `client.spec.ts` rougit.
  - [x] **Exporter `LAST_USER_STORAGE_KEY`** depuis `session.ts:57` (aujourd'hui `const` privé) et remplacer le littéral dupliqué de `session.spec.ts:55`. Sans cela, un renommage laisserait les assertions vertes.

- [x] **T2 — Case « rester connecté »** (AC: 1)
  - [x] Dans `AuthView.vue`, mode login uniquement. **Non cochée par défaut.** Clé i18n EN+FR, cible ≥ 44 px, focus visible (UX-DR38).
  - [x] ⚠️ **Ne pas fermer la porte de la Story 2.6** : le TOTP s'insérera **entre** la soumission et l'émission du jeton. Le choix doit être mémorisé **avant** d'avoir un jeton en main.

- [x] **T3 — Expiration d'inactivité** (AC: 2)
  - [x] **Délai = 15 minutes** (tranché par Oscard le 2026-08-10). **Constante nommée et exportée**, jamais un littéral recopié : un seuil présent à deux endroits diverge au premier ajustement, et le test doit lire la même constante que la production.
  - [x] Minuterie réarmée sur interaction + **contrôle au démarrage** (`main.ts`). Le contrôle au démarrage est ce qui attrape l'onglet rouvert — et le shell est **précaché** (UX-DR46), donc l'UI authentifiée s'affiche **avant tout appel API**.
  - [x] 🔴 **« Inactivité » ≠ « absence de clic ». Un versement de preuve en cours EST de l'activité.** Un dépôt de 10 Mo (`PlatformLimits`) sur une liaison de corridor lente peut dépasser 15 minutes **sans une seule interaction** : l'utilisateur clique « déposer » puis attend. Une minuterie naïve tuerait la session **au milieu du transfert** et détruirait exactement le travail que la décision Q2 protège. La preuve d'activité doit donc inclure **les requêtes en vol**, pas seulement les événements d'entrée. Couvrir ce cas par un test explicite — c'est le scénario le plus coûteux de la story s'il est manqué, et le plus silencieux.
  - [x] Horodatage de dernière activité persisté dans le même substrat que le jeton.
  - [x] Ajouter le mode `'idle'` à `EndSessionReason` (`session.ts:90`). **Traiter explicitement le mode inconnu** : la revue 1.9 a relevé que `explicit = reason === 'logout'` faisait tomber tout mode nouveau **en silence** sur le chemin conservateur (`spec-1-9:150`). Ajouter un mode rouvre ce défaut mécaniquement.
  - [x] Motif affiché : clé i18n EN+FR, **motif ET action de reprise** (UX-DR28), passé par le **canal d'annonces a11y centralisé** (convention Frontend du spine) — ne pas créer un second canal. → ⚠️ **contradiction remontée** : ce canal centralisé **n'existe pas** dans le code (voir Completion Notes). Idiome existant repris (`role="status"`), aucun second canal créé.
  - [x] ⚠️ **Ne pas réarmer le verrou `sessionExpiryAnnounced` par minuterie.** `client.ts:30-33` documente que seul `beginSession` l'abaisse. La minuterie d'inactivité est un mécanisme distinct.

- [x] **T4 — Propagation inter-onglets** (AC: 3)
  - [x] `BroadcastChannel('escrow-session')`, repli silencieux si absent.
  - [x] Récepteurs : terminaison **locale seule**, aucun appel réseau (voir D-B, motif NFR-P2).
  - [x] Pas de rechargement brutal — la convention Frontend du spine interdit le reload silencieux ; l'onglet doit terminer sa session de façon **observable**.
  - [x] Documenter la règle d'autorité **dans le code**, en commentaire load-bearing.
  - [x] ⚠️ Écart nommé, **pas coché** : la propagation est LOCALE À L'APPAREIL. Une révocation déclenchée côté serveur depuis un autre appareil (Story 2.6 / NFR-P5) ne produit aucun message ; elle se détecte par le 403 nu au prochain appel, chemin qui existe déjà.
  - [x] **T4-bis — Veto du récepteur (décision D-F, 2026-08-11)** : un onglet qui n'est pas lui-même inactif ignore une annonce `'idle'` et poursuit sa session. Veto **borné à `'idle'`** — `'logout'` et `'expired'` traversent toujours. Primitive de T3 réutilisée (`isIdleExpired()`), aucun calcul de seuil recopié.

- [x] **T5 — Attente bornée sur la révocation** (AC: 4)
  - [x] Borner l'attente de `auth.revokeOnServer` / `logoutUser` (`api/auth.ts:77-80`, aujourd'hui `fetch` + `keepalive`, **aucun `signal`**). → plafond `REVOCATION_WAIT_SECONDS = 3` porté par `stores/session.ts`, appliqué par `withBudget` sur le seul `await` de la révocation.
  - [x] ⚠️ **NEVER (spec 1.9:47) : ne pas remplacer `logoutUser` par un appel axios.** Le `fetch` + `keepalive` est une décision de la revue 1.6. `keepalive` est précisément ce qui garantit que la requête aboutit après la navigation. → `api/auth.ts` **inchangé**, et désormais **asservi** par `api/__tests__/authLogout.spec.ts` (`keepalive: true`, aucun `signal`).
  - [x] ⚠️ **Ce changement peut casser `session.spec.ts:243-293`** → il ne l'a PAS cassé, et pour une raison qui mérite d'être écrite : ce test observe sur `vi.waitFor(() => expect(logoutUser).toHaveBeenCalled())`, pas sur un nombre de macrotâches. La leçon de la passe 3 de la 1.9 avait déjà été appliquée à ce test ; c'est elle qui l'a rendu insensible à l'`await` supplémentaire. **Vérifié par mutation** : il rougit toujours quand on remet la révocation devant les purges.
  - [x] `DashboardView.vue:53-63` porte un **commentaire justificatif périmé** → réécrit : la phrase fausse (« la navigation avortait la requête en vol ») est supprimée, les deux raisons qui tiennent sont écrites, et le report au ledger qu'il annonçait est déclaré clos.
  - [x] L'attente visible est libellée avec son délai annoncé (UX-DR26) → `common.loggingOut` EN+FR, délai **interpolé** depuis `REVOCATION_WAIT_SECONDS`, bouton `:disabled` + `aria-busy`.

- [x] **T6 — Trois états du jeton** (AC: 5)
  - [x] `loadStoredUser()` (`auth.ts:31`) rend `null` sur échec de `JSON.parse` alors que le jeton survit. Distinguer **trois** états : jeton absent / jeton + profil lisible / jeton + profil illisible. → `SessionState = 'anonymous' | 'active' | 'incoherent'`, getter `sessionState` de `stores/auth.ts`. **Le jeton n'est PAS effacé** — motif en Completion Notes.
  - [x] Le **routeur a déjà** une branche « session incohérente » (2-3, `router/index.ts:181`). **La file offline ne l'a pas** : `init()`/`hydrate()` lisent `user?.id == null` comme « pas de session », et chaque mise en file estampille `userId: undefined` → entrées orphelines. → `init()` ne prétend plus avoir lu la file, `enqueue()` refuse plutôt que de fabriquer une orpheline. Branche du routeur **non réécrite**, et la raison est écrite sur place.
  - [x] ⚠️ La 2-4 introduit un **quatrième** état — compte non vérifié. La logique de démarrage doit l'accueillir sans réécriture. → repère explicite dans `sessionState`, branches nommées (jamais `!== 'active'`) dans `init()`.

- [x] **T7 — Époque de session** (AC: 6)
  - [x] Compteur monotone porté par `session.ts`, capturé à l'émission, comparé à la résolution. → `sessionEpoch` au module (jamais dans un `state()`, qu'un `$reset()` rembobinerait), `currentSessionEpoch()` exportée, `turnSessionEpoch()` privée.
  - [x] Câbler sur `stores/escrow.ts` (`this.transactions = await fetchTransactions()` — **aucune garde de session**, seulement un `issuedAt` horodaté). → `loadTransactions` **et** `loadTransactionDetail`, sur les TROIS sorties (succès, échec, `finally`).
  - [x] `evidence.ts` : `loadSeq: 0` dans le `state()`, donc `$reset()` le rembobine. L'époque doit rendre ce rembobinage inoffensif — **ne pas supprimer `loadSeq`**. → `loadSeq` **conservé et désormais testé** (il n'avait aucun test) ; l'époque s'ajoute devant lui sur les trois sorties.
  - [x] ⚠️ **L'époque est une garde client complémentaire, jamais un substitut d'AD-3.** L'autorisation reste serveur. → écrit dans le module ; aucune décision d'autorisation n'est déplacée vers le client.
  - [x] Écart D-E **nommé et non coché** : le chemin service worker (E9) n'est pas couvert, écrit dans `session.ts` et dans l'en-tête de `sessionEpoch.spec.ts`.

- [x] **T8 — Bouton de déconnexion dans les shells** (AC: 3, 7 — voir D-C)
  - [x] Migrer de `DashboardView.vue:101-106` vers `ClientShell.vue` et `DesktopShell.vue`. → extrait en `components/LogoutButton.vue`, **écrit une fois** et placé par les deux shells. Le porter en double aurait dupliqué `endSession`, le filet et l'état d'attente — trois choses que l'AC7 exige de prouver, et qu'il aurait fallu prouver deux fois.
  - [x] Tokeniser (classes brutes aujourd'hui), `type="button"`, cible ≥ 44 px, focus visible. → délégué à `AppButton` (2-2), qui porte déjà `type="button"` par défaut et `min-h-[44px]` sur toutes les variantes. **L'anneau de focus, lui, n'existait pas** (voir Completion Notes).
  - [x] ⚠️ **Point ouvert de 2-2 reconduit deux fois** : `DESIGN.md` définit `primary-hover` mais **aucun `danger-hover`**. → **NON DÉCLENCHÉ, et c'est un constat, pas un report.** Le bouton d'origine était neutre (`border-gray-300 text-gray-600 hover:bg-gray-50`), donc son équivalent tokenisé est `secondary`, pas `danger`. Le rendre rouge au passage aurait été une décision de design prise en contrebande d'une tâche de migration. L'arbitrage `danger-hover` reste donc ouvert pour qui livrera un vrai bouton destructeur.
  - [x] ⚠️ **Ne jamais recâbler ce bouton sur `auth.logout()`.** → `endSession({ reason: 'logout' })` conservé, et l'avertissement recopié **dans** `LogoutButton.vue` : le fichier qui porte l'appel est le seul endroit où l'interdiction sera relue au bon moment.
  - [x] Conserver le filet `window.location.assign('/auth')` si `router.replace` échoue, et **le tester** : il est aujourd'hui entièrement mort pour la suite. → conservé et **couvert par 3 tests** (`components/__tests__/logoutBoundedWait.spec.ts`), dont l'un a d'abord été écrit CREUX et redressé par la mutation (Completion Notes).

- [ ] **T9 — Tests** (AC: 1..7)
  - [ ] `DashboardView`/shells : premier test du bouton de déconnexion. Gabarit le plus proche : `views/__tests__/RecoveryView.spec.ts:497-523` (`trigger('click')` avec confirmation/annulation).
  - [ ] **Câblage `main.ts`** : `src/__tests__/appShell.spec.ts` est le véhicule qui existe déjà (monte l'application entière avec le vrai routeur) et ne mentionne ni `logout` ni `endSession`. *« Un shell testé isolément prouve qu'il fonctionne, pas qu'il est branché. »*
  - [ ] Minuteries factices : **un seul fichier** du dépôt en fait usage (`escrow.offline.spec.ts:304-355`) et **uniquement `setSystemTime`** — aucun `advanceTimersByTime` nulle part. Patron à introduire.
  - [ ] ⚠️ **`vi.waitFor` s'appuie sur les minuteries RÉELLES et se bloque sous `vi.useFakeTimers()`.** `session.spec.ts:279` et `:583` en dépendent. Ne pas basculer tout le fichier en minuteries factices.
  - [ ] **Instants sentinelles, jamais `new Date()`** : deux `toISOString()` réels tombent dans la même milliseconde et ne distinguent rien.
  - [ ] Pinia **réel** — `@pinia/testing` n'est pas installé **et ne doit pas l'être** (`SyncFailureNotice.spec.ts:94`). i18n réel via `createEscrowI18n('en')`.
  - [ ] Session posée par la vraie primitive `applySession({token, user})` : une fixture ne posant que `user` laisse `isAuthenticated` à `false` et teste silencieusement le chemin déconnecté.
  - [ ] **Chaque assertion négative appariée à une positive** dans le même test, comptes exacts (un `data-testid` inexistant fait renvoyer 0 à `findAll` et satisfait « au plus un » par le vide — **quatrième occurrence de ce motif sur cet epic**).

- [ ] **T10 — Vérification par mutation** (AC: 7 — obligatoire, `project-context.md:99-102`)
  - [ ] Une mutation **par garde livrée** : substrat `sessionStorage`, contrôle au démarrage, minuterie, propagation inter-onglets, borne d'attente, branche « profil illisible », époque de session, câblage `main.ts`, bouton de déconnexion.
  - [ ] Forme : **mutation la plus proche du défaut d'origine** (pas une mutation commode), suite relancée, **et** vérification que ce sont bien les tests visés qui rougissent.
  - [ ] ⚠️ Piège 1.9 (`spec-1-9:170`) : une assertion posée sur un chemin `break` **n'est pas** détectée par la mutation — `break` sort normalement et atteint tout ce qui suit ; seul un `throw` saute la ligne. *« Sans la passe de mutation, un test vert et inutile aurait été livré comme preuve. »*
  - [ ] Consigner chaque résultat dans la section Completion Notes.

- [ ] **T11 — Barrières** (toutes bloquantes en CI)
  - [ ] `npm run test` — base actuelle **427 tests / 29 fichiers**, tous verts en 12,1 s.
  - [ ] `npm run lint` (`--max-warnings 0`, `reportUnusedDisableDirectives: 'error'` — une directive `eslint-disable` devenue inutile **fait échouer** le lint).
  - [ ] `npm run build` = `npm run type-check && vite build` (`vue-tsc --build`) — **gate ajouté par la migration TS, non consigné dans les stories antérieures**. Doit rester à 0 diagnostic.
  - [ ] `python3 scripts/check-encoding.py` (racine backend) — **écrire le français avec ses accents**, y compris dans les commentaires de test.
  - [ ] `npm run verify:no-demo`, `npm run verify:pwa`.

---

## Dev Notes

### ⚠️ Tableau de conversion des chemins — tous les artefacts amont citent des `.js` morts

La migration TypeScript a eu lieu **entre la Story 2-3 et la Story 2-4** et **aucun artefact de planification ne l'a intégrée**. Les faits tiennent tous ; les chemins et numéros de ligne, non.

| Cité par le ledger / spec 1.9 / epics.md | Réel au 2026-08-10 |
|---|---|
| `main.js:18` | `frontend/src/main.ts:26` (`app.mount` en `:33`) |
| `stores/session.js` | `frontend/src/stores/session.ts` — `READ_CACHE_NAME:44`, `LAST_USER_STORAGE_KEY:57`, `purgeReadCache:67`, `EndSessionReason:90`, `endSession:100`, `beginSession:229`, `installSessionExpiryListener:324` |
| `stores/auth.js:17-24`, `:41` | `frontend/src/stores/auth.ts:32`, `:51`, `:64-68` |
| `api/auth.js:36-43` | `frontend/src/api/auth.ts:77-80` |
| `api/client.js:60`, `:3` | `frontend/src/api/client.ts:61`, `TOKEN_STORAGE_KEY` en `:5` |
| `views/DashboardView.vue:54` | `frontend/src/views/DashboardView.vue:66` |
| `stores/evidence.js` (`loadSeq`) | `frontend/src/stores/evidence.ts:15,25,30,38,42,89` |
| `stores/offlineQueue.js` (`flushing`) | `frontend/src/stores/offlineQueue.ts:305,316,429` |
| `stores/escrow.js:82,127,185` | `frontend/src/stores/escrow.ts:71-92,135,149` |
| `vite.config.js:50-58` | `frontend/vite.config.ts:66-71` |
| `application.yml:44` | `backend/src/main/resources/application.yml:77` |

Piège annexe : `vitest.config.ts:16` déclare `setupFiles: ['./vitest.setup.js']` alors que le fichier est **`vitest.setup.ts`**. Ça fonctionne par résolution Vite. Ne pas s'y fier pour localiser du code.

### État actuel — les cinq clés de stockage

| Clé | Où | Sort en 2.7 |
|---|---|---|
| `escrow_token` | `client.ts:5` (déf.), lu `:20`, `:62` ; `auth.ts:51`, écrit `:64-65` | **→ substrat commutable** |
| `escrow_user` | `auth.ts:18` (déf.), lu `:32`, écrit `:67-68` | **→ substrat commutable** |
| `escrow_last_user` | `session.ts:57` (déf. **non exportée**), `:184`, `:266`, `:300-301` | reste en `localStorage` (marqueur d'appareil) ; **exporter la constante** |
| `escrow_locale` | `i18n/index.ts:39` | **NE PAS PURGER** (décision 2.1, verrouillée par test) |
| `escrow_offline_queue` | `offlineQueue.idb.ts:19` | migration one-shot, hors périmètre |

**Aucun `sessionStorage` n'existe nulle part aujourd'hui.** Aucun `localStorage.clear()` en production — la purge est toujours **par clés nommées** (invariant `session.ts:194`). Ne pas régresser.

### L'ordre de `endSession` — et pourquoi son test est fragile

Ordre actuel (`session.ts:100-213`), **prouvé par `session.spec.ts:243-293`** :
1. lire `userId` et `revokedToken` **avant** toute purge (`:121-122`)
2. `auth.clearSession()` (`:134`) → 3. `escrow.$reset()` (`:143`) → 4. `evidence.$reset()` (`:148`) → 5. `queue.clearMemory()` (`:153`)
6. *si `expired` → **return** ici (`:158`)*
7. `await idb.clearForUser(userId)` (`:170`) → 8. `await purgeReadCache()` (`:175`) → 9. `removeItem('escrow_last_user')` (`:184`)
10. **et seulement alors** `await auth.revokeOnServer(revokedToken)` (`:207`)

Chaque effet dans son propre `try/catch`. **`endSession` ne rejette jamais.**

Le test mocke `logoutUser` sur une promesse **qui ne se résout jamais** — seul moyen de distinguer les deux ordres — et observe sur `vi.waitFor`, pas sur un nombre fixe de macrotâches. Il assère **tous** les effets locaux pendant que la révocation est en vol.

> **C'est la troisième passe consécutive de la Story 1.9 où une moitié de la purge est restée derrière l'`await` sans que la suite le voie, chaque fois parce qu'elle ne contrôlait que la moitié déjà déplacée.** Puis la suite a viré au rouge parce que le correctif ajoutait deux `await` réels et que le test n'en drainait qu'un. *« Le correctif de code était bon, sa preuve ne l'était plus. »*

### NEVER — contraintes dures héritées

- **Ne pas importer le routeur ni un store dans `api/client.ts`** — cycle `router → stores/auth → api/auth → api/client` (documenté `client.ts:99-102`). Le pattern est : `client.ts` n'émet qu'un `window.dispatchEvent`, `session.ts` possède le teardown.
- **Ne pas remplacer `logoutUser` par axios** (spec 1.9:47, décision de la revue 1.6).
- **Ne pas toucher le `403` nu de Spring Security.** Le discriminant « session morte » est **exactement** `code == null && (status === 401 || status === 403)`, défini **une seule fois** dans `utils/replayFailure.ts`. Un 403 **codé** est un verdict métier, un 401 **codé** `AUTH_FAILED` un échec de connexion : **ni l'un ni l'autre ne déconnecte**. La Story 2.5 produira des « 403 uniformisés » applicatifs — la distinction doit être figée avant.
- **Ne pas modifier `vitest.setup.ts` entre les lignes 17 et 31.** L'ordre échange de `globalThis.Blob`/`File` depuis `node:buffer` **puis** import **dynamique** d'`undici` est *load-bearing* : un import statique serait hoisté au-dessus de l'échange et capturerait le Blob de jsdom. Insérer après la ligne 32 si nécessaire.
- **Ne pas ajouter d'entrée à la liste `PENDING_MIGRATION`** (`i18n/__tests__/noHardcodedStrings.spec.ts`). ⚠️ **`stores/auth` et `stores/escrow` y figurent et sont tous deux réécrits par cette story.** Retirer une entrée fait ROUGIR la suite tant que le module n'est pas migré.
- **Ne pas restaurer un fichier par `git checkout --`** s'il porte du travail non commité (un correctif de `StateBadge` a été effacé ainsi en 2-1).

### Conventions UI héritées (2-1 / 2-2 / 2-3)

- **Aucune chaîne littérale** — la garde scanne gabarits, script **et** modules ; simples, doubles **et backticks**.
- **Aucune clé brute rendue** — passer par `@/i18n/labels` ; **`$t(null)` LÈVE**.
- **Aucune ombre hors `shadow-floating`** — la garde vise la classe **et** la propriété CSS (`boxShadow` en ligne compris).
- **Classes Tailwind en toutes lettres, jamais composées** — l'extraction est statique ; une classe construite à l'exécution n'existe pas et **le rendu est muet, sans erreur**.
- **`type="button"`** sur tout `<button>` qui n'est pas un envoi de formulaire.
- Marges de contenu : `p-4 lg:p-6` (décision 2-3).
- Tokens : `surface-card`, `primary`/`primary-hover`/`primary-foreground`, `danger`, `--radius`, `--shadow-floating`.

### Modes d'échec récurrents de ce projet — ce que cette story va rencontrer

1. **Un test vert n'est pas une preuve ; seule la mutation l'est.** Cinq gardes du projet se sont révélées creuses : sentinelle vivant dans un commentaire supprimé par la minification, garde d'élévation aveugle à la propriété CSS, drapeau `hydrated` asserté sur un store neuf où il valait déjà `false`, assertion sur un `data-testid` inexistant, assertion posée sur un chemin `break`. **Chaque fois, seule la mutation l'a montré.**
2. **Un composant testé isolément n'est pas un composant branché.** `ClientShell` avait 7 tests verts et **n'était monté par aucune route**. Symétriquement : `endSession` et `installSessionExpiryListener` sont testés comme **fonctions**, jamais comme **câblage**.
3. **Un drapeau sentinelle a deux états de départ.** Un test qui part de l'état neutre ne distingue rien. Applicable au drapeau d'inactivité et à l'époque.
4. **Un `try/catch` ne garde que contre ce qui lève.** `Intl.format(NaN)` rend « $NaN », `new Date('x').toLocaleString()` rend « Invalid Date » — aucun ne lève.
5. **Un objet littéral hérite d'`Object.prototype`.** `spaceForRole('constructor')` rendait la fonction `Object` et `?? null` ne rattrapait rien → **enfermement hors de `/auth`**. Corrigé par `Object.hasOwn`. Même vigilance sur toute table de correspondance ajoutée ici.
6. **Un octet brut dans un littéral rend un fichier invisible à `grep` ET à `git`.** `AuthView.vue` est devenu binaire en 1.9 (classe de caractères de contrôle écrite avec les octets qu'elle matche) et **échappait au grep de vérification de sa propre story**. Un en-tête magique s'écrit en tableau d'octets explicite.
7. **Une justification périmée reconduit un défaut pour toujours** — `DashboardView.vue:53-63` en porte une aujourd'hui.
8. **Une AC absolue est intenable et sera relevée en revue.** Écrire l'écart plutôt que le cocher.

### Prévoir la revue comme la moitié du travail

Bilan des stories antérieures : **1.9 → 3 passes, 29 correctifs (5 hauts)** ; 2-1 → 3 passes ; 2-2 → 2 campagnes ; 2-3 → 3 couches. Constat reconduit sur trois stories : **haiku produit du volume, pas de la vérification** (2-3 : 2 pistes tenables sur 15 ; les deux premières couches citaient des lignes **du patch**, pas des fichiers). Les défauts de cette story demanderont d'**exécuter** — prévoir sonnet et des mutations en worktree jetable.

### Project Structure Notes

Fichiers **modifiés** : `stores/session.ts`, `stores/auth.ts`, `stores/escrow.ts`, `stores/evidence.ts`, `api/client.ts`, `api/auth.ts`, `main.ts`, `views/AuthView.vue`, `views/DashboardView.vue`, `layouts/ClientShell.vue`, `layouts/DesktopShell.vue`, `i18n/en.json`, `i18n/fr.json`.
Fichiers **nouveaux** : module de substrat de stockage, module de propagation inter-onglets, module d'inactivité (emplacement à l'appréciation du dev, sous `src/utils/` ou `src/stores/` selon qu'il porte de l'état).
Tests **nouveaux** : test de composant du bouton de déconnexion, test de câblage dans `appShell.spec.ts`, suites d'inactivité / inter-onglets / époque.

**Recouvrement avec la Story 2-4 (en pause à T6) :** `api/client.ts`, `stores/auth.ts`, `stores/session.ts`, `views/AuthView.vue`, `views/VerifyEmailView.vue`. C'est le motif du réordonnancement du 2026-08-10 — T6/T8 de la 2-4 s'écriront **après**, une seule fois, contre la politique livrée ici.

### References

- [Source: `epics.md#Story 2.7`] lignes 668-711 — les 7 AC
- [Source: `deferred-work.md:353-404`] — les 9 entrées de l'ex-bundle SESSION-PARTAGÉE (E1, E5, E6, E7, E8 ici ; E2, E9 → 11-3 ; E4 → Epic 9 ; E3 ici)
- [Source: `spec-1-9-hygiene-session-appareil-partage.md`] — antécédent direct : 29 correctifs, `:26-47` contraintes NEVER, `:150` mode inconnu silencieux, `:170` mutation sur chemin `break`, `:189` 403 nu délibéré
- [Source: `2-3-layout-trois-espaces-navigation.md:189-216`] — les trois défauts trouvés par ses propres tests ; `:291-352` revue
- [Source: `2-1-fondation-ui-tokens-design-i18n.md:210-214`] — section écrite **pour cette story** : persistance de la langue à travers la déconnexion
- [Source: `project-context.md:99-102`] — règle de vérification par mutation ; `:77` — 403 nu vs 403 codé
- [Source: `ARCHITECTURE-SPINE.md`] — AD-9, AD-10, AD-18, AD-20, AD-21, AD-23, AD-27, AD-30 ; conventions « Sécurité & session » et « Frontend »
- [Source: `epics.md#UX Design Requirements`] — UX-DR13, UX-DR20, UX-DR21, UX-DR26, UX-DR28, UX-DR32, UX-DR35, UX-DR37, UX-DR38, UX-DR39, UX-DR40, UX-DR43, UX-DR45, UX-DR46
- [Source: `epics-production.md:72`] — texte canonique de NFR-P8 (**absent du PRD**, qui délègue en `prd.md:178`)
- [Source: `sprint-status.yaml:198-217`] — décision de séquencement du 2026-08-10

---

## Décisions d'Oscard — 2026-08-10 (les trois questions sont closes)

**Q1 → DÉLAI D'INACTIVITÉ = 15 MINUTES.** Valeur normative de cette story. Elle n'existait dans aucun artefact amont ; elle vit désormais ici et doit être **une constante nommée et exportée**, pas un littéral dispersé — un seuil recopié à deux endroits diverge au premier ajustement.

**Q2 → CONFIRMÉ : l'expiration d'inactivité NE PURGE PAS la file offline.** La décision D-A tient telle qu'écrite : sémantique `expired`, mode nommé `'idle'`. L'hygiène d'appareil partagé vient de la mort du jeton et du scoping par utilisateur, pas de la destruction du travail non envoyé (AD-9, UX-DR32).

**Q3 → TTL serveur de 24 h HORS PÉRIMÈTRE.** `application.yml:77` conserve `ttl-seconds: 86400`. Aucune AC ne le touche. Conséquence à ne pas maquiller : **un jeton dérobé reste valide 24 h côté serveur** — la politique client réduit la surface d'exposition sur l'appareil, elle ne raccourcit pas la vie du jeton. À porter au ledger à la clôture de la story, comme dette nommée.

---

## Change Log

> **Créé rétroactivement le 2026-08-11**, à la demande du PO. La définition de terminé du workflow exige cette section ; elle manquait depuis l'ouverture de la story, et les sept premières tâches ont donc été reconstituées depuis `git log --oneline`. Une ligne par tâche livrée ; les commits qui ne livrent pas de tâche (décisions de spécification, entrées de registre, suivi de sprint) sont rattachés à la tâche qu'ils servent plutôt que d'occuper une ligne à eux.

| Date | Tâche | Résumé | Commit |
|---|---|---|---|
| 2026-08-10 | T0 | Lecture préalable des cinq fichiers bloquants (`session.ts`, `client.ts`, `auth.ts`, le test d'ordonnancement, `i18n/index.ts`). Aucun artefact de code : la tâche est une condition d'entrée, consignée avec T1. Contexte et décisions Q1/Q2/Q3 tranchés en amont. | `c406304` |
| 2026-08-10 | T1 | Substrat de stockage commutable (`utils/credentialStorage.ts`) : les 5 accès directs à `localStorage` migrés, `LAST_USER_STORAGE_KEY` exportée, `concernsCurrentSession` suivie. | `8ba7cb9` |
| 2026-08-10 | T2 | Case « rester connecté » dans `AuthView`, décochée par défaut, écrite AVANT `auth.login` — l'ordre est load-bearing. | `e9762f6` |
| 2026-08-10 | T3 | Expiration d'inactivité : `utils/idleTimeout.ts`, `IDLE_TIMEOUT_MINUTES = 15`, minuterie **et** contrôle au démarrage, requêtes en vol comptées comme activité, mode `'idle'`, motif persisté. Deux dettes portées au registre (`ebf2ef2`). | `31b9749` |
| 2026-08-11 | T4 | Propagation inter-onglets : `utils/sessionBroadcast.ts`, `BroadcastChannel('escrow-session')`, règle d'autorité (émetteur unique de la révocation), repli silencieux. | `19fd712` |
| 2026-08-11 | T4-bis | Veto du récepteur sur `'idle'` (décision D-F, `46969ee`) : un onglet qui n'est pas lui-même inactif ignore l'annonce. Veto borné à `'idle'`. | `44a40b2` |
| 2026-08-11 | T5 | Attente bornée sur la révocation : `REVOCATION_WAIT_SECONDS = 3`, `withBudget` sur le seul `await`. `api/auth.ts` inchangé (NEVER 1.9) et désormais asservi. Attente dite à l'écran. | `5e1fdb2` |
| 2026-08-11 | T6 | Les trois états du jeton (AC5) : `SessionState`, getter `sessionState`, la file hors-ligne ne confond plus « jeton + profil illisible » avec « personne n'est connecté ». Jeton **non** effacé, motif écrit. | `dd66226` |
| 2026-08-11 | T7 | Époque de session (AC6) : compteur monotone au module, tourné aux deux bouts, comparé à la résolution des trois actions de lecture. `loadSeq` conservé et enfin testé. Écart service worker (D-E) nommé. | `9ee4de0` |
| 2026-08-11 | T8 | Bouton de déconnexion extrait en `components/LogoutButton.vue` et placé par `ClientShell` **et** `DesktopShell` — ARBITRATOR et ADMIN avaient jusqu'ici zéro sortie par l'IHM (D-C). Tokenisé via `AppButton`, à qui la tâche a révélé **deux manques** : aucun anneau de focus, aucune interpolation de libellé. Les deux réparés dans `AppButton` (additif), pas contournés dans l'appelant. Filet de navigation enfin couvert. 530 tests (527 → +3). | _à venir_ |

## Dev Agent Record

### Agent Model Used

claude-opus-5 (session interactive, workflow bmad-dev-story)

### Debug Log References

**T1 — mutations de vérification (2026-08-10), obligation AC7 :**

| Mutation | Garde visée | Résultat |
|---|---|---|
| `readCredential` retombe sur le substrat inactif quand le substrat actif est vide | l'AC1 (« aucune session n'est restaurée ») — un repli ressusciterait la session que la fermeture d'onglet doit tuer | 🔴 `ne lit QUE le substrat actif` **et lui seul** (1 échec / 11 verts) |
| `removeCredential` ne purge que le substrat actif au lieu des deux | l'hygiène de purge — un identifiant abandonné dans le substrat inactif reste un identifiant sur l'appareil | 🔴 `retire la clé des DEUX substrats` **et lui seul** (1 échec / 11 verts) |

**T2 — mutation de vérification :** `writePersistence` déplacée APRÈS `auth.login` au lieu d'avant → **3 tests rouges sur 6**, dont celui qui porte nommément l'ordre. Restauré, 6/6.

Restauration vérifiée après chaque mutation : 12/12 verts. Fichier sauvegardé hors dépôt puis réécrit — **jamais** de `git checkout --` (leçon Story 2.1, un correctif détruit par ce geste).

**T3 — mutations de vérification (2026-08-10), obligation AC7.** Dix mutations, chacune la plus proche possible du défaut d'origine ; pour chacune, la suite ENTIÈRE relancée et vérification que ce sont bien les tests visés qui rougissent. Base : 478 verts.

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `void enforceIdlePolicy()` retiré de `main.ts` | le CÂBLAGE du contrôle au démarrage — la fonction reste prouvée, personne ne l'appelle | 🔴 **1 seul** rouge : `un onglet rouvert APRÈS le délai ne restaure aucune session` (477 verts) |
| 2 | `isIdleExpired` compare à `IDLE_TIMEOUT_MS * 2` | le seuil lui-même. Muter la CONSTANTE serait indétectable — les tests la lisent ; c'est la COMPARAISON qu'il faut muter | 🔴 5 rouges, tous sur le seuil et le démarrage (469 verts) |
| 2b | `IDLE_TIMEOUT_MINUTES = 60` | la valeur tranchée par le PO, que les tests lisant la constante ne peuvent pas garder | 🔴 **1 seul** rouge : `vaut 15 minutes, et les millisecondes en DÉRIVENT` |
| 3 | garde `if (inFlight > 0)` retirée de `tick()` | 🔴 le scénario du versement de 10 Mo | 🔴 `un versement en vol tient la session vivante bien au-delà du délai` |
| 3b | idem, après durcissement du test voisin | — | 🔴 **2** rouges (voir Completion Notes : la mutation a révélé une assertion CREUSE dans mon propre test) |
| 4 | `installIdleTimeout(router)` retiré de `main.ts` | le câblage de la MINUTERIE, distinct de celui du démarrage | 🔴 **1 seul** rouge : `la minuterie est branchée elle aussi` |
| 5 | `'idle'` retiré de `KNOWN_REASONS` | le piège de la revue 1.9 : un mode nouveau traité comme inconnu | 🔴 **1 seul** rouge : `« idle » est une raison RECONNUE` |
| 6 | `explicit = reason === 'logout' \|\| reason === 'idle'` | **décision D-A / Q2** : l'inactivité ne purge NI la file hors-ligne NI le cache de lecture | 🔴 6 rouges, dont `LAISSE la file hors-ligne et le cache` |
| 7 | `forgetActivity()` retiré d'`endSession` | l'horodatage ne doit pas survivre à la session qu'il décrit | 🔴 **1 seul** rouge : `efface l'horodatage d'inactivité` |
| 8 | écriture du motif retirée d'`endSession` | AC2 « avec un motif affiché » + UX-DR28 | 🔴 5 rouges, sur la fin de session ET sur l'écran |
| 9 | `noteRequestStarted()` retiré de l'intercepteur de requête | le CÂBLAGE du compteur : `utils/idleTimeout` reste vert sans lui | 🔴 2 rouges dans `client.spec.ts` |
| 10 | `noteRequestSettled()` retiré de la branche d'ÉCHEC | hors ligne toutes les requêtes échouent : le compteur ne redescendrait jamais et la session ne pourrait plus jamais expirer | 🔴 **1 seul** rouge : `décompte aussi une requête qui ÉCHOUE` |

Restauration après chaque mutation : fichier sauvegardé **hors du dépôt** puis réécrit, `diff` vérifié vide, suite relancée. **Jamais** de `git checkout --`. État final : **478/478 verts**.

**T4 — mutations de vérification (2026-08-11), obligation AC7.** Neuf mutations, chacune la plus proche possible du défaut d'origine ; pour chacune, la suite ENTIÈRE relancée et vérification que ce sont bien les tests visés qui rougissent. Base : 490 verts.

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `publishSessionEnd(reason)` retiré d'`endSession` | l'ÉMISSION — sans elle, aucun autre onglet n'apprend jamais rien | 🔴 3 rouges, tous sur une émission attendue (487 verts) |
| 2 | `installSessionBroadcastListener(router)` retiré de `main.ts` | le CÂBLAGE : la fonction reste prouvée, personne ne l'appelle | 🔴 **les 2** tests de `mainSessionBroadcast.spec.ts`, et eux seuls (488 verts) |
| 3 | `if (!fromAnotherTab)` retiré devant `publishSessionEnd` | la NON-RÉ-ÉMISSION : A annonce à B, B annonce à A, sans fin | 🔴 **1 seul** rouge : `le récepteur ne RÉ-ÉMET pas` |
| 4 | `explicit = reason === 'logout'` (règle d'autorité retirée) | **NFR-P2** : N onglets = N révocations sur `/auth/*`, rate-limité | 🔴 **2** rouges : `n'appelle AUCUN endpoint` + `ne retouche pas l'état PARTAGÉ` |
| 5 | `if (!useAuthStore().isAuthenticated) return` retiré du récepteur | l'onglet déjà déconnecté ne doit pas naviguer ni écrire de motif | 🔴 **1 seul** rouge : `un onglet qui n'a rien à terminer ne navigue pas` |
| 6 | `if (message.type !== SESSION_END) return` retiré | le DISCRIMINANT : sans lui, tout message sur le canal déconnecte | 🔴 **1 seul** rouge : `un message étranger ne détruit aucune session` |
| 7 | garde `typeof` **ET** `try/catch` retirés d'`openChannel` | le REPLI SILENCIEUX quand `BroadcastChannel` manque | 🔴 **1 seul** rouge : `la propagation dégrade, la déconnexion aboutit` |
| 7a | garde `typeof` **seule** retirée | — | 🟢 **490 verts** — voir Completion Notes : le repli est DOUBLEMENT implémenté, aucune mutation d'une seule ligne ne le détecte |
| 8 | `endSession({ source: 'another-tab' })` — la raison n'est plus transmise | contrainte « le message porte la raison, pour le bon motif » | 🔴 **2** rouges : `le MOTIF voyage` + `une annonce sans raison lisible` |

Restauration après chaque mutation : les trois fichiers de production sauvegardés **hors du dépôt** puis réécrits, `diff` vérifié vide avant chaque nouvelle mutation et à la fin. **Jamais** de `git checkout --`. État final : **490/490 verts**, confirmé sur **10 exécutions complètes consécutives**.

**T4-bis — veto du récepteur (2026-08-11), obligation AC7.** Quatre mutations sur la seule ligne livrée. Base : 494 verts.

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `if (reason === 'idle' && !isIdleExpired()) return` **retiré** | le veto lui-même — le défaut d'origine, à la ligne près | 🔴 **1 seul** rouge : `un onglet ACTIF ignore l'annonce d'inactivité` (493 verts) |
| 2 | `if (!isIdleExpired()) return` — le veto s'applique à **toutes** les raisons | **la LIMITATION à `'idle'`** : un onglet actif qui vétoerait un `'logout'` resterait authentifié sur un appareil rendu | 🔴 **10** rouges, tous sur un récepteur qui doit honorer un message (dont les **2** de `mainSessionBroadcast.spec.ts`) |
| 2b | veto étendu à `'logout'` **seulement** — la mutation la plus proche du défaut de sécurité | idem, isolée | 🔴 **9** rouges ; `un 403 nu (« expired ») traverse le veto` redevient **vert**, ce qui montre que les deux raisons sont gardées séparément |
| 3 | comparaison **inversée** : `reason === 'idle' && isIdleExpired()` | le SENS de la lecture — un veto qui protégerait l'onglet inactif et sacrifierait l'actif | 🔴 **4** rouges, tous sur le chemin `'idle'`, dont `un onglet lui-même INACTIF meurt` (NFR-P8) |

Restauration : `session.ts` sauvegardé hors dépôt puis réécrit, `diff` vérifié **vide** après chaque mutation. **Jamais** de `git checkout --`. État final : **494/494 verts**.

**T5 — mutations de vérification (2026-08-11), obligation AC7.** Sept mutations sur trois fichiers de production. Base : 505 verts.

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `await auth.revokeOnServer(revokedToken)` — plafond retiré | **la borne elle-même**, à la ligne près du défaut d'origine | 🔴 **2** rouges, tous deux dans `sessionRevocationBudget.spec.ts` (503 verts) |
| 2 | `REVOCATION_WAIT_SECONDS = 0` — le plafond gagne toujours | la borne est un **PLAFOND, pas un délai** : abandonner d'emblée supprimerait la garantie de la revue 1.6 | 🔴 **3** rouges, dont `attend pour de bon quand le réseau répond` |
| 3 | révocation déplacée AVANT les purges persistées | **l'ordre purge-avant-réseau**, cassé deux fois en Story 1.9 | 🔴 `empties the device BEFORE waiting on the network, not after it` **rougit toujours** + 2 rouges de la borne |
| 4 | `signal: AbortSignal.timeout(3000)` ajouté au `fetch` | **le NEVER de la spec 1.9** : borner l'ATTENTE, jamais la requête — un abandon laisserait le jeton vivant 24 h côté serveur | 🔴 **1 seul** rouge : `porte keepalive et AUCUN moyen d'interrompre la requête` |
| 4b | `keepalive: true` retiré | la garantie qui rend l'abandon de l'attente acceptable | 🔴 **1 seul** rouge : le même |
| 5 | libellé d'attente retiré du gabarit (`common.logout` en toutes circonstances) | **UX-DR26** : motif ET délai annoncé | 🔴 **2** rouges, EN et FR |
| 6 | `:disabled="signingOut"` retiré du bouton | la neutralisation du bouton pendant l'attente — la seule chose qui empêche une seconde terminaison | 🔴 **2** rouges, dont `n'a lancé qu'UNE terminaison` |

Restauration après chaque mutation : `session.ts`, `api/auth.ts` et `DashboardView.vue` sauvegardés **hors du dépôt** puis réécrits, `diff` vérifié **vide** avant chaque nouvelle mutation et à la fin. **Jamais** de `git checkout --`. État final : **505/505 verts**.

**T6 — mutations de vérification (2026-08-11), obligation AC7.** Quatre mutations sur deux fichiers de production, plus **deux REMUTATIONS de gardes voisines déjà prouvées** (leçon de T5 : une garde neuve placée devant une garde ancienne peut la creuser). Base : 512 verts.

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | branche `if (session === 'incoherent') return` retirée d'`init()` | le MENSONGE `hydrated = true` — `RecoveryView` annonçant « rien à récupérer » sur une file que personne n'a ouverte | 🔴 **1 seul** rouge : `ne prétend PAS avoir lu la file quand la session est incohérente` (511 verts) |
| 2 | garde d'`enqueue()` retirée | l'entrée ORPHELINE — `meta.userId: undefined`, invisible à `getAllForUser`, supprimée à la déconnexion suivante | 🔴 **2** rouges : `refuse de mettre en file` + `ne laisse aucune entrée orpheline naître d'une création hors ligne` |
| 3 | `sessionState` rend `'anonymous'` quand le profil est illisible — **la lecture à deux états, à la source** | le défaut d'origine, à la ligne près | 🔴 **les 7** tests de `sessionState.spec.ts`, et eux seuls (505 verts = la base d'avant T6) |
| 4 | `sessionState` ne lit plus l'identifiant (`state.user == null` au lieu de `state.user?.id == null`) | le cas du profil PARSABLE mais sans `id` — celui qui produit le même dégât en aval sans échouer à `JSON.parse` | 🔴 **2** rouges, tous deux sur ce cas |
| N1 | **remutation voisine** — `this.hydrated = true` retiré de la branche `'anonymous'` d'`init()`, désormais placée APRÈS la branche incohérente | la garde de la Story 1.9 : un démarrage sans session a bel et bien terminé sa lecture | 🔴 `leaves hydrated false when adoptSession() cannot read the queue` **rougit toujours** (+ la positive appariée de ma propre suite) |
| N2 | **remutation voisine** — reprise de la carte optimiste retirée de `createNewTransaction` | la garde de la Story 4.x que ma garde d'`enqueue` précède désormais dans le même `try` | 🔴 `takes the optimistic card back when the entry could not be persisted` **rougit toujours** (+ la mienne) |

Les deux remutations confirment que la garde neuve ne creuse aucune des deux : elle ne se déclenche pas dans l'état `'anonymous'`, qui est celui de ces tests-là.

Restauration après chaque mutation : `stores/auth.ts`, `stores/offlineQueue.ts` et `stores/escrow.ts` sauvegardés **hors du dépôt** puis réécrits, `diff` vérifié **vide**. **Jamais** de `git checkout --`. État final : **512/512 verts**.

**T7 — mutations de vérification (2026-08-11), obligation AC7.** Huit mutations sur trois fichiers de production, plus **trois REMUTATIONS de gardes voisines déjà prouvées**. Base : 527 verts (après les deux tests ajoutés par la passe elle-même, voir ci-dessous).

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `turnSessionEpoch()` retiré d'`endSession` | le tour de FIN de session | 🔴 **3** rouges, dont `ne laisse pas la réponse de A repeupler le store APRÈS sa déconnexion` |
| 2 | `turnSessionEpoch()` retiré de `beginSession` | le tour de DÉBUT — le seul qui attrape une session commencée SANS qu'aucune ne se termine (branche « session incohérente » du routeur, `auth.verify`) | 🔴 **3** rouges, dont `rejette la LISTE de A quand B se connecte SANS qu'aucune session ne se soit terminée` |
| 3 | `turnSessionEpoch()` fait `sessionEpoch = 0` | **la MONOTONIE** — le rembobinage de `loadSeq` reproduit dans son propre correctif | 🔴 **11** rouges, la quasi-totalité de T7 (voir Completion Notes pour le seul survivant, et pourquoi) |
| 4 | garde d'époque retirée de la branche SUCCÈS de `loadTransactions` | le défaut d'origine, à la ligne près (`this.transactions = await fetchTransactions()`) | 🔴 **3** rouges, tous sur la liste |
| 5 | idem, branche SUCCÈS de `loadTransactionDetail` | le second point d'écriture d'escrow | 🔴 **1 seul** rouge : `rejette le DÉTAIL de A` |
| 6 | garde retirée de la branche ÉCHEC des lectures d'escrow | le message d'erreur d'une requête qui n'est pas la sienne | 🔴 **1 seul** rouge : `n'affiche pas à B le message d'erreur d'une lecture émise par A` |
| 7 | garde retirée du `finally` (`this.loading = false` inconditionnel) | le voile de chargement de B éteint par la réponse de A | 🔴 **1 seul** rouge : `ne laisse pas la réponse de A éteindre le drapeau de chargement de B` |
| 8 | garde d'époque retirée de `loadEvidence`, **`loadSeq` laissé en place** | **LA mutation la plus proche du défaut** : c'est exactement l'état d'avant T7 | 🔴 **1 seul** rouge : `rejette une réponse de A dont le seq COÏNCIDE avec la première lecture de B` — la preuve que `loadSeq` seul ne l'attrape pas |
| N1 | **remutation voisine** — garde `loadSeq` retirée de `loadEvidence`, devant laquelle ma garde d'époque est désormais placée | les courses INTRA-session de l'Epic 4 | 🔴 **2** rouges : lecture la plus récente + invalidation par le retrait |
| N2 | **remutation voisine** — révocation serveur déplacée DEVANT les purges persistées d'`endSession`, fonction que T7 modifie | la garde de la Story 1.9 / T5, cassée deux fois en 1.9 | 🔴 `empties the device BEFORE waiting on the network, not after it` **rougit toujours** (+ 3 rouges de la borne) |
| N3 | **remutation voisine** — `forgetActivity()` retiré d'`endSession` | la garde de T3 (mutation 7), dans la même fonction | 🔴 **1 seul** rouge : `efface l'horodatage d'inactivité` — **inchangé** |

Restauration après chaque mutation : `stores/session.ts`, `stores/escrow.ts` et `stores/evidence.ts` sauvegardés **hors du dépôt** puis réécrits, `diff` vérifié **vide** avant chaque nouvelle mutation et à la fin. **Jamais** de `git checkout --`. État final : **527/527 verts**.

**T8 — mutations de vérification (2026-08-11), obligation AC7.** Trois mutations sur `components/LogoutButton.vue`. Base : 530 verts (527 + les 3 tests de filet ajoutés par la tâche).

| # | Mutation | Garde visée | Résultat |
|---|---|---|---|
| 1 | `window.location.assign('/auth')` retiré du `catch` | le FILET lui-même — jusqu'ici entièrement mort pour la suite | 🔴 **3** rouges, tous du nouveau bloc (527 verts) |
| 2 | `signingOut.value = false` retiré du `finally` | le bouton laissé figé sur « déconnexion en cours » sur le chemin du filet, où le composant n'est **pas** démonté | 🔴 **1 seul** rouge : `ne laisse pas le bouton figé` (529 verts) |
| 3 | `endSession` déplacée APRÈS la navigation | l'ORDRE : purger avant de recharger, sans quoi le rechargement ramène l'application sur un appareil encore porteur du jeton du partant | ⚠️ **0 rouge au premier essai** — le test était CREUX. Après redressement de son oracle : 🔴 **1 seul** rouge, `la session est détruite AVANT le filet` |

La mutation 3 est la trouvaille de la tâche et elle vaut d'être lue en entier dans les Completion Notes : un test peut asserter les bons faits sur le mauvais INSTANT et rester vert quoi qu'il arrive.

Restauration : `LogoutButton.vue` réécrit par substitution ciblée après chaque mutation, `git diff` vérifié **vide** avant chaque nouvelle. **Jamais** de `git checkout --`. État final : **530/530 verts**, `vue-tsc` 0 diagnostic, `eslint --max-warnings 0` propre, garde d'encodage verte (1984 fichiers).

### Completion Notes List

**T0 — Lire avant d'écrire.** Les cinq fichiers du préalable lus intégralement. Deux avertissements « Story 2.7 réécrira ce fichier » trouvés en place (`session.ts:198-199`, `i18n/index.ts:36-38`) : la décision qu'ils protègent — `escrow_locale` n'est pas une donnée de session — est **conservée**, aucune clé de langue n'entre dans la purge.

**T1 — Substrat de stockage commutable.** Nouveau module `utils/credentialStorage.ts`, seul point d'accès aux identifiants. Les **cinq** accès directs à `localStorage` recensés par la story sont migrés : `client.ts:20` (intercepteur de requête), `client.ts:62` (`concernsCurrentSession`), `auth.ts:32` (`loadStoredUser`), `auth.ts:51` (state du store), `auth.ts:64-68` (`persist`). Aucun accès direct ne subsiste sur ces clés.

Choix de conception non dictés par la story, et leurs motifs :
- **Le profil suit le jeton dans le même substrat.** Laisser `escrow_user` en `localStorage` pendant que le jeton meurt avec l'onglet fabriquerait l'état « incohérent » de l'AC5 **à chaque fermeture d'onglet** — un profil sans jeton — au lieu d'en faire un cas rare.
- **La préférence vit en `localStorage`, obligatoirement.** En `sessionStorage` elle mourrait avec l'onglet : l'option « rester connecté » serait vraie pendant la session et fausse après. Elle ne porte aucune donnée personnelle et n'entre donc pas dans la purge.
- **`writeCredential` vide l'autre substrat.** Sans ce second geste, un utilisateur ayant coché « rester connecté » puis un suivant ne l'ayant pas coché laisseraient un jeton abandonné, redevenu lisible à la première bascule de préférence.
- **`removeCredential` purge les deux substrats**, délibérément asymétrique avec la lecture : elle s'exécute quand on ne veut plus rien laisser derrière soi.
- **Valeur de préférence non reconnue → `session`.** La direction sûre est la persistance la plus courte.

`LAST_USER_STORAGE_KEY` **exportée** (elle était `const` privée) et le littéral dupliqué de `session.spec.ts:55` remplacé par l'import : un renommage laissait ces assertions vertes en interrogeant une clé que plus personne n'écrit.

**Trois suites ont dû être corrigées, et c'est le harnais qui avait tort, pas la production :**
- `client.spec.ts` posait la session par `localStorage.setItem` en direct — un test qui écrit dans un substrat que la production n'interroge plus vérifie une mécanique qui n'existe pas. Migré vers `writeCredential`/`readCredential`.
- `session.spec.ts` et `escrow.offline.spec.ts` faisaient `localStorage.clear()` en `beforeEach`. Ce geste ne vide plus le substrat des identifiants : deux tests « personne n'est connectée » trouvaient l'utilisateur du test précédent. `sessionStorage.clear()` ajouté à côté.

**T2 — Case « rester connecté ».** Ajoutée au formulaire de connexion, **décochée par défaut**, initialisée depuis la préférence déjà enregistrée. Clés i18n EN+FR. Cible tactile 44 px sur le libellé entier (UX-DR38).

Deux choix et leurs motifs :
- **Hors de `form`.** `form` est la charge envoyée au serveur ; ceci est une préférence d'appareil qui ne quitte jamais le navigateur. Les mélanger finirait par l'expédier dans un corps de requête.
- **Absente en mode inscription.** L'inscription n'ouvre pas de session (Story 2.4 : c'est `verify` qui le fait) ; offrir le choix là promettrait un effet qui n'aurait pas lieu.

**L'ordre est load-bearing** : `writePersistence` s'exécute AVANT `auth.login`. Après, le jeton partirait dans l'ancien substrat puis on déclarerait l'autre actif — jeton illisible, session en apparence ouverte, premier appel API sans en-tête, **et rien ne lèverait**.

Nouvelle suite `views/__tests__/rememberMe.spec.ts` (6 tests) : elle assère le **substrat de destination du jeton**, pas la seule préférence enregistrée — une assertion sur la préférence passerait aussi bien si `login` écrivait toujours au même endroit. Un défaut du test lui-même a été trouvé et corrigé par le test : le helper ne cochait la case que pour `true` et ne la décochait jamais, si bien que le cas « décoché » sur un poste déjà en `local` vérifiait le contraire de son nom.

État : **445 tests verts** (427 au départ, +18), `vue-tsc --build` à 0 diagnostic, `npm run lint` propre, garde d'encodage verte.

**T3 — Expiration d'inactivité.** Nouveau module `utils/idleTimeout.ts`, `IDLE_TIMEOUT_MINUTES = 15` comme constante SOURCE dont `IDLE_TIMEOUT_MS` dérive. Les deux déclencheurs de l'AC2 sont livrés et **prouvés séparément** : `enforceIdlePolicy()` appelée depuis `main.ts` avant `app.mount()`, et `installIdleTimeout(router)` qui pose la minuterie.

Choix de conception non dictés par la story, et leurs motifs :

- **Le module vit dans `utils/`, pas dans `stores/`.** Il est importé par `api/client.ts`, à qui le cycle `router → stores/auth → api/auth → api/client` interdit d'importer un store ou le routeur. `utils/` est la seule direction que `client.ts` emprunte déjà. Le module ne connaît donc ni `endSession` ni le routeur : **il mesure et il prévient, `session.ts` décide** — le même partage que `client.ts` / `session.ts` autour de `escrow:session-expired`.
- **Les requêtes en vol sont comptées à l'ÉMISSION et décomptées au RÈGLEMENT, sur les deux branches.** Ne décompter que le succès aurait fait croître le compteur à chaque appel rejeté — hors ligne, c'est chaque appel — et **la session n'aurait plus jamais pu expirer**. Plancher à zéro sur le décompte : un compteur négatif rendrait `inFlight > 0` faux pendant les requêtes suivantes.
- **La minuterie recalcule TOUJOURS depuis l'horodatage persisté**, jamais depuis sa seule échéance : un onglet réveillé après une mise en veille reçoit son `setTimeout` en retard, et une conclusion tirée de l'échéance seule serait fausse dans les deux sens.
- **`startIdleWatch` n'horodate PAS à l'installation.** Sans cette précaution, l'ordre des deux lignes de `main.ts` devenait load-bearing : installée avant le contrôle au démarrage, la veille aurait rafraîchi l'horodatage que ce contrôle doit lire, et **l'onglet rouvert après une heure serait passé pour actif**. Une mesure qu'un réordonnancement innocent supprime n'est pas une mesure.
- **Événements d'entrée DISCRETS (`pointerdown`, `keydown`), `mousemove`/`scroll` exclus** — des dizaines d'écritures par seconde dans le stockage, et une souris bousculée n'est pas une intention. **L'écart est nommé** : quelqu'un qui lirait un écran quinze minutes sans toucher clavier ni pointeur verrait sa session expirer. C'est le comportement voulu d'une politique d'appareil partagé, et tout appel API déclenché par l'écran réarme de toute façon le compteur.
- **Horodatage ABSENT ≠ inactivité constatée.** `enforceIdlePolicy` horodate une session non horodatée au lieu de la terminer. La lecture inverse aurait déconnecté **tout le monde au déploiement** pour un fait que personne n'a établi. Écart nommé plutôt que coché : une session d'avant cette story bénéficie d'une seule fenêtre de 15 minutes, une seule fois. Horodatage **illisible** confondu avec absent, parce que `Number('n\'importe quoi')` ne lève pas — il rend `NaN`, qui se compare `false` à tout et aurait fait passer la session pour fraîche.
- **Le motif est PERSISTÉ, pas passé en paramètre de route.** Les deux déclencheurs n'arrivent pas par le même chemin : la minuterie navigue elle-même, le contrôle au démarrage s'exécute **avant le montage**, sur un onglet qui vient d'être rouvert — ni navigation à décorer, ni mémoire vive à consulter. Un paramètre d'URL n'aurait servi que le cas où l'utilisateur était déjà là pour le voir. Lecture **destructrice** dans `AuthView` : un motif qui survit à son affichage réapparaît après une déconnexion volontaire pour expliquer une expiration qui n'a pas eu lieu.
- **Le délai est INTERPOLÉ dans le message (`{minutes}`)**, jamais réécrit dans les deux catalogues : un « 15 » gravé en EN et en FR aurait survécu à l'ajustement du seuil et annoncé un délai que la minuterie n'applique plus.
- **`endSession` efface l'horodatage QUELLE QUE SOIT la raison.** Laissé derrière, il serait lu par le contrôle au démarrage de la session **suivante** comme la fraîcheur de celle-ci.
- **Le mode inconnu passe par un `Set<EndSessionReason>`** au lieu de conditions disséminées. La condition d'origine (`!explicit && reason !== 'expired'`) rouvrait le défaut de la revue 1.9 **par le bord opposé** : sans traitement, `'idle'` aurait produit la plainte « raison inconnue » à **chaque expiration normale** — un diagnostic faux à chaque déclenchement.
- **`sessionExpiryAnnounced` n'est jamais touché par la minuterie.** Seul `beginSession` l'abaisse, comme `client.ts` le documente. `beginSession` gagne un `markActivity()`, et le commentaire dit explicitement que les deux mécanismes sont distincts.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **⚠️ Le « canal d'annonces a11y centralisé » de T3 n'existe pas.** `grep -rn "aria-live" src` ne rend qu'une occurrence, locale à `VerifyEmailView.vue:148`. Aucun composant de région live partagé, aucun store d'annonces. J'ai donc repris **l'idiome existant** (`role="status"`, qui vaut `aria-live="polite"` implicite) plutôt que d'inventer le canal manquant — le créer aurait été une refonte a11y transverse hors du périmètre de T3, et l'instruction disait précisément de ne pas ouvrir un second canal. **À trancher hors de T3.**
2. **⚠️ La garde anti-chaîne-en-dur ne scanne plus les modules.** `noHardcodedStrings.spec.ts:236` globe `**/*.{vue,js}` : après la migration TypeScript, le volet « prose dans le code » **ne lit plus aucun `.ts`**, et les six entrées de `PENDING_MIGRATION` (`stores/auth.js`, `utils/replayFailure.js`…) ne désignent plus aucun fichier existant. Le volet gabarit, lui, fonctionne toujours. Aucune entrée ajoutée à `PENDING_MIGRATION` comme demandé ; **la garde elle-même est à réparer** (ajouter `ts` au motif et traiter ce qu'elle révélera) — hors périmètre T3, dette à porter au ledger.
3. **Une minuterie factice complète SUSPEND fake-indexeddb.** Deux tests ont expiré à 30 s sans diagnostic utile avant que la cause n'apparaisse : `vi.useFakeTimers()` fige aussi `setImmediate`/`setTimeout`, dont fake-indexeddb se sert pour ordonnancer ses transactions. Le patron retenu — et documenté dans les suites — est `toFake: ['Date']` quand seule l'horloge compte, `toFake: ['setTimeout','clearTimeout','Date']` quand il faut faire avancer l'échéance **et** lire IndexedDB, la panoplie complète seulement quand IndexedDB n'est pas touchée. `vi.advanceTimersByTimeAsync` remplace `vi.waitFor`, qui se bloque sous minuteries factices.
4. **La passe de mutation a trouvé une assertion creuse dans mon propre test.** `l'horodatage persisté reste frais pendant le transfert` restait **vert** avec la garde des requêtes en vol retirée : le chemin « budget consommé » horodate lui aussi avant de prévenir, si bien que l'horodatage seul ne distingue pas les deux chemins. Seule la **notification** les sépare ; le test l'assère désormais et rougit (mutation 3b). C'est exactement le mode d'échec n° 1 des Dev Notes, rencontré à la première occasion.
5. **Deux câblages étaient hors couverture et le sont maintenant.** `enforceIdlePolicy` et le compteur de requêtes étaient prouvés comme **fonctions** ; rien ne prouvait que `main.ts` appelle l'une ni que `client.ts` alimente l'autre. Deux suites de câblage ont été ajoutées (`src/__tests__/mainIdleStartup.spec.ts` amorce `main.ts` en vrai ; trois tests dans `client.spec.ts` pilotent la vraie instance axios) et les mutations 1, 4, 9 et 10 confirment qu'elles rougissent.

État T3 : **478 tests verts** (445 au départ, **+33**), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1975 fichiers, `npm run verify:no-demo` verte.

**T4 — Propagation inter-onglets.** Nouveau module `utils/sessionBroadcast.ts`, `BroadcastChannel('escrow-session')`, branché dans `main.ts` par `installSessionBroadcastListener(router)` à côté de l'écouteur d'expiration existant. `endSession` gagne un paramètre `source: 'this-tab' | 'another-tab'`.

Choix de conception non dictés par la story, et leurs motifs :

- **UN SEUL objet `BroadcastChannel` par onglet, partagé par l'émission et la réception.** La spécification garantit qu'un canal ne reçoit jamais SES PROPRES messages, mais elle ne garantit rien entre deux objets du même onglet : vérifié dans ce runtime, deux canaux ouverts côte à côte reçoivent tous deux le message d'un troisième. Avec deux instances, l'onglet émetteur s'entendrait lui-même, déclencherait sa propre terminaison en pleine terminaison, et le garde d'authentification ne serait plus qu'une question de calendrier. L'instance unique transforme la propriété « on ne s'écoute pas soi-même » en garantie de la plateforme au lieu d'une course gagnée.
- **`source` plutôt que trois drapeaux.** Un seul paramètre commande les TROIS conséquences de la règle d'autorité — ne pas ré-émettre, ne pas révoquer, ne pas retoucher l'état partagé de l'appareil — parce que ce sont trois faces d'un même fait : cet onglet n'est pas celui où l'action a eu lieu. Trois booléens auraient permis d'en oublier un, et le compilateur n'aurait rien dit.
- **Le récepteur exécute la sémantique CONSERVATRICE, quelle que soit la raison reçue.** `explicit = reason === 'logout' && !fromAnotherTab`. Ce qui reste au récepteur — les identifiants de SON onglet — est précisément ce que personne d'autre ne peut faire à sa place : par l'AC1 ils vivent en `sessionStorage`, cloisonné par onglet. Ce qu'il ne refait pas — IndexedDB, cache de lecture, marqueur d'appareil, révocation — est partagé ou distant, et l'émetteur vient de le traiter selon la raison. Rejouer coûterait N tours d'IndexedDB **concurrents** de ceux de l'émetteur, et N révocations sur `/auth/*` que **NFR-P2** limite en débit.
- **Émission TÔT, avant la moindre purge.** La révocation en fin d'`endSession` est un `fetch` sans délai d'expiration : sur un portail captif elle pend des minutes (c'est l'objet même de l'AC4/T5). Annoncer après aurait laissé les autres onglets afficher une interface authentifiée pendant toute cette attente — le défaut que l'AC3 ferme. Les terminaisons des différents onglets sont indépendantes et doivent courir en parallèle.
- **Un discriminant `type: 'session-end'` dans le message.** Le canal porte un nom générique ; une story ultérieure qui y ferait transiter une synchronisation de langue verrait ses messages lus comme des fins de session par tous les onglets ouverts. Coût : un champ. Prouvé par mutation 6.
- **Une raison NON RECONNUE termine quand même la session.** Le canal est de même origine : seule notre propre application y écrit, donc une raison qu'on ne comprend pas vient d'une version plus récente de l'application dans un autre onglet — la session y a bel et bien pris fin. On termine, sur le chemin conservateur d'`endSession`, et le diagnostic « raison inconnue » existant s'affiche. Ignorer aurait été la direction non sûre.
- **Un garde d'authentification côté récepteur.** Un onglet déjà posé sur `/auth` se serait fait renvoyer vers `/auth` à chaque annonce — navigation visible, sans objet, qui écrase au passage le `redirect` que l'utilisateur venait d'obtenir.
- **Terminaison OBSERVABLE par navigation de routeur**, jamais `location.reload()` : la convention Frontend du spine interdit le rechargement silencieux, et un rechargement ferait perdre la saisie en cours d'un onglet que l'utilisateur n'a peut-être même pas regardé. Même chemin que `installSessionExpiryListener`, `signInQuery`/`returnToSignIn` réutilisés — troisième appelant, et c'est la raison pour laquelle T3 les avait extraits.
- **`closeSessionBroadcast()` sans appelant de production.** Un onglet vivant garde son canal ouvert pour la durée de sa vie, comme ses écouteurs `pointerdown`. C'est la suite qui en a besoin : sans elle, chaque fichier laisserait derrière lui des canaux entendant les messages du suivant. Même rôle que `stopIdleWatch`.

**Ce que la story NE revendique PAS (écarts nommés, pas cochés) :**

1. **La propagation est LOCALE À L'APPAREIL.** `BroadcastChannel` ne franchit ni l'origine ni la machine. Une révocation déclenchée côté serveur depuis un AUTRE appareil — changement de mot de passe, Story 2.6 / NFR-P5 — ne produit aucun message ; elle se détecte par le 403 nu au prochain appel API, chemin `escrow:session-expired` qui existe déjà. Écrit dans le module et dans les tâches.
2. **La raison `'idle'` est mesurée PAR ONGLET** quand le substrat est `sessionStorage` (le défaut) : chaque onglet a son propre horodatage. Un onglet resté en arrière-plan quinze minutes met donc fin aussi à la session d'un onglet où l'on travaillait. C'est la lecture littérale de l'AC3 — « les autres onglets terminent leur session » — combinée à la contrainte « le message porte la raison, pour que le récepteur affiche le bon motif », qui n'aurait aucun sens si `'idle'` ne se propageait pas. En mode « rester connecté » l'horodatage est partagé et le cas ne se présente pas. **À rouvrir avec le PO si le coût d'usage se confirme** — corriger en silence aurait voulu dire ajouter au récepteur une condition que l'AC ne demande pas.
3. **Aucune nouvelle clé i18n.** Le motif du récepteur est celui que `endSession` écrit déjà (`auth.sessionIdleNotice`), et une déconnexion volontaire n'en produit aucun — ni ici ni dans l'onglet émetteur. Inventer un message « vous avez été déconnecté depuis un autre onglet » aurait élargi l'AC en passant.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **⚠️ La passe de mutation a démasqué une assertion négative FLAKY dans mes propres tests — et c'est la mutation, pas la suite, qui l'a vue.** Trois tests s'écrivaient « on poste le message douteux, on attend 20 ms, on constate que rien n'a bougé ». Avec le discriminant retiré (mutation 6), le test correspondant **rougissait lancé seul et restait VERT dans la suite complète** : sous charge, les 20 ms s'écoulaient avant que le message ne soit livré, et l'assertion était satisfaite par le vide. Une preuve qui dépend de la charge de la machine n'est pas une preuve. Les trois tests s'appuient désormais sur deux garanties de la spécification plutôt que sur l'horloge — **l'ordre de livraison** (on fait suivre le message douteux d'un message bien formé et on attend l'effet du second, donc le premier est nécessairement traité), et **l'ordre de création des canaux** (un témoin ouvert APRÈS le canal de production : quand il reçoit, la production a déjà reçu). Le marqueur qui distingue « c'est le premier qui a agi » de « c'est le second » est le MOTIF, seul témoin différent entre `'idle'` et `'logout'` là où le jeton, la navigation et les comptes d'appels sont identiques dans les deux hypothèses. Ordre de création vérifié dans ce runtime sur 200 messages et deux canaux : zéro inversion.
2. **⚠️ Le repli silencieux est DOUBLEMENT implémenté, et aucune mutation d'une seule ligne ne le détecte** (mutation 7a : 490/490 verts). La garde `typeof BroadcastChannel === 'undefined'` et le `try/catch` autour du constructeur couvrent deux causes DIFFÉRENTES — API absente d'un côté, constructeur qui lève de l'autre (stockage cloisonné, contexte non sécurisé) — mais chacune rattrape le cas de l'autre. La redondance est justifiée et commentée ; le fait est **consigné plutôt que maquillé**, parce qu'une couverture qui ne se prouve qu'en retirant deux lignes à la fois n'est pas la même chose qu'une couverture ligne à ligne.
3. **`BroadcastChannel` EXISTE dans jsdom 29 + Vitest 4** (implémentation Node) — aucun double n'a donc été nécessaire, et `vitest.setup.ts` n'a pas été touché. Deux comportements vérifiés avant d'écrire quoi que ce soit : la livraison est **asynchrone** (d'où les minuteries réelles dans tout le fichier — `vi.useFakeTimers()` aurait suspendu la suite au lieu de la faire rougir, exactement le piège que T3 a payé), et un canal **ne reçoit pas ses propres messages**, ce sur quoi repose toute la simulation de « l'autre onglet ».
4. **Observé une fois, non reproduit : `la minuterie est branchée elle aussi` (T3, `mainIdleStartup.spec.ts`) a échoué dans deux exécutions MUTÉES** — donc plus longues et plus chargées — et n'a jamais échoué en isolation (5/5) ni sur l'arbre livré (**10 exécutions complètes consécutives, 490/490**). Ce test amorce l'application entière sous minuteries factices partielles, là où `fake-indexeddb` ordonnance sur `setImmediate` ; c'est une fragilité de harnais sensible à la charge, antérieure à T4 et à surveiller, pas un défaut de la propagation.

État T4 : **490 tests verts** (478 au départ, **+12**), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1978 fichiers.

**T4-bis — Veto du récepteur (décision D-F).** L'écart n° 2 nommé ci-dessus — « la raison `'idle'` est mesurée PAR ONGLET » — a été porté au PO, qui l'a tranché : **veto du récepteur**. Une ligne dans `endSessionFromAnotherTab`, et le paragraphe de `session.ts` qui documentait l'écart comme un comportement assumé est réécrit — une justification périmée est ce qui fait reconduire un défaut pour toujours.

Choix et motifs :

- **Le veto vit chez le RÉCEPTEUR, pas dans le message.** L'émetteur ne sait rien de l'activité des autres onglets ; c'est précisément l'asymétrie d'information qui fabrique le défaut. Le seul module en droit de contredire une déduction d'inactivité est celui qui détient l'horodatage concerné.
- **`isIdleExpired()` réutilisée telle quelle, jamais un calcul recopié.** Le veto lit le MÊME horodatage et le MÊME seuil que le contrôle au démarrage et que la minuterie. Un `Date.now() - last > 15 * 60 * 1000` écrit ici aurait divergé au premier ajustement, et la divergence n'aurait rougi nulle part.
- **Horodatage ABSENT ⇒ l'onglet n'est PAS inactif ⇒ il survit.** Même lecture qu'`enforceIdlePolicy`, qui horodate au lieu de déconnecter : l'absence n'établit aucun fait. La direction est ici la conservatrice au sens de l'usage, et elle ne coûte rien à NFR-P8 — un appareil vraiment abandonné voit chacun de ses onglets atteindre sa propre échéance et terminer de lui-même, sans avoir besoin d'un message.
- **Le veto est placé APRÈS le garde d'authentification** et non avant : un onglet déjà déconnecté n'a rien à vétoer, et l'ordre garde chaque test sur la garde qu'il nomme.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **⚠️ Le veto allait CREUSER deux gardes déjà prouvées par mutation en T4.** `sessionBroadcast.spec.ts` prouve le discriminant de message (T4/M6) et le garde d'authentification (T4/M5) en postant un message `'idle'` qui doit être ignoré, et en lisant le MOTIF comme témoin — `'idle'` en écrit un, `'logout'` non. Sur un onglet actif, le veto écarte désormais ce message **avant** que la garde nommée ait à se prononcer : les deux tests seraient restés **verts** avec leur garde retirée. Les deux tabs sont donc explicitement rendus inactifs (`alsoIdle()`), ce qui rétablit la mutation. C'est le mode d'échec « une assertion satisfaite par la mauvaise garde », trouvé non par la suite mais en relisant ce que chaque test prouve avant d'y toucher.
2. **Aucune minuterie factice n'a été nécessaire.** « Actif » et « inactif » s'écrivent en posant un instant dans le substrat (`markActivity()` / `Date.now() - IDLE_TIMEOUT_MS - 1000`) sur l'horloge RÉELLE. Figer l'horloge dans ce fichier aurait suspendu la livraison des messages `BroadcastChannel` — le piège que T3 a payé et que T4 documente.
3. **L'assertion « l'onglet actif survit » est une négative**, et elle est ancrée sur le `witness()` de T4 (ordre de création des canaux) plutôt que sur un délai : « rien n'a bougé » et « le message n'est pas encore arrivé » sont indiscernables sur une attente fixe. Appariée à une positive dans le même test : le `'logout'` qui suit termine bel et bien la session.

État T4-bis : **494 tests verts** (490 au départ, **+4**), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1978 fichiers.

**T5 — Attente bornée sur la révocation.** `endSession` n'attend plus la révocation serveur au-delà de `REVOCATION_WAIT_SECONDS = 3` (constante SOURCE exportée par `session.ts`, dont `REVOCATION_WAIT_MS` dérive), via un `withBudget` privé qui fait courir la promesse contre une minuterie.

Choix de conception non dictés par la story, et leurs motifs :

- **La borne porte sur l'ATTENTE, jamais sur la requête — et c'est tout le correctif.** La façon apparemment naturelle de « borner » un `fetch` est d'y poser un `AbortSignal` ; c'est précisément celle qu'il ne fallait pas prendre. Elle ANNULE la révocation au lieu de cesser de l'attendre, et le jeton reste accepté côté serveur pour les 24 h de son TTL (Q3, hors périmètre). `api/auth.ts` n'est donc pas touché d'une ligne : `keepalive` mène la requête à terme après la navigation, y compris après la fermeture de l'onglet, et c'est exactement ce qui rend acceptable d'arrêter de regarder.
- **Le plafond vit dans `session.ts` et non dans `logoutUser`.** C'est `endSession` que `DashboardView` attend avant de naviguer ; c'est donc cette attente-là qu'il faut borner. Borner `logoutUser` aurait changé le contrat d'une fonction dont la revue 1.6 a fixé la forme, et pour un seul de ses appelants.
- **Trois secondes.** Le temps qu'une liaison de corridor honnête met à répondre, pas le temps qu'un portail captif met à ne pas répondre. Dépasser le plafond ne coûte qu'une navigation plus tôt : rien de local n'en dépend, tout est déjà purgé — c'est bien parce que la révocation est la dernière étape, et la seule dont l'issue ne conditionne rien, qu'elle est abandonnable.
- **La minuterie est éteinte dans un `finally`.** Sans cela, une déconnexion ordinaire — celle où le réseau gagne la course — laisserait derrière elle une minuterie de trois secondes, une par déconnexion.
- **Le rejet tardif reste traité.** `Promise.race` a posé son gestionnaire sur la promesse de révocation avant même que le plafond échoie : un échec réseau arrivant après l'abandon ne remonte pas en rejet non traité. (`revokeOnServer` avale déjà ses échecs ; ceci est la ceinture de sa bretelle.)
- **L'attente est DITE (UX-DR26).** Une attente bornée reste une attente, et pendant ces secondes-là les stores sont déjà remis à zéro : l'utilisateur regardait un tableau de bord qui perd son contenu et un bouton qui ne répond plus. Le bouton porte désormais `common.loggingOut` (EN+FR), `:disabled` et `aria-busy`. Le délai est **interpolé** depuis `REVOCATION_WAIT_SECONDS` et jamais écrit dans les catalogues — même règle que le `{minutes}` de T3, pour la même raison.
- **Périmètre tenu :** la place du bouton, sa tokenisation, `type="button"` et sa cible tactile restent à **T8** ; le test livré ici n'assère rien de tout cela, pour ne pas rougir à la migration du bouton pour une raison étrangère à ce qu'il prouve.

**Le commentaire périmé de `DashboardView.vue` est réécrit**, pas raboté : la phrase fausse — « la navigation avortait la requête en vol », vraie de `window.location.href`, fausse de `router.replace` — est supprimée ; les deux raisons qui tiennent (l'appareil doit être propre avant la navigation ; l'attente est bornée) sont écrites ; et le report au ledger qu'il annonçait est déclaré clos, puisque c'est cette story qui le solde.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **`session.spec.ts` n'a PAS rougi — et c'est un résultat, pas une chance.** La story annonçait ce test comme la victime probable du changement. Il a tenu parce qu'il observe sur `vi.waitFor(() => expect(logoutUser).toHaveBeenCalled())` : la leçon de la passe 3 de la Story 1.9 — « le correctif de code était bon, sa preuve ne l'était plus » — y avait déjà été appliquée, et c'est elle qui l'a rendu insensible à un `await` de plus. La mutation n° 3 confirme qu'il garde toujours ce qu'il nomme : remettre la révocation devant les purges le fait rougir.
2. **⚠️ Une garde livrée était improuvable, et la mutation l'a montrée AVANT la revue.** Le gestionnaire portait un `if (signingOut.value) return` en plus du `:disabled` du bouton. Aucune mutation d'une seule ligne ne les distinguait — chacune rattrapait le cas de l'autre — si bien que la condition du gestionnaire était du code que la suite ne pouvait pas falsifier. Elle est **retirée** : une garde improuvable en double d'une garde prouvée n'est pas de la défense en profondeur, c'est une preuve creuse en attente d'être citée. `:disabled` reste, et la mutation n° 6 le fait rougir.
3. **⚠️ Deux défauts de preuve dans mon propre test du second clic, trouvés par la même mutation.** D'abord `trigger()` de `@vue/test-utils` **refuse de cliquer un élément désactivé** : le second clic n'atteignait jamais le gestionnaire, et le test restait vert la garde retirée. Ensuite l'oracle lui-même était **faux** : à la seconde entrée, le jeton a déjà quitté la mémoire et `revokeOnServer(null)` rend une promesse résolue **sans appeler `logoutUser`** — le compte serait resté à 1 dans les deux hypothèses. Ce qu'une seconde terminaison rejoue réellement, c'est la PURGE (un second tour d'IndexedDB, un second effacement du cache, une seconde annonce aux autres onglets) ; `caches.delete` en est le témoin, et c'est lui qu'on compte désormais.
4. **`logoutUser` n'avait AUCUN test.** Ses deux garanties — `keepalive: true` et rien qui puisse interrompre la requête — étaient des commentaires, pas des propriétés, alors que ce sont elles qui rendent la borne acceptable. `api/__tests__/authLogout.spec.ts` les asservit désormais (3 tests), et les mutations 4 et 4b montrent qu'il rougit.
5. **`vi.getTimerCount()` n'est pas un oracle absolu utilisable ici** : `fake-indexeddb` pose ses propres minuteries pendant la purge qui précède la révocation. Le compte est pris en DIFFÉRENTIEL, l'instant de référence étant capturé **pendant** l'attente — sinon on mesure le ménage du harnais plutôt que le sien.

État T5 : **505 tests verts** (494 au départ, **+11**), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1978 fichiers, `npm run verify:no-demo` et `npm run verify:pwa` vertes.

**Écart nommé, pas coché :** le TTL serveur reste de 24 h (`application.yml:77`, décision Q3). La borne rend la main à l'utilisateur ; elle ne raccourcit pas la vie du jeton, et une révocation qui n'aboutirait vraiment jamais — appareil éteint pendant le `keepalive` — laisse ce jeton valide jusqu'à son expiration naturelle. À porter au ledger à la clôture de la story, comme la Q3 le prévoit déjà.

**T6 — Trois états du jeton.** `stores/auth.ts` porte désormais `SessionState = 'anonymous' | 'active' | 'incoherent'` et le getter `sessionState`. `stores/offlineQueue.ts` en est le premier consommateur, aux deux endroits où la lecture à deux états faisait du dégât.

**L'ARBITRAGE que l'AC5 laissait ouvert — effacer le jeton, ou faire signaler la file — et son motif.** L'AC offrait les deux ; j'ai retenu **le signalement, et refusé l'effacement**. Quatre raisons, la dernière étant décisive :

1. **Effacer le jeton, c'est détruire une preuve sur un soupçon.** Ce jeton est valide côté serveur — l'API répond, et pour 24 h encore (Q3). Le supprimer parce qu'une CLÉ VOISINE est illisible, c'est laisser un incident de stockage (quota dépassé pendant `persist()`, `sessionStorage` cloisonné, valeur éditée à la main) décider d'une déconnexion que personne n'a demandée. Le sens de la lecture est faux : l'authentification ne manque pas, le profil manque.
2. **Ça aurait remis trois états à deux.** Jeton effacé ⇒ état `'anonymous'` ⇒ exactement la confusion que l'AC5 demande de défaire, obtenue par l'autre bout. La file aurait continué à lire « personne n'est connecté » — en ayant raison cette fois, mais sans jamais avoir su qu'il y avait eu quelqu'un.
3. **La Story 2.4 n'aurait plus eu de place où s'insérer.** Un quatrième état — compte non vérifié — a besoin d'une énumération, pas d'un chemin qui efface. Le repère est écrit dans `sessionState`, entre la lecture du profil et le `return 'active'` : deux lignes à ajouter, aucun appelant à retoucher.
4. **La Story 2.3 avait déjà tranché la même question et dans le même sens.** Sa branche « session incohérente » renvoie vers `/auth` *pour réparer le profil*, en disant explicitement pourquoi elle ne traite pas le cas comme un refus : « le traiter comme un refus condamnerait l'écran de récupération, qui rend à l'utilisateur des fichiers n'existant NULLE PART ailleurs ». Effacer le jeton aurait contredit cette décision six mois plus tard, dans un autre fichier, sans la rouvrir.

Choix de conception non dictés par la story, et leurs motifs :

- **Une ÉNUMÉRATION, jamais un second booléen.** `isIncoherent` posé à côté d'`isAuthenticated` aurait été un booléen à deux branches déguisé en trois : la Story 2.4 aurait dû le réécrire, et deux booléens indépendants admettent quatre combinaisons dont deux sont impossibles — le compilateur n'en dit rien.
- **`isAuthenticated` reste `Boolean(token)`, intouché.** Le rendre faux sur une session incohérente était le raccourci tentant : il aurait renvoyé l'utilisateur vers `/auth` sans rien expliquer **pendant que `client.ts` continue d'envoyer l'en-tête `Authorization`** — deux modules en désaccord sur l'existence de la session. Le getter neuf dit ce qui manque ; l'ancien n'a pas à mentir pour le compenser.
- **AUCUNE table de correspondance.** Trois conditions écrites en toutes lettres. C'est la leçon de `spaceForRole('constructor')` appliquée par prévention : `state.user` sort d'un `JSON.parse` sur un stockage éditable, et une table indexée par une de ses valeurs aurait rendu la fonction `Object` — que `?? null` ne rattrape pas. Un objet littéral qu'on n'écrit pas ne peut pas être indexé par une clé hostile ; un test le fige quand même (`ne se laisse pas indexer par une clé hostile venue du stockage`).
- **La branche du routeur n'est PAS réécrite en `sessionState === 'incoherent'`, et la raison est écrite sur place.** Les deux prédicats posent la même question sur deux champs différents : le routeur a besoin d'un RÔLE routable (`spaceForRole(user?.role) === null`), la file d'un IDENTIFIANT de propriétaire (`user?.id == null`). Ils se recouvrent sans coïncider — un profil lisible portant un rôle inconnu est routable « nulle part » sans être incohérent. Les fusionner aurait élargi l'un des deux en silence, et c'est une garde de sécurité de la Story 2.3. Ce qui est partagé, c'est le VOCABULAIRE ; le commentaire du routeur le dit et renvoie à `SessionState`.
- **`init()` ne pose PAS `hydrated = true` dans l'état incohérent — et ne fait rien d'autre non plus.** C'est le seul signal disponible : `RecoveryView` lit `hydrated` et rien d'autre, et son `if (!queue.hydrated)` lui fait rappeler `init()` puis afficher son état de lecture manquée. Laissé à `true`, l'écran annonce `recovery.nothingToRecover` à propos d'une file que personne n'a ouverte — sur la seule surface qui rende des fichiers n'existant nulle part ailleurs.
- **Les branches d'`init()` sont NOMMÉES (`=== 'incoherent'`, `=== 'anonymous'`) et non écrites en `!== 'active'`.** Écrite en négatif, la condition aurait capturé le quatrième état de la 2.4 par accident et refusé sa file à un compte non vérifié — qui a pourtant un identifiant, donc des entrées qui sont les siennes. Un défaut par omission, invisible au compilateur, dans une story qui n'aurait pas touché ce fichier.
- **La garde d'`enqueue()` vit au POINT D'ÉTRANGLEMENT, pas aux trois points d'appel.** `stores/escrow.ts` estampille `meta.userId` trois fois ; trois copies divergent, et un quatrième appelant naîtrait sans garde. Refuser est la seule issue non destructrice : il n'existe aucun propriétaire à inscrire, et en inventer un — le marqueur d'appareil `escrow_last_user`, par exemple — rendrait à quelqu'un les entrées d'un autre, ce que la Story 1.9 a fermé.
- **L'état `'anonymous'` n'est PAS refusé à l'enfilement, et l'asymétrie est délibérée.** Il ne traduit aucune incohérence : c'est l'état honnête d'une application sans session, et toutes les surfaces qui appellent `enqueue` sont derrière une route protégée. C'est l'état INCOHÉRENT qui fabrique un orphelin *pendant que l'application se croit authentifiée*. Le refuser aussi aurait par ailleurs cassé une quinzaine de tests qui exercent légitimement la mécanique de la file sans session — signe que ce n'est pas la même chose.

**Ce que je n'ai PAS touché, après l'avoir vérifié :**

- **`hydrate()` garde son `userId == null`.** Sa question n'est pas « dans quel état est la session » mais « ai-je de quoi interroger `getAllForUser` ». Ses deux appelants ont déjà tranché l'état avant d'arriver.
- **`flush()` n'a besoin de rien.** Son filtre `ownsEntry(item, user)` rend `false` dès que `user?.id` est nul : une session incohérente ne rejoue **rien**, et donc ne peut pas geler l'entrée d'un tiers. Vérifié en lisant `frozenEntry.ts:90-93`, pas en le supposant.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **Le troisième état est plus large que « `JSON.parse` a échoué ».** Un profil parfaitement parsable — `{"email":"…","role":"BUYER"}`, sans `id` — franchit `loadStoredUser()` sans encombre et produit **exactement le même dégât en aval** : `meta.userId: undefined`, `getAllForUser` qui ne rend rien, `ownsEntry` qui rend `false`. Le prédicat retenu est donc `user?.id == null` et non `user == null`. La mutation n° 4 existe précisément pour ça : écrite sur `user == null`, la garde restait verte sur les cinq autres tests.
2. **La branche « session incohérente » du routeur rend le défaut d'`enqueue` INATTEIGNABLE par l'interface aujourd'hui.** Toute route protégée est refusée à une session incohérente (`spaceForRole(undefined) === null`), donc aucun écran ne peut déclencher une mise en file dans cet état. Le seul chemin encore vivant est celui d'`init()`, appelé par `main.ts` **avant** que la garde du routeur n'ait rien à dire. La garde d'`enqueue` est donc, à cette date, une garde d'INVARIANT et non un correctif observable — et c'est écrit ici plutôt que revendiqué comme une fuite fermée. Elle garde sa valeur : la 2.4 ajoute un état où la question se reposera, et la garde vit au seul endroit qui survivra à ce changement.
3. **Le test qui prouve le mieux le défaut est le test de bout en bout, pas le test unitaire.** `ne laisse aucune entrée orpheline naître d'une création de transaction hors ligne` passe par `escrow.createNewTransaction`, c'est-à-dire par le code qui écrit réellement `userId: useAuthStore().user?.id`. Un test qui n'aurait appelé qu'`enqueue` aurait prouvé la garde sans jamais prouver que le chemin de production la traverse — la distinction « fonction prouvée » / « fonction branchée » que T3 et T4 ont payée deux fois.

État T6 : **512 tests verts** (505 au départ, **+7**), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte, `npm run verify:no-demo` verte.

**T7 — Époque de session.** Un compteur monotone `sessionEpoch` porté par `stores/session.ts`, tourné aux DEUX bouts d'une session, capturé à l'émission de chaque lecture et relu à sa résolution — dans `escrow.loadTransactions`, `escrow.loadTransactionDetail` et `evidence.loadEvidence`.

Choix de conception non dictés par la story, et leurs motifs :

- **L'époque vit AU MODULE, jamais dans un `state()`.** C'est le point entier. Une époque rangée dans un store serait rembobinée par `$reset()` — c'est-à-dire le défaut de `loadSeq` reproduit à l'identique dans son propre correctif. `session.ts` est de surcroît le seul fichier qui sache quand une session commence et quand elle finit ; l'invariant y tient en une phrase, écrite sur place : **tout `$reset()` qui rembobine `loadSeq` est précédé d'un tour d'époque**, et il y en a exactement deux.
- **`currentSessionEpoch` est une DÉCLARATION de fonction, pas une `const` fléchée.** `escrow.ts` et `evidence.ts` importent désormais `session.ts`, qui les importe déjà : le cycle existait (`escrow → auth → session → escrow`), ces imports en sont des cordes. Une déclaration de fonction est hissée et initialisée avant l'évaluation du module ; une `const` aurait été en zone morte temporelle et le premier import croisé aurait levé un `ReferenceError` **au démarrage**, pas en test. Le commentaire de tête du fichier posait déjà la règle — aucune traversée du cycle à l'évaluation — et elle est respectée à la lettre.
- **Le tour d'époque n'est PAS dans un `try/catch`**, à contre-courant de tout ce qui l'entoure. Une incrémentation d'entier ne lève pas, et l'envelopper laisserait croire le contraire. Il est en revanche placé **avant** les effets qui, eux, peuvent échouer : un `$reset()` qui lèverait ne doit pas laisser derrière lui une époque non tournée, c'est-à-dire un store à demi vidé qu'une réponse en vol pourrait repeupler.
- **La comparaison porte sur les TROIS sorties**, pas seulement sur l'écriture des données. Un `error` écrit par la lecture de A afficherait à B un message pour une requête qui n'est pas la sienne ; un `loading = false` retirerait le voile de chargement de B pendant que sa propre lecture est en vol — un écran vide annoncé comme terminé. Les mutations 6 et 7 prouvent ces deux-là séparément.
- **`loadSeq` est conservé, et sa raison d'être est réécrite en tête du champ.** Les deux gardes couvrent des courses différentes : `loadSeq` les courses INTRA-session — deux lectures concurrentes, et surtout `withdrawEvidence` qui l'incrémente pour invalider une lecture en vol dont la réponse montrerait la pièce encore ACTIVE ; l'époque les courses INTER-sessions, que `loadSeq` ne peut pas voir. L'époque **rend le rembobinage inoffensif ; elle ne le supprime pas**.
- **L'époque est lue AVANT `loadSeq` dans `loadEvidence`.** Une réponse d'une session révolue n'a même pas à être comparée au compteur d'une session qui n'est pas la sienne. L'ordre inverse aurait donné le même résultat ; celui-ci se lit dans le sens de la question.

**Ce que la story NE revendique PAS (écarts nommés, pas cochés) :**

1. **Le chemin service worker n'est pas couvert** — décision D-E, entrée E9 du ledger, routée vers la Story 11-3. Une réponse `/api/` en vol au moment de la purge **recrée** `escrow-api-cache` derrière elle : la stratégie `NetworkFirst` de Workbox écrit hors de tout store Pinia, donc hors de portée d'une comparaison qui vit dans les stores. C'est écrit dans `session.ts` et dans l'en-tête de `sessionEpoch.spec.ts`, et aucune assertion ne dit « aucune donnée de A ne survit » : elles disent toutes « ce store-ci ne l'accepte pas ».
2. **`createNewTransaction` n'est PAS gardée, et c'est une question posée au PO plutôt qu'une décision prise seul.** Son `this.transactions.unshift(created)` en ligne écrit sans comparer l'époque. Ce n'est pas une réponse de lecture — l'AC6 parle des lectures — et l'écarter en silence est une décision produit : la transaction **existe** côté serveur, si bien que la refuser à l'affichage la rend invisible jusqu'au prochain rechargement. L'écart est écrit dans le code, à l'endroit exact. **Les deux autres chemins d'écriture n'ont, eux, besoin de rien, et c'est vérifié et non supposé** : `sendTransactionEvent` et `openDispute` écrivent via `findIndex(...)` (qui rend -1 sur une liste vidée par `$reset()`) et via `this.currentDetail?.…` (nul après le même `$reset()`) — leurs écritures sont structurellement sans effet sur le store d'autrui.

**Ce que j'ai trouvé et qui n'était pas prévu :**

1. **⚠️ La passe de mutation a montré que les DEUX tours d'époque n'étaient gardés que par une assertion sur le COMPTEUR — et elle a fait écrire les deux tests qui manquaient.** Retirer le tour d'`endSession` laissait verts tous les tests du scénario A → B : celui de `beginSession` suffisait à les satisfaire, et réciproquement. Chaque moitié du dispositif était donc « prouvée » par un test qui lisait sa valeur, jamais par un test qui constatait son effet — la définition même de la preuve creuse. Deux tests ont été ajoutés, chacun sur la fenêtre que **seul** son tour ferme : pour `endSession`, la réponse de A qui repeuple le store **après sa déconnexion et avant toute autre session** — sur un appareil qui vient d'être rendu, personne ne le voit à l'écran (la garde du routeur renvoie vers `/auth`) et c'est bien le problème ; pour `beginSession`, une session qui commence **sans qu'aucune ne se soit terminée**, chemin qui n'est pas théorique puisque la branche « session incohérente » du routeur (2.3) et `auth.verify` (2.4) y mènent tous les deux. Les deux mutations rougissent désormais sur un effet.
2. **La mutation qui casse la MONOTONIE (n° 3) laisse un test vert, et l'explication est instructive.** Avec `sessionEpoch = 0` à chaque tour, `n'affiche pas à B l'erreur d'une lecture émise par A` (côté `evidence`) reste vert : dans ce test-là, B n'a lancé aucune lecture, donc son `loadSeq` vaut 0 quand celui de la réponse d'Alice vaut 1 — **c'est `loadSeq` qui l'attrape**, pas l'époque. C'est la démonstration la plus nette que les deux gardes sont complémentaires et non redondantes, et c'est aussi pourquoi le test du **rembobinage** (où les deux `seq` coïncident) est le seul qui isole vraiment l'époque.
3. **`loadSeq` n'avait AUCUN test**, alors qu'il garde ce store depuis l'Epic 4 et que `withdrawEvidence` l'incrémente pour une raison de sécurité de la donnée. `stores/__tests__/evidence.spec.ts` n'existait pas ; il existe désormais et couvre les deux courses intra-session avant de couvrir les inter-sessions. La remutation N1 confirme que ces deux-là rougissent — c'est-à-dire que ma garde neuve, placée devant, ne les creuse pas.
4. **Aucune minuterie, ni factice ni réelle, dans les deux suites.** « La réponse de A arrive après la connexion de B » est obtenue par des promesses différées que le test résout lui-même : c'est une propriété du montage, pas un pari sur un délai. C'est la leçon de T4 (trois assertions négatives flaky démasquées par la mutation) appliquée d'emblée, et elle évite au passage le piège `vi.useFakeTimers()` / fake-indexeddb, puisque `endSession` et `beginSession` touchent IndexedDB.

État T7 : **527 tests verts** (512 au départ, **+15** — 13 écrits d'emblée, 2 ajoutés par la passe de mutation), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1982 fichiers, `npm run verify:no-demo` verte.

---

**T8 — Le bouton de déconnexion, et ce qu'il a révélé du gabarit.**

Le bouton est extrait en `components/LogoutButton.vue` et placé par `ClientShell` **et** `DesktopShell`. Il n'est pas dupliqué : le comportement (`endSession`, le filet de navigation, l'état d'attente) est écrit une fois. Le porter en double aurait obligé à prouver deux fois trois gardes que l'AC7 exige de prouver — et, à la première divergence, l'une des deux copies aurait cessé d'être celle qu'on teste.

`DashboardView` est vidé de la déconnexion (fonction, état, imports), avec sur place la raison de ne pas l'y remettre.

**Ce que la tâche a trouvé et qui n'était pas prévu :**

1. **⚠️ `AppButton` (Story 2-2) n'avait AUCUN anneau de focus.** Ses classes de base s'arrêtaient à `disabled:opacity-60`, et `style.css` définit `--color-focus-ring` sans aucune règle globale qui l'applique : sur 28 composants, **deux fichiers seulement** portaient un `focus-visible` (`ClientShell`, `VerifyEmailView`). Le bouton **partagé** de la plateforme était donc atteignable au clavier sans que le focus se voie — pour qui n'utilise pas de souris, une interface où l'on ne sait pas où l'on est. Réparé **dans `AppButton`**, pas contourné dans l'appelant : le défaut n'était pas celui du bouton de déconnexion, il était celui du gabarit, et le corriger en local l'aurait laissé entier pour les 27 autres usages. La 2-3 avait pourtant asséré l'anneau sur les liens de navigation (`shells.spec.ts`) — la règle existait, elle n'avait simplement jamais été portée aux boutons.
2. **`AppButton` ne savait pas interpoler.** Son libellé passe par `translateOrHumanize(i18n, key)`, sans paramètres. Or `common.loggingOut` vaut « Déconnexion en cours… ({seconds} s au plus) » : sans interpolation, le bouton affichait le gabarit nu ou perdait le chiffre, et UX-DR26 (motif **et** délai annoncé), tenue en T5, se serait défaite à la migration. Prop `labelParams` ajoutée, **défaut `null` et non `{}`** — un objet vide aurait basculé *tous* les boutons de l'application sur la forme `t(key, params)`, changeant la signature observée par les doubles de test sans qu'une seule ligne de leur code ne bouge.
3. **⚠️ J'AI ÉCRIT UN TEST CREUX, ET SEULE LA MUTATION L'A DIT.** Le test « la session est détruite AVANT le filet » assérait `auth.token === null` **après** un `vi.waitFor`. Or `waitFor` scrute par sondages : le temps qu'il rende la main, `endSession` s'était achevée de toute façon. Les assertions décrivaient l'état FINAL, identique que l'ordre soit respecté ou inversé — déplacer `endSession` après la navigation ne faisait rougir **aucun** des six tests. Le correctif n'est pas une assertion de plus : c'est un changement d'instant d'observation. Le double d'`assign` capture lui-même l'état au moment où il est appelé, seul point du parcours contemporain de l'événement mesuré. La mutation rejouée fait alors rougir ce test **et lui seul**. C'est la troisième fois sur cette story qu'une preuve creuse survit à sa propre relecture ; le motif est toujours le même — *les bons faits, le mauvais instant*.
4. **Le filet de navigation était intestable par les moyens habituels.** `location.assign` est `configurable: false` et `writable: false` : `vi.spyOn` lève. Ce qui est redéfinissable, c'est `window.location` lui-même (accesseur `configurable: true`). Un proxy sur le **vrai** `Location` échoue à son tour — l'invariant du langage interdit à un piège `get` de masquer une propriété non configurable de sa cible. La cible du proxy est donc un objet **vide** qui délègue tout au vrai `location`, sauf `assign`. Le détour vaut d'être noté : le routeur lit `location` à sa construction, qui a lieu dans le test ; un objet factice écrit à la main aurait fait router la suite sur une URL inventée, et le harnais aurait mesuré autre chose que la production sans le dire. **Patron nouveau dans ce dépôt** — aucun test n'avait jamais eu besoin de neutraliser une navigation.

**Ce que T8 ne revendique PAS (écart nommé, pas coché) :** rien ne prouve encore que le bouton est **effectivement rendu** par les deux shells. `LogoutButton` est testé isolément, ce qui établit qu'il fonctionne, pas qu'il est branché — exactement la distinction que T9 pose pour `main.ts` (« un shell testé isolément prouve qu'il fonctionne, pas qu'il est branché »). Les assertions de placement, de tokenisation et de cible tactile dans `shells.spec.ts` appartiennent à T9 et **ne sont pas anticipées ici**.

Le point `danger-hover` de T8 **ne s'est pas déclenché** : le bouton d'origine était neutre, son équivalent tokenisé est `secondary`. Le rendre destructeur au passage aurait été une décision de design prise en contrebande d'une tâche de migration. L'arbitrage reste ouvert, inchangé, pour qui livrera un vrai bouton danger.

État T8 : **530 tests verts / 43 fichiers** (527 au départ, **+3**, tous sur le filet), `vue-tsc --build` à 0 diagnostic, `npm run lint --max-warnings 0` propre, `python3 scripts/check-encoding.py` verte sur 1984 fichiers.

### File List

_Cumulée T1 → T8. Les tâches T9-T11 ne sont pas commencées._

**Nouveaux — production**
- `frontend/src/utils/credentialStorage.ts` (T1) — substrat commutable des identifiants
- `frontend/src/utils/idleTimeout.ts` (T3) — mesure de l'inactivité, compteur de requêtes en vol, minuterie
- `frontend/src/utils/sessionBroadcast.ts` (T4) — canal `escrow-session`, règle d'autorité, repli silencieux
- `frontend/src/components/LogoutButton.vue` (T8) — la déconnexion écrite UNE fois : `endSession`, filet de navigation, attente dite ; placée par les deux shells

**Nouveaux — tests**
- `frontend/src/utils/__tests__/credentialStorage.spec.ts` (T1)
- `frontend/src/views/__tests__/rememberMe.spec.ts` (T2)
- `frontend/src/utils/__tests__/idleTimeout.spec.ts` (T3) — seuil, minuterie, requêtes en vol
- `frontend/src/stores/__tests__/sessionIdle.spec.ts` (T3) — sémantique `idle`, motif, `enforceIdlePolicy`, `installIdleTimeout`
- `frontend/src/__tests__/mainIdleStartup.spec.ts` (T3) — **câblage** de `main.ts`, amorçage réel
- `frontend/src/views/__tests__/sessionIdleNotice.spec.ts` (T3) — motif affiché, EN et FR
- `frontend/src/stores/__tests__/sessionBroadcast.spec.ts` (T4) — émission, réception, non-ré-émission, absence d'appel réseau, motif, repli
- `frontend/src/__tests__/mainSessionBroadcast.spec.ts` (T4) — **câblage** de `main.ts`, amorçage réel
- `frontend/src/stores/__tests__/sessionRevocationBudget.spec.ts` (T5) — le plafond d'attente : abandon borné, attente réelle quand le réseau répond, minuterie éteinte, signature de la révocation
- `frontend/src/api/__tests__/authLogout.spec.ts` (T5) — ce que `logoutUser` envoie : `keepalive`, aucun `signal`, jeton explicite
- `frontend/src/components/__tests__/logoutBoundedWait.spec.ts` (T5, **déplacé** de `views/__tests__/` en T8 — il monte désormais `LogoutButton`, pas `DashboardView`) — l'attente est dite (UX-DR26), EN et FR, bouton neutralisé ; **+3 tests T8** sur le filet de navigation, jusqu'ici entièrement mort
- `frontend/src/stores/__tests__/sessionState.spec.ts` (T6) — les trois états, le mensonge d'`hydrated`, l'entrée orpheline (7 tests)
- `frontend/src/stores/__tests__/evidence.spec.ts` (T7) — **`loadSeq` n'avait aucun test** : courses intra-session (lecture périmée, retrait) puis inter-sessions, dont le rembobinage par `$reset()` (6 tests)
- `frontend/src/stores/__tests__/sessionEpoch.spec.ts` (T7) — le compteur (deux tours, monotonie) et les lectures d'escrow (9 tests)

**Modifiés**
- `frontend/src/api/client.ts` (T1 : lecture par `readCredential` ; T3 : alimentation du compteur de requêtes en vol)
- `frontend/src/api/__tests__/client.spec.ts` (T1 ; T3 : 3 tests de câblage du compteur)
- `frontend/src/stores/auth.ts` (T1 : substrat commutable ; T6 : `SessionState`, getter `sessionState`)
- `frontend/src/stores/offlineQueue.ts` (T6 : `init()` ne prétend plus avoir lu, `enqueue()` refuse l'orpheline)
- `frontend/src/router/index.ts` (T6 : renvoi croisé vers `sessionState` — la branche « session incohérente » n'est PAS réécrite, et le pourquoi est écrit sur place)
- `frontend/src/stores/session.ts` (T1 : `LAST_USER_STORAGE_KEY` exportée ; T3 : mode `'idle'`, `KNOWN_REASONS`, motif persisté, `enforceIdlePolicy`, `installIdleTimeout`, extraction de `signInQuery`/`returnToSignIn` ; T4 : `EndSessionSource`, émission, règle d'autorité, `installSessionBroadcastListener` ; T4-bis : veto du récepteur sur `'idle'` ; T5 : `REVOCATION_WAIT_SECONDS`, `withBudget` ; T7 : `sessionEpoch`, `currentSessionEpoch`, les deux tours d'époque)
- `frontend/src/stores/escrow.ts` (T7 : garde d'époque sur `loadTransactions` et `loadTransactionDetail`, écart nommé sur `createNewTransaction`)
- `frontend/src/stores/evidence.ts` (T7 : garde d'époque sur `loadEvidence`, `loadSeq` conservé et documenté)
- `frontend/src/stores/__tests__/sessionBroadcast.spec.ts` (T4-bis : 4 tests du veto, et `alsoIdle()` sur les deux tests dont le veto aurait creusé la garde)
- `frontend/src/main.ts` (T3 : contrôle au démarrage + minuterie, avant `app.mount()` ; T4 : écouteur inter-onglets)
- `frontend/src/api/auth.ts` — **inchangé** (NEVER 1.9), désormais couvert par `authLogout.spec.ts`
- `frontend/src/views/DashboardView.vue` (T5 : commentaire justificatif périmé réécrit, état d'attente visible et libellé ; **T8 : la déconnexion en est RETIRÉE** — fonction, état, bouton, trois imports — avec sur place la raison de ne pas l'y remettre)
- `frontend/src/components/AppButton.vue` (T8 : anneau de focus, qui n'existait sur AUCUN bouton de l'application ; prop `labelParams` pour l'interpolation, défaut `null`)
- `frontend/src/i18n/labels.ts` (T8 : `params` optionnel sur `translateOrHumanize` et `translateOr` ; `I18nLike.t` élargie. `params` absent ⇒ `t(key)` conservé, pour ne pas changer la signature observée par les doubles antérieurs)
- `frontend/src/layouts/ClientShell.vue` (T8 : `LogoutButton` dans l'en-tête)
- `frontend/src/layouts/DesktopShell.vue` (T8 : `LogoutButton` dans la barre latérale — ARBITRATOR et ADMIN n'avaient aucune sortie)
- `frontend/src/views/AuthView.vue` (T2 : case « rester connecté » ; T3 : motif d'expiration)
- `frontend/src/i18n/en.json`, `frontend/src/i18n/fr.json` (T2 : `auth.rememberMe` ; T3 : `auth.sessionIdleNotice` ; T5 : `common.loggingOut`)
- `frontend/src/stores/__tests__/session.spec.ts`, `frontend/src/stores/__tests__/escrow.offline.spec.ts` (T1 : `sessionStorage.clear()` en `beforeEach`)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (T1)
