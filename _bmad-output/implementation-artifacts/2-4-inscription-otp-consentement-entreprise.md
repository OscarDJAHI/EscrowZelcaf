---
baseline_commit: 1ac97dc3c486378cfdf1ba011699090be575d5ea
---

# Story 2.4: Inscription vérifiée par OTP, consentement horodaté et rattachement à l'entreprise

Status: in-progress

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a professionnel non inscrit,
I want créer mon compte avec email et mot de passe, vérifier mon email par un code à 6 chiffres et déclarer mon entreprise,
so that je dispose d'un compte vérifié, juridiquement consenti et rattaché à mon entreprise, prêt pour le parcours KYB.

**Portée réelle :** cette story est la première de l'Epic 2 à toucher le **backend**, et elle modifie un endpoint **déjà en service** (`POST /api/v1/auth/register`) dont dépendent huit classes de test et le store frontend. Elle touche aussi une surface de sécurité (OTP = credential, anti-énumération, plafonds d'envoi).

## Acceptance Criteria

**AC1 — Création de compte non vérifié, consentement et entreprise**
**Given** le formulaire d'inscription (extension de l'`AuthService`/`AuthView` existants, formulaire 1 colonne avec validation à la volée — UX-DR35)
**When** je soumets un email, un mot de passe conforme à la politique en vigueur et la raison sociale de mon entreprise, et que je coche le consentement aux documents légaux
**Then** un compte non vérifié est créé, le consentement est persisté avec horodatage serveur (FR-P27), une entreprise est créée et reliée à l'utilisateur — User↔Company, l'utilisateur créateur en gestionnaire (FR-P9) — et un OTP à 6 chiffres à durée de vie limitée est envoyé à l'email
**And** les nouvelles tables (OTP, consentements, rattachement) sont créées par une migration Flyway dédiée à cette story, et le rôle ADMIN plateforme reste inaccessible depuis ce parcours (FR-P16).

**AC2 — Vérification du code**
**Given** l'écran de saisie OTP, dont le champ accepte le collage (UX-DR39)
**When** je saisis le code correct avant expiration
**Then** le compte passe vérifié, la session JWT est émise via le mécanisme existant et je suis dirigé vers l'espace client
**And** un compte non vérifié qui tente de se connecter est renvoyé vers l'étape OTP, jamais vers l'application.

**AC3 — Code erroné ou expiré**
**Given** un code erroné ou expiré
**When** je le soumets
**Then** un message d'erreur i18n s'affiche sous le champ, la saisie du formulaire est conservée (UX-DR28, UX-DR35) et le compte reste non vérifié
**And** le nombre de tentatives de validation est plafonné avant invalidation du code.

**AC4 — Renvoi limité et non-divulgation**
**Given** la demande de renvoi du code
**When** je clique « Renvoyer »
**Then** le renvoi est limité (compte à rebours affiché avant nouvel envoi, plafond de renvois — FR-P27, UX-DR21) et tout dépassement est rejeté avec un message daté
**And** la réponse d'inscription ne révèle pas si l'email est déjà enregistré (NFR-P9).

---

## ⚠️ TROIS BLOCAGES À TRANCHER AVANT D'ÉCRIRE UNE LIGNE

Ces trois points ne sont pas des détails d'implémentation : chacun change ce que la story livre. Les deux premiers doivent être arbitrés **par Oscard**, le troisième est une conséquence mécanique à assumer.

### ⛔ BLOCAGE 1 — Aucun transport e-mail n'existe, et il appartient à la Story 8.1

L'AC1 dit « un OTP à 6 chiffres à durée de vie limitée est **envoyé à l'email** ». Vérification faite dans l'arbre réel :

| Cherché | Résultat |
|---|---|
| Dépendance mail dans `backend/pom.xml` | **aucune** (`spring-boot-starter-mail` absent) |
| `JavaMailSender`, `MimeMessage`, SMTP dans `src/` | **aucune occurrence** |
| `spring.mail.*` dans `application.yml` | **aucune** |
| Service SMTP dans `docker-compose*.yml` (Mailhog/Mailpit/Maildev) | **aucun** |

Ce n'est pas un oubli : **la Story 8.1 est un spike dont c'est l'objet exact** — « choix du fournisseur email/SMS et port d'envoi », avec pour AC « une interface unique (type `NotificationSender`) isole le fournisseur derrière un adaptateur, avec un mode sandbox ». Le choix porte sur la **délivrabilité par corridor ZLECAf**, les coûts et la conformité : ce n'est pas une décision qu'une story d'onboarding a vocation à prendre au passage.

C'est le même piège que l'AC5 de la Story 2.3 (« audit dans le harnais existant » — le harnais n'existait pas). Il a été traité en livrant explicitement moins, et en le disant. Trois voies ici :

1. **Port + outbox + adaptateur de développement.** Définir `OtpNotifier` (ou réutiliser la forme `NotificationSender` que 8.1 figera), persister l'envoi comme une ligne d'outbox — c'est le patron **AD-22** déjà retenu ailleurs dans le backlog (« un événement de notification est persisté […] les canaux de livraison arrivent en Epic 8 ») — et brancher un adaptateur non-production qui journalise. **Conséquence à écrire noir sur blanc : aucun e-mail n'atteint une vraie boîte dans cette story.** Le parcours est complet et testable de bout en bout, mais la dernière marche est un journal, pas un serveur SMTP.
2. **Transport SMTP réel maintenant** : `spring-boot-starter-mail` + Mailpit dans le compose de développement. Le parcours devient démontrable ; en revanche on ajoute une dépendance et une décision d'infrastructure que 8.1 devra peut-être défaire, et cela ne règle **pas** la production (Mailpit n'est pas un fournisseur).
3. **Reporter la story** derrière 8.1 — mais 8.1 est à six epics de distance, et l'Epic 2 est la fondation d'onboarding.

**Recommandation : voie 1.** Elle respecte AD-22, ne préempte pas le spike 8.1, et laisse le parcours entièrement asservissable. Elle exige en contrepartie de répondre à : *comment le développeur et les tests récupèrent-ils le code ?* → par le port en test (adaptateur de capture), **jamais** par un endpoint de lecture d'OTP, fût-il « de développement » : ce serait une porte dérobée d'authentification, et l'Epic 1 a passé un epic à en fermer.

### ⛔ BLOCAGE 2 — L'AC4 exige de casser un contrat d'API en service

`AuthService.register()` fait aujourd'hui **exactement l'inverse** de ce que l'AC4 demande :

```java
if (users.existsByEmail(email)) {
    throw new ConflictException("Email already registered");   // ← oracle d'énumération
}
...
return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));  // ← session immédiate
```

Deux ruptures nécessaires, et leur rayon de souffle est mesuré :

| Rupture | Exigée par | Impact constaté |
|---|---|---|
| Le 409 « Email already registered » disparaît | AC4 (NFR-P9) | `AuthRateLimitFilter` compte aujourd'hui ce 409 comme un échec (son javadoc le dit : « 409 conflit/énumération au register »). Ce signal disparaît avec lui. |
| `register` n'émet plus de JWT (compte non vérifié) | AC1 + AC2 | **8 classes de test** utilisent `register()` comme fabrique de compte authentifié : `AntiEnumerationIntegrationTest`, `PasswordAndRevocationIntegrationTest` (11 occurrences), `AuthServiceTest`, `AuthRateLimitIntegrationTest`, `ChangePasswordRateLimitIntegrationTest`, plus 3 autres. Côté frontend, `stores/auth.js:87` fait `applySession(await registerUser(payload))`. |

**À ne pas faire :** rustiner les tests un par un pour qu'ils repassent. Ils ont besoin d'un compte *authentifié* ; il leur faut donc une **fabrique de test** qui crée un compte déjà vérifié (via le repository ou un helper de support), pas un `register` détourné. C'est un refactor de support de test, à faire d'un bloc.

**Nouveau vecteur d'abus à couvrir, créé par la suppression du 409 :** si `register` répond toujours « vérifiez votre boîte », n'importe qui peut déclencher un envoi vers **l'adresse d'un tiers**, en boucle. Le plafond ne peut donc pas porter seulement sur l'origine : il doit aussi porter sur **l'adresse cible**. Sans cela, l'anti-énumération achète le silence au prix d'un canal de harcèlement.

### ⚠️ BLOCAGE 3 — « Gestionnaire » n'existe pas encore comme notion

FR-P9 demande que l'utilisateur créateur soit **gestionnaire** de l'entreprise. Or les rôles internes d'entreprise sont le sujet de la **Story 2.5** (« Multi-utilisateur — rôles internes et invitation de collègues »). `Role.java` vaut `{BUYER, SELLER, ADMIN}` : ce sont des rôles **plateforme**, pas des rôles **dans l'entreprise** — ne pas les confondre, et surtout ne pas ajouter `MANAGER` à cette énumération (ce serait mélanger deux axes, et l'AD-21 réserve le routage des espaces à cette énumération-là).

Livrer ici la **forme minimale** : un rattachement porteur d'un statut de gestionnaire, que 2.5 étendra. Ne pas construire le modèle de rôles internes complet en avance — 2.5 le fera avec ses invitations.

---

## Tasks / Subtasks

- [x] **T0 — Arbitrer les blocages 1 et 2** (préalable bloquant) — **TRANCHÉ le 2026-07-28 par Oscard**

  **Blocage 1 → VOIE 1 : port + outbox + adaptateur de développement.**
  Conforme à AD-22, déjà retenu ailleurs dans le backlog (« un événement de notification est
  persisté […] les canaux de livraison arrivent en Epic 8 »). Ne préempte pas le spike 8.1,
  qui reste seul juge du fournisseur et de la délivrabilité par corridor.

  **Ce que cela veut dire, sans arrondir : aucun e-mail n'atteindra une vraie boîte dans
  cette story.** Le parcours d'inscription est complet, asservissable de bout en bout, et sa
  dernière marche est une ligne d'outbox plus un journal. C'est à écrire dans les notes de
  complétion ET à porter au ledger à la clôture, comme les deux dettes de la 2.3 — un statut
  `done` ne doit pas laisser croire que l'OTP arrive chez l'utilisateur.

  **Interdiction ferme qui accompagne la décision :** les tests et le développement
  récupèrent le code **par le port** (adaptateur de capture). Jamais par un endpoint de
  lecture d'OTP, fût-il gardé par un profil « dev » — ce serait une porte dérobée
  d'authentification, et `DevApiSurfaceIntegrationTest` / `ProductionApiSurfaceIntegrationTest`
  existent précisément pour interdire ce genre de surface.

  **Blocage 2 → refactor du support de test d'un bloc** (T7), avant de toucher `register`.
  Aucune classe de test n'est rustinée individuellement pour repasser au vert : elles ont
  besoin d'un compte **authentifié**, pas d'un `register` détourné.

- [x] **T1 — Lire avant d'écrire** (préalable)
  - [x] `backend/.../service/AuthService.java` — `register`, `login`, `changePassword`, `revokeSessions` : ce qui existe, et la **liste blanche de rôles** qui satisfait déjà FR-P16 (ne pas la réécrire, ne pas la régresser).
  - [x] `backend/.../security/AuthRateLimitFilter.java` + `AuthRateLimiter.java` — clés compte/origine, backoff, **état en mémoire process** (mono-instance ; la limitation distribuée relève de 11.3).
  - [x] `backend/.../domain/{User,Company,Role,ErrorCode}.java` — `User.company_id` et l'entité `Company` **existent déjà** (`name`, `registrationNumber` UNIQUE, `country`).
  - [x] `backend/.../security/PasswordPolicy.java` — autorité unique de la politique, appelée avant le hachage.
  - [x] `frontend/src/stores/auth.js`, `src/api/auth.js`, `src/views/AuthView.vue` — le formulaire, le mode login/register, et `safeRedirect`.
  - [x] `frontend/src/router/index.js` + `spaces.js` — la garde livrée en 2.3 : un compte non vérifié devra être routé sans casser la redirection profonde de 1.9 ni le cloisonnement par rôle.

- [x] **T2 — Schéma et migration** (AC: 1)
  - [x] Migration Flyway **`V10__…`** (V1..V9 existent) — une seule migration pour cette story.
  - [x] Table OTP : **hash** du code (jamais le code en clair — c'est une credential), `expires_at`, compteur de tentatives, marque d'usage unique, lien utilisateur.
  - [x] Table consentements : version du document, horodatage **serveur** (`TIMESTAMPTZ`, AD-11), utilisateur.
  - [x] Rattachement User↔Company avec statut de gestionnaire (forme minimale — voir blocage 3).
  - [x] Drapeau de vérification sur `users`.

- [x] **T3 — Inscription** (AC: 1,4)
  - [x] `register` crée un compte **non vérifié**, sans émettre de JWT.
  - [x] Réponse **strictement identique** que l'email existe ou non (NFR-P9) : ni code, ni message, ni forme différente. Aucune entreprise ni consentement créés dans la branche « email connu ».
  - [x] Plafond d'envoi keyé sur **l'adresse cible ET l'origine** (voir blocage 2).
  - [x] Consentement horodaté serveur ; entreprise créée et reliée, créateur gestionnaire.
  - [x] La liste blanche de rôles reste en place (FR-P16).

- [x] **T4 — Vérification OTP** (AC: 2,3)
  - [x] Comparaison **constant-time** du hash ; code à usage unique ; TTL.
  - [x] Plafond de tentatives **persisté** (il doit survivre à un redémarrage — un compteur en mémoire ne protège pas un code qui, lui, vit en base).
  - [x] Succès → compte vérifié + session émise par le `JwtService` existant.
  - [x] Un compte non vérifié qui se connecte est renvoyé vers l'étape OTP, **jamais** dans l'application.
  - [x] Codes d'erreur via l'enum `ErrorCode` et l'enveloppe unique du `GlobalExceptionHandler` — jamais de format parallèle.

- [x] **T5 — Renvoi limité** (AC: 4)
  - [x] Plafond de renvois + délai entre deux envois ; dépassement rejeté avec un message **daté**.
  - [x] Le compte à rebours affiché par le front vient du **serveur**, pas d'un minuteur local : l'heure serveur est la source de vérité (AD-11), et un minuteur client se contourne en rechargeant la page.

- [ ] **T6 — Front : inscription, OTP, consentement** (AC: 1,2,3,4)
  - [ ] Formulaire 1 colonne, validation à la volée (UX-DR35), case de consentement.
  - [ ] Écran OTP : champ acceptant **le collage** (UX-DR39), erreur i18n **sous le champ**, saisie conservée (UX-DR28).
  - [ ] Compte à rebours de renvoi et plafond visibles (UX-DR21).
  - [ ] Assembler la bibliothèque 2-2 (`AppButton`, `AppCard`, `AppSkeleton`) — ne rien réinventer.
  - [ ] `stores/auth.js` : `register` ne pose plus de session ; le parcours passe par l'étape OTP.

- [x] **T7 — Refactor du support de test** (blocage 2)
  - [x] Fabrique de compte vérifié pour les 8 classes qui utilisaient `register()` comme raccourci.
  - [x] `AntiEnumerationIntegrationTest` doit rester vert **et** s'étendre à la nouvelle surface d'inscription.

- [ ] **T8 — Tests** (AC: 1,2,3,4)
  - [ ] Indistinguabilité de la réponse d'inscription (email connu / inconnu) — **par égalité du corps ET du statut**, à l'image du gabarit backend existant.
  - [ ] OTP : correct, erroné, expiré, rejoué, plafond de tentatives atteint, plafond de renvois atteint.
  - [ ] Compte non vérifié refusé à la connexion et routé vers l'OTP.
  - [ ] Non-régression : `authRedirect.spec.js` (1.9) et `guards.spec.js` (2.3) restent verts.
  - [ ] `npm run lint`, `verify:no-demo`, `verify:pwa` verts ; suite frontend **422 tests au départ**.

## Dev Notes

### Ce qui existe déjà et ne doit pas être réécrit

| Besoin de la story | Déjà livré | Où |
|---|---|---|
| Politique de mot de passe | Story 1.6 — autorité unique | `security/PasswordPolicy.java`, appelée avant le hachage |
| ADMIN inaccessible à l'inscription (FR-P16) | Story 1.1 — **liste blanche**, pas liste noire | `AuthService.register()` — `role != BUYER && != SELLER` → rejet |
| Émission de session | `JwtService` + `tokenVersion` (révocation, Story 1.6) | `security/JwtService.java` |
| Limitation de débit auth | Story 1.3 — clés compte + origine, backoff 30 s → 15 min | `security/AuthRateLimiter.java` |
| Enveloppe d'erreur unique + `ErrorCode` | Story 1.10 | `GlobalExceptionHandler`, `domain/ErrorCode.java` |
| Entité entreprise et lien utilisateur | POC | `domain/Company.java`, `User.company_id` |
| Composants UI, i18n, shells, guards | Stories 2-1 / 2-2 / 2-3 | `components/`, `layouts/`, `router/spaces.js` |

**`AuditService` ne convient pas ici** : ses méthodes exigent toutes un `transactionId`. Le consentement et la vérification de compte sont des faits **de compte**, pas de transaction — ils ont leur propre table (T2), pas une entrée détournée dans le journal d'audit escrow.

### Règles héritées, non négociables

- **Aucune chaîne littérale** côté front — la garde scanne gabarits, scripts, littéraux simples/doubles/backticks.
- **Aucune clé i18n brute rendue** — passer par `@/i18n/labels` ; `$t(null)` LÈVE.
- **Aucune ombre hors `shadow-floating`** (classe ET propriété CSS, `boxShadow` en ligne compris).
- **Gouttière** : toute région de contenu porte `p-4 lg:p-6` (décision tranchée en 2.3, écrite dans `style.css`).
- **Un seul `<main>` par écran** — le shell le porte ; une vue routée dans un espace n'en déclare pas.
- **`type="button"`** sur tout `<button>` qui n'est pas un envoi de formulaire.
- **Enums et codes machine en anglais** ; les libellés français sont de l'i18n, jamais des valeurs stockées.
- **`TIMESTAMPTZ`, heure serveur source de vérité** (AD-11) ; ids `IDENTITY`.
- **Secrets par variables d'environnement** uniquement ; comparaison de secret en **temps constant**.
- **Français avec accents** ; **vérification par mutation** sur toute garde livrée.

### Previous story intelligence — ce que 2-1, 2-2 et 2-3 ont coûté

Six campagnes de revue au total. Ce qui s'applique ici :

- **Une garde qu'aucune assertion ne relit n'est pas une garde.** Quatre gardes se sont révélées fausses sur ces trois stories — sentinelle dans un commentaire supprimée par la minification, garde d'élévation aveugle à la propriété CSS, garde anti-prose aveugle aux backticks, et une assertion visant un `data-testid` inexistant (donc satisfaite par le vide). **Chaque fois, seule la mutation l'a montré.** Sur une story d'OTP, cela vise en priorité : le plafond de tentatives, l'usage unique, l'expiration.
- **Un contrat annoncé en commentaire peut être faux.** `spaceForRole` promettait « rôle inconnu → aucun espace » et rendait une fonction pour `role='constructor'`, l'objet littéral héritant d'`Object.prototype`. **Toute table de correspondance indexée par une valeur venue du client doit être lue par `Object.hasOwn`** — ou construite sans prototype.
- **Un composant testé isolément n'est pas un composant branché.** `ClientShell` passait tous ses tests sans qu'aucune route ne le rende. Vérifier le parcours OTP **monté**, pas seulement ses fonctions.
- **Un `try/catch` ne garde que contre ce qui lève.** `Intl.format(NaN)` rend « $NaN » ; `new Date('x').toLocaleString()` rend « Invalid Date ». Un compte à rebours mal alimenté affichera une valeur absurde sans jamais lever.
- **Ne jamais restaurer par `git checkout --`** un fichier porteur de travail non commité.
- **Une case cochée n'est pas une preuve** — un compte de tests annoncé s'est révélé faux deux fois.
- **Prévoir la revue comme la moitié du travail.** Sur une story qui crée des comptes et manipule une credential, c'est un plancher.

### Git intelligence

Cinq derniers commits : `eb5680c` (2.3 done + dettes au ledger), `e336b7e` (correctifs de revue 2.3), `1714ff5` (2.3), et la clôture de 2.2. Le rythme établi est : implémentation → revue 3 couches → correctifs prouvés par mutation → clôture avec dettes explicitement portées au ledger. Le modèle des couches a été **haiku** sur 2-2 et 2-3 : sur 2-3, deux des trois couches ont produit du volume sans vérifier (3 appels d'outil chacune) et une seule a réellement travaillé (39 appels). À rappeler au moment de choisir.

### File structure

- `backend/src/main/resources/db/migration/V10__*.sql` — NOUVEAU (une seule migration)
- `backend/.../domain/` — entités OTP, consentement, rattachement (NOUVELLES) ; `User` MODIFIÉ (drapeau vérifié)
- `backend/.../service/AuthService.java` — MODIFIÉ (rupture de contrat, voir blocage 2)
- `backend/.../web/AuthController.java` + `web/dto/AuthDtos.java` — MODIFIÉS (endpoints OTP)
- `backend/.../security/AuthRateLimitFilter.java` — MODIFIÉ (plafond sur l'adresse cible)
- `backend/.../ports/` ou équivalent — port d'envoi OTP (NOUVEAU, forme à figer avec le blocage 1)
- `frontend/src/views/` — écran OTP (NOUVEAU) ; `AuthView.vue` MODIFIÉ
- `frontend/src/stores/auth.js`, `src/api/auth.js` — MODIFIÉS
- `frontend/src/i18n/{fr,en}.json` — MODIFIÉS
- Tests colocalisés ; support de test backend à refactorer (T7)

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 2.4`] — AC d'origine
- [Source: `epics.md#Story 8.1`] — spike du port d'envoi email/SMS, **origine du blocage 1**
- [Source: `epics.md#Story 2.5`] — rôles internes d'entreprise, **origine du blocage 3**
- [Source: `.../ARCHITECTURE-SPINE.md`] — AD-5 (audit), AD-11 (heure serveur), AD-21 (rôles/espaces), AD-22 (outbox)
- [Source: `_bmad-output/implementation-artifacts/2-3-…md`] — story précédente, ses trois défauts de revue et ses deux dettes
- [Source: `_bmad-output/project-context.md`] — versions épinglées, conventions Java/Vue, règle de mutation

## Dev Agent Record

### Agent Model Used

claude-opus-5 (interactif, flux BMad classique)

### État à l'arrêt du 2026-07-28 — REPRENDRE ICI

**Backend : terminé et prouvé.** T1–T5 et T7 complets.
**Frontend : câblé jusqu'à l'écran OTP, tests non écrits.** T6 partiel, T8 partiel.

Ce qui reste, dans l'ordre :

1. **T6 (fin)** — rien de bloquant, le parcours est en place (`VerifyEmailView.vue`, route
   publique `verify-email`, store `verify`/`resend`, `AuthView` avec raison sociale et
   consentement). À relire : la vue n'a **aucun test**, donc rien ne prouve encore la
   normalisation du collage, la conservation de la saisie, ni le compte à rebours.
2. **T8 (frontend)** — tests de `VerifyEmailView` (collage « 123 456 » → `123456`, message
   d'erreur SOUS le champ et lié par `aria-describedby`, saisie conservée après échec,
   bouton de renvoi désactivé pendant le décompte) et du store (`register` n'ouvre PAS de
   session, `verify` en ouvre une, `resend` lit `Retry-After` sans produire de NaN).
3. **Vérifications finales** — suite complète des deux côtés, `npm run lint`,
   `verify:no-demo`, `verify:pwa`, `scripts/check-encoding.py`, puis statut `review`.

**Comptes de tests réellement observés** (ne pas les recopier sans revérifier — un compte
annoncé s'est révélé faux deux fois sur cet epic) :
- Frontend : **422 / 29 fichiers**, verts. Inchangé : aucun test frontend n'a encore été
  ajouté pour cette story.
- Backend : suite complète **489 verte** AVANT l'ajout des nouvelles classes. Depuis :
  `AuthServiceTest` 13 (était 10), `RegistrationOtpIntegrationTest` 12 (nouveau),
  `OutboxEmailVerificationSenderTest` 3 (nouveau) — tous verts individuellement. **La suite
  complète n'a pas été relancée depuis**, le total est donc à établir, pas à annoncer.

### Debug Log References

**UN VRAI DÉFAUT, trouvé par le test de l'AC3 — le plafond de tentatives ne comptait rien.**
`verifyEmail` est `@Transactional` ; lever l'`UnauthorizedException` juste après avoir
incrémenté `attempts` **annulait l'incrément**, Spring défaisant la transaction sur toute
exception non vérifiée. Le compteur repartait de zéro à chaque essai et le code restait
valide jusqu'à son expiration : forçage illimité sur un espace de 10^6 pendant 15 minutes.
Le code se lisait comme correct. Corrigé par `@Transactional(noRollbackFor =
ApiExceptions.CodedException.class)` ; **rouge sans le correctif**, vert avec.

**UNE DE MES GARDES ÉTAIT CREUSE — cinquième fois sur cet epic.** Le hachage du mot de passe
est calculé AVANT le branchement sur l'existence de l'adresse, pour que les deux cas coûtent
le même temps : sans cela, l'oracle fermé côté code d'erreur se rouvre au chronomètre. La
mutation qui replace `encode(...)` après le `existsByEmail` est passée **VERTE** — aucune
assertion ne relisait cette garde, et une comparaison de corps de réponse ne peut par
construction pas voir un écart de temps. Remplacé par une assertion sur la CAUSE plutôt que
sur l'effet (`verify(encoder).encode(...)` même quand l'adresse est connue), déterministe là
où un chronométrage serait instable en CI. Rouge sous mutation.

**`mvn test-compile` a répondu BUILD SUCCESS sur des tests qui ne compilaient pas.** Maven
n'avait pas recompilé les classes de test après le changement de signature d'`AuthService` ;
la rupture n'est apparue qu'en `NoSuchMethodError` à l'exécution. Il a fallu un `clean` pour
voir les six vraies erreurs. Corollaire pour la suite de cette story : ne pas conclure d'un
`test-compile` vert que le rayon de souffle est nul.

**Trois bogues dans mes propres tests, corrigés :**
- comparaison d'enveloppes octet pour octet impossible telle quelle — `GlobalExceptionHandler`
  y insère `Instant.now()`, donc deux réponses indistinguables diffèrent toujours. Neutralisé
  par `withoutTimestamp(...)`, qui compare ce qu'un attaquant peut exploiter et pas l'heure ;
- assertion sur l'outbox posée dans un contexte où l'adaptateur de capture REMPLACE
  l'adaptateur outbox : elle ne regardait rien. Déplacée dans
  `OutboxEmailVerificationSenderTest`, seul endroit où cet adaptateur tourne réellement ;
- `LazyInitializationException` sur `User.company` hors session — test annoté `@Transactional`.

### Completion Notes List

- **Décision T0 appliquée (voie 1)** : port `EmailVerificationSender` + outbox AD-22 +
  adaptateur d'attente. **Aucun e-mail n'atteint une vraie boîte** : `delivered_at` reste nul
  et c'est l'état réel du système. À porter au ledger à la clôture.
- **Le port a été déplacé** d'un paquet `ports/` racine vers `service/notification/`, pour
  suivre la convention réelle du dépôt (`service/storage/EvidenceStorage`).
- **Aucun endpoint de lecture d'OTP**, et il ne doit pas en exister : les tests lisent le
  code par le port (`CapturingEmailVerificationSender`). Un endpoint « de développement »
  serait une porte dérobée d'authentification qu'une erreur de profil suffit à publier.
- **202 sans corps à l'inscription, pas 201** : un « Created » affirmerait qu'un compte vient
  d'être créé, donc que l'adresse était libre.
- **Oracle résiduel assumé (AC4)** : les deux exigences de l'AC se contredisent en partie —
  qui déclenche le 429 de quota apprend qu'un code est en attente pour cette adresse. Le
  quota porte sur le DESTINATAIRE, ce qui ferme le canal de harcèlement ouvert par
  l'anti-énumération, mais le rend observable. Le fermer demanderait de stocker un quota pour
  des adresses arbitraires, soit un autre déni de service. À porter au ledger.
- **Correction d'une affirmation de la story elle-même** : les Dev Notes disaient
  qu'`AuditService` ne convenait pas aux faits de compte. C'est faux —
  `recordAccountSecurityEvent` accepte un `transactionId` nul. Il est utilisé.
- **Migration V10 : les comptes préexistants sont passés `email_verified = TRUE`.** Les
  laisser au défaut `FALSE` les aurait tous bloqués derrière un code qu'aucun d'eux ne peut
  recevoir.

### File List

**Nouveaux — backend**
- `backend/src/main/resources/db/migration/V10__email_verification_consent_company_link.sql`
- `backend/src/main/java/com/zlecaf/escrow/domain/{EmailVerificationCode,LegalConsent,NotificationOutboxEntry}.java`
- `backend/src/main/java/com/zlecaf/escrow/repository/{EmailVerificationCode,LegalConsent,NotificationOutbox}Repository.java`
- `backend/src/main/java/com/zlecaf/escrow/service/notification/{EmailVerificationSender,OutboxEmailVerificationSender}.java`
- `backend/src/test/java/com/zlecaf/escrow/support/{VerifiedAccounts,CapturingEmailVerificationSender}.java`
- `backend/src/test/java/com/zlecaf/escrow/security/RegistrationOtpIntegrationTest.java`
- `backend/src/test/java/com/zlecaf/escrow/service/notification/OutboxEmailVerificationSenderTest.java`

**Nouveaux — frontend**
- `frontend/src/views/VerifyEmailView.vue`

**Modifiés — backend**
- `service/AuthService.java` (register refondu, verifyEmail, resendVerificationCode, issueCode)
- `domain/{User,ErrorCode}.java`, `web/{ApiExceptions,GlobalExceptionHandler,AuthController}.java`
- `web/dto/AuthDtos.java`, `config/SecurityConfig.java`
- `src/test/.../{AuthServiceTest,PasswordAndRevocationIntegrationTest,ChangePasswordRateLimitIntegrationTest,AntiEnumerationIntegrationTest}.java`

**Modifiés — frontend**
- `src/api/auth.js`, `src/stores/auth.js`, `src/views/AuthView.vue`, `src/router/index.js`
- `src/i18n/{fr,en}.json`

### Change Log

| Date | Version | Description |
|---|---|---|
| 2026-07-28 | 0.2 | Backend complet : OTP, consentement, rattachement entreprise, anti-énumération à l'inscription, port d'envoi + outbox AD-22, refactor du support de test. Correctif du plafond de tentatives annulé par le rollback transactionnel. Frontend câblé, tests frontend restant à écrire. |
| 2026-07-28 | 0.1 | Création du contexte de développement. Trois blocages documentés. |
