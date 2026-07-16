---
title: 'Story 1.5 — Interface PWA : dépôt & liste des preuves'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: '7b08a54ba82dd469108d2fb77446adabf74b504d'
final_revision: '9d6c31f'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** Les endpoints preuve (dépôt 1.2, liste 1.3, téléchargement 1.4) existent mais ne sont exposés dans aucune UI : un utilisateur de la PWA ne peut ni déposer une pièce ni consulter le dossier sans appeler l'API à la main. La fonctionnalité est invisible pour l'humain.

**Approach:** Sur l'écran de détail transaction (`/escrow/:id`, `TransactionDetailView.vue`), ajouter deux composants Vue 3 `<script setup>` — `EvidenceDeposit` (sélection fichier + validation client miroir du serveur + commentaire optionnel + envoi multipart) et `EvidenceList` (liste chronologique contradictoire : déposant, horodatage, type, taille, commentaire, statut, action **télécharger**) — câblés via un module API `src/api/evidence.js` et un store Pinia `stores/evidence.js`, suivant strictement les conventions front existantes (Tailwind v4, flux vue → store → api, JWT auto-attaché).

## Boundaries & Constraints

**Always:**
- **Le serveur est la seule autorité.** La validation client (type MIME, taille ≤ 10 485 760 octets, extension) est un **confort d'UX pré-envoi** ; toute erreur `400/403/404/409` renvoyée par le serveur est affichée telle quelle à l'utilisateur (via le champ `message` de l'enveloppe d'erreur). Le client ne rejoue jamais la décision serveur ni ne masque un refus serveur.
- **Contrat multipart figé (AD-13).** L'envoi POST utilise `FormData` avec les noms de parts **exacts** : chaque fichier ajouté sous la clé `files` (répétée, jamais `files[]`), `comment` (optionnel), `clientCapturedAt` (ISO-8601, optionnel). Aucun autre nom de champ.
- **Flux d'architecture front respecté.** Un composant n'appelle jamais `axios`/`apiClient` directement : `vue (composant) → action de store (`stores/evidence.js`) → module API (`src/api/evidence.js`) → `apiClient``. Le JWT est attaché par l'intercepteur existant ; ne jamais poser l'en-tête `Authorization` à la main.
- **Visibilité contradictoire (FR-9).** La liste affiche **toutes** les pièces de la transaction, quelle qu'en soit l'origine, y compris celles au statut `WITHDRAWN` (affichées avec un badge « Withdrawn », jamais masquées). L'action télécharger est disponible sur **chaque** pièce.
- **Téléchargement authentifié en pièce jointe.** Le téléchargement passe par `apiClient.get(url, { responseType: 'blob' })` (pour porter le JWT), puis déclenche l'enregistrement via un `<a download>` synthétique avec un `URL.createObjectURL` **révoqué après usage**. Jamais un `<a href>` direct vers l'API (il ne porterait pas le JWT) et jamais d'ouverture *inline*.
- **Fenêtre de dépôt fondée sur l'état.** Le composant de dépôt n'est monté/actif que si `transaction.state ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}` (confort UX) ; hors de ces états il est masqué. La liste, elle, est toujours affichée dès qu'une transaction est chargée.
- **Limites miroir centralisées.** Les constantes serveur (taille max `10485760`, MIME `image/jpeg`/`image/png`/`application/pdf`, extensions `.jpg/.jpeg/.png/.pdf`) sont définies **une seule fois** dans `src/utils/evidence.js` et réutilisées par le composant de dépôt (validation) et la liste (affichage) — pas de duplication.
- **Conventions UI ratifiées.** `<script setup>` + Composition API en JS pur ; Tailwind v4 utilitaire inline (aucun `<style>` scoped) ; palette `brand-*` ; cartes `rounded-2xl border border-gray-200 bg-white p-5 shadow-sm` ; réutiliser `StateBadge`/tokens existants ; **textes d'UI en anglais** (le reste de l'app est en anglais, pas d'i18n).

**Block If:**
- `npm run build` ne peut pas s'exécuter (toolchain Vite absente/cassée) ⇒ HALT `blocked` : la vérification automatisée de la story (build propre) est non négociable.

**Never:**
- **Aucune action « retirer » / withdraw** : le retrait logique est l'Epic 2 (Story 2.3) et **aucun endpoint backend de retrait n'existe** — câbler un bouton retirer appellerait une API inexistante. Hors périmètre 1.5.
- **Aucun dépôt hors-ligne / mise en file du binaire** : le portage binaire en IndexedDB et le rejeu offline sont l'Epic 4 (FR-15/16). L'upload de 1.5 est **online uniquement**. (La liste peut se recharger sur l'événement `escrow:sync` existant, mais l'upload n'enfile jamais de `Blob`.)
- Aucune modification des endpoints backend, DTO, ou logique serveur. Aucun nouveau framework de test front (aucun n'existe ; ne pas en introduire dans cette story). Aucun accès direct au stockage, aucune dérivation du nom de stockage. Aucune pagination, aucun tri « intelligent » (la liste arrive déjà chronologique de 1.3). Pas de gold-plating (pas d'aperçu d'image, pas de drag-and-drop, pas de barre de progression d'upload).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Affichage liste | Transaction chargée avec N pièces | Liste chronologique : déposant (uploaderType, « (you) » si `uploadedByUserId === auth.user.id`), horodatage `createdAt`, type MIME, taille lisible, commentaire, badge statut, bouton télécharger sur chaque ligne | Erreur réseau → message d'erreur, pas de crash |
| Liste vide | Transaction sans pièce | État vide explicite (« No evidence yet. ») | — |
| Pièce retirée visible | Pièce `status = WITHDRAWN` | Ligne affichée normalement avec badge « Withdrawn », toujours téléchargeable | — |
| Dépôt nominal | État ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}, fichier PDF/JPG/PNG ≤ 10 MiB | `POST` multipart (`files`, `comment?`, `clientCapturedAt?`) → 201 ; la liste se recharge, le formulaire se réinitialise | — |
| Type non autorisé (client) | Fichier `.txt`/`.docx` sélectionné | Rejeté **avant envoi**, message client « Only JPG, PNG or PDF files are allowed. » | Bouton envoyer désactivé |
| Taille hors borne (client) | Fichier > 10 485 760 octets ou vide (0 octet) | Rejeté **avant envoi**, message client sur la taille | Bouton envoyer désactivé |
| Refus serveur | Le serveur renvoie 400/403/409 malgré la pré-validation | Le `message` de l'enveloppe d'erreur est affiché ; formulaire non réinitialisé | Message serveur affiché tel quel |
| Dépôt hors fenêtre d'état | État ∈ {RELEASED, REFUNDED} (ou INITIATED) | Composant de dépôt **non monté** ; seule la liste s'affiche | — |
| Téléchargement | Clic « Download » sur une pièce | `GET .../download` en `blob` (JWT porté) → enregistrement via `<a download>` nommé `originalFilename`, ObjectURL révoqué | Échec → message d'erreur non bloquant |

</intent-contract>

## Code Map

- `frontend/src/views/TransactionDetailView.vue` -- MODIFIER : monter `<EvidenceDeposit>` (uniquement si `state ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}`) et `<EvidenceList>` dans une nouvelle carte après la carte d'audit ; charger les preuves via le store evidence dans `onMounted` (+ réécoute `escrow:sync`). Reçoit déjà `id` en prop. Ne pas toucher aux sections existantes.
- `frontend/src/components/EvidenceDeposit.vue` -- CRÉER : formulaire de dépôt (`<input type="file" accept=".jpg,.jpeg,.png,.pdf" multiple>`, aperçu nom/type/taille, validation client via `utils/evidence.js`, champ commentaire optionnel, `submitting`), suit le patron de `NewTransactionModal.vue`. `defineProps({ transactionId })`, `defineEmits(['uploaded'])`.
- `frontend/src/components/EvidenceList.vue` -- CRÉER : rend la liste chronologique (déposant/horodatage/type/taille/commentaire/statut + bouton télécharger). `defineProps({ transactionId })`. Consomme l'état du store evidence ; déclenche le téléchargement via l'action de store.
- `frontend/src/api/evidence.js` -- CRÉER : `listEvidence(id)` (`GET /api/v1/escrow/:id/evidence`), `uploadEvidence(id, formData)` (`POST .../evidence`, en-tête `Content-Type: multipart/form-data`), `downloadEvidence(id, evidenceId)` (`GET .../evidence/:eid/download`, `responseType: 'blob'`, retourne le `Blob`). Enveloppe `apiClient` de `src/api/client.js`, retourne `res.data`.
- `frontend/src/stores/evidence.js` -- CRÉER : store Pinia (style Options, comme `escrow.js`) — state `{ items, loading, error, uploading }` ; actions `loadEvidence(id)`, `uploadEvidence(id, { files, comment, clientCapturedAt })` (construit le `FormData` avec les parts figées), `downloadFile(id, item)` (récupère le blob et déclenche l'enregistrement navigateur).
- `frontend/src/utils/evidence.js` -- CRÉER : source unique des limites miroir serveur — `MAX_EVIDENCE_SIZE = 10485760`, `ALLOWED_MIME_TYPES`, `ALLOWED_EXTENSIONS`, helpers `formatBytes(bytes)`, `uploaderLabel(item, transaction, currentUserId)`, `validateFile(file) → string|null` (message d'erreur client ou null).
- `frontend/src/api/client.js` -- LIRE : instance axios partagée (`apiClient`), base `VITE_API_BASE`, intercepteur JWT + gestion 401. API figée, ne pas modifier.
- `frontend/src/stores/auth.js` -- LIRE : `useAuthStore().user` (`id`, `email`, `role`) pour marquer « (you) ».
- `frontend/src/utils/stateMachine.js` -- LIRE : états et `StateBadge`/tokens réutilisables pour la fenêtre de dépôt.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/utils/evidence.js` -- Créer les constantes miroir + `formatBytes` + `uploaderLabel` + `validateFile` -- source unique des limites, réutilisée dépôt & liste.
- [x] `frontend/src/api/evidence.js` -- Créer `listEvidence`/`uploadEvidence`/`downloadEvidence` sur `apiClient` -- couche API mince, contrat multipart figé, download en blob.
- [x] `frontend/src/stores/evidence.js` -- Créer le store (state + `loadEvidence`/`uploadEvidence`/`downloadFile`) -- orchestre l'état, construit le `FormData`, déclenche l'enregistrement navigateur.
- [x] `frontend/src/components/EvidenceList.vue` -- Créer la liste chronologique contradictoire + bouton télécharger -- rend toutes les pièces (y compris WITHDRAWN), action download par ligne.
- [x] `frontend/src/components/EvidenceDeposit.vue` -- Créer le formulaire de dépôt avec validation client -- confort UX pré-envoi, POST multipart, émet `uploaded`.
- [x] `frontend/src/views/TransactionDetailView.vue` -- Monter les deux composants + charger les preuves (onMounted + `escrow:sync`), dépôt conditionné à l'état -- intègre la fonctionnalité au détail transaction.

**Acceptance Criteria:**
- Given une transaction à l'état `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED` dont j'ouvre le détail, when j'utilise le composant de dépôt avec un JPG/PNG/PDF ≤ 10 Mo, then la pièce est envoyée en multipart (`files`/`comment?`/`clientCapturedAt?`), le serveur répond 201, et la liste se rafraîchit avec la nouvelle pièce sans rechargement de page.
- Given un fichier de type non autorisé ou > 10 485 760 octets (ou vide), when je le sélectionne, then il est signalé côté client **avant** tout envoi et la soumission est bloquée — le serveur restant l'autorité pour tout ce qui passe cette barrière.
- Given une transaction comportant des pièces (dont certaines `WITHDRAWN`), when j'affiche le détail, then **toutes** s'affichent en ordre chronologique avec déposant, horodatage, type, taille lisible, commentaire et statut, chacune dotée d'une action télécharger fonctionnelle qui enregistre le binaire original (JWT porté, `Content-Disposition: attachment`).
- Given un état terminal (`RELEASED`/`REFUNDED`) ou `INITIATED`, when j'affiche le détail, then le composant de dépôt n'est pas proposé, mais la liste des preuves reste consultable et téléchargeable.
- Given le code livré, when on relit le diff, then aucun composant n'appelle `apiClient`/axios directement (flux vue→store→api respecté), aucun bouton « retirer » n'existe, aucun `Blob` n'est mis en file offline, et les textes d'UI sont en anglais.
- Given le code livré, when on exécute `cd frontend && npm run build`, then le build Vite réussit sans erreur.

## Spec Change Log

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6: (high 0, medium 1, low 5)
- defer: 0
- reject: 11: (high 0, medium 0, low 11)
- addressed_findings:
  - `[medium]` `[patch]` **Fuite d'état inter-transactions + course de réponses.** Le store étant un singleton, `loadEvidence` n'effaçait jamais `items` : en changeant de transaction (ou sur échec de rechargement), les pièces de la transaction précédente restaient affichées sous une autre — exposition de documents d'un dossier sous un autre. Corrigé : `loadEvidence` vide `items` au changement de transaction (`id !== loadedId`) et garde les réponses par un jeton de séquence (`loadSeq`) qui ignore toute réponse supplantée par un chargement plus récent (+ garde défensive `Array.isArray`).
  - `[low]` `[patch]` **Message serveur avalé au téléchargement.** Le téléchargement utilisant `responseType: 'blob'`, le corps d'erreur JSON arrivait en `Blob` et `err.response?.data?.message` était toujours `undefined` (message générique seul, contraire à l'invariant « erreur serveur affichée telle quelle »). `EvidenceList` lit désormais le `Blob` d'erreur et en extrait le `message`.
  - `[low]` `[patch]` **Double GID après dépôt.** Le store rechargeait la liste en interne *et* la vue rechargeait via `@uploaded` → deux `GET .../evidence` par dépôt. Rechargement interne du store retiré : la vue (orchestratrice unique des chargements) est le seul déclencheur.
  - `[low]` `[patch]` **Collision de `:key`.** L'aperçu des fichiers sélectionnés se clé sur `name+size` : deux fichiers de mêmes nom+taille produisaient une clé dupliquée. Clé enrichie de l'index.
  - `[low]` `[patch]` **Métadonnée d'audit trompeuse.** Le dépôt envoyait `clientCapturedAt = heure d'upload`, alors que le champ dénote l'heure de *capture* (usage réel = dépôt différé, Epic 4). Champ retiré du dépôt en ligne : le serveur horodate à la réception.
  - `[low]` `[patch]` **Couplage mort.** `uploaderLabel(item, transaction, currentUserId)` n'utilisait jamais `transaction` ; `EvidenceList` importait le store escrow et calculait `transaction` uniquement pour le passer. Paramètre et couplage supprimés.

<!-- Rejets (11, tous low — non atteignables ou par-design) :
     (a) Intégration file d'attente offline du binaire → hors périmètre explicite (Never : Epic 4, FR-15/16, IndexedDB).
     (b) Validation multi-fichiers ne montrant que la 1re erreur → confort d'UX, bloque bien la soumission ; le serveur reste l'autorité.
     (c) Absence d'indicateur de chargement dans la liste → cosmétique (fetch bref).
     (d) Formulaire de dépôt démonté si l'état sort de la fenêtre pendant la saisie → rare, et le dépôt n'est alors plus autorisé.
     (e) Fuite d'ObjectURL si `a.click()` lève → `a.click()` ne lève pas en pratique.
     (f) « 201 signalé comme échec si le rechargement échoue » → faux : `loadEvidence` avale ses propres erreurs (jamais rejeté) ; de plus le rechargement interne a été retiré.
     (g) Spinner/erreur de téléchargement partagés entre lignes sur clics concurrents → UX mineure.
     (h) `formatBytes(négatif)` → `sizeBytes` serveur toujours > 0 (V2/V3 CHECK).
     (i) `uploaderLabel` `===` id string vs number → les deux sont des nombres (Long → JSON number).
     (j) `formatTimestamp` « Invalid Date » → `createdAt` serveur toujours ISO valide.
     (k) Corps 200 non-array → l'endpoint liste renvoie toujours un tableau (garde `Array.isArray` ajoutée par acquit de conscience). -->

## Design Notes

**Gotcha multipart / axios.** L'instance `apiClient` impose `Content-Type: application/json` par défaut. Pour l'upload, passer explicitement `{ headers: { 'Content-Type': 'multipart/form-data' } }` : axios 1.x reconnaît le `FormData` et calcule la *boundary* lui-même. Ne pas sérialiser le `FormData` à la main. Construction :

```js
// stores/evidence.js — uploadEvidence
const form = new FormData()
for (const f of files) form.append('files', f)     // clé répétée exacte 'files'
if (comment) form.append('comment', comment)
if (clientCapturedAt) form.append('clientCapturedAt', clientCapturedAt)
const created = await uploadEvidence(id, form)       // POST 201 → EvidenceDto[]
await this.loadEvidence(id)                            // rafraîchit la liste
```

**Téléchargement authentifié (le `<a href>` ne porte pas le JWT).**

```js
// stores/evidence.js — downloadFile
const blob = await downloadEvidence(id, item.id)      // responseType: 'blob'
const url = URL.createObjectURL(blob)
const a = document.createElement('a')
a.href = url; a.download = item.originalFilename
document.body.appendChild(a); a.click(); a.remove()
URL.revokeObjectURL(url)                               // libère l'ObjectURL
```

**Validation client (miroir, jamais autorité).** `validateFile(file)` dans `utils/evidence.js` retourne un message si `file.size === 0`, `file.size > MAX_EVIDENCE_SIZE`, ou `file.type` ∉ `ALLOWED_MIME_TYPES` (avec repli extension) — sinon `null`. Le serveur re-valide par content-sniffing ; un fichier renommé qui passe le client sera refusé `400` par le serveur, message affiché.

**Déposant lisible.** `uploaderLabel(item, transaction, currentUserId)` : si `item.uploadedByUserId === currentUserId` → « You » ; sinon mappe `item.uploaderType` (`BUYER`→"Buyer", `SELLER`→"Seller", `ADMIN`→"Admin", `CARRIER_PARTNER`→"Carrier partner"). Ne pas déduire l'identité de l'email seul.

**Enveloppe d'erreur serveur.** Toute erreur API a la forme `{ timestamp, status, error, message }`. Afficher `err.response?.data?.message` (repli sur un texte générique). Convention d'affichage existante : `<p class="text-sm text-red-600">`.

**Statut visuel.** Badge `ACTIVE` (vert discret) / `WITHDRAWN` (gris) en Tailwind inline, cohérent avec le style des badges existants ; réutiliser la palette, pas de nouveau composant si un simple `<span>` suffit.

## Verification

**Commands:**
- `cd frontend && npm run build` -- expected : build Vite **réussi**, aucune erreur de compilation/import.

**Manual checks:**
- Détail d'une transaction `FUNDS_LOCKED` : le composant de dépôt s'affiche ; sélectionner un `.txt` → message client, envoi bloqué ; sélectionner un PDF ≤ 10 Mo → 201, la liste montre la pièce (déposant, horodatage, type, taille, commentaire, badge ACTIVE).
- Cliquer « Download » sur une pièce → le fichier original s'enregistre sous son `originalFilename` (JWT porté, réponse `attachment`).
- Détail d'une transaction `RELEASED` : pas de composant de dépôt, liste toujours consultable et téléchargeable ; une pièce `WITHDRAWN` reste visible avec son badge.
- Relire le diff : aucun appel axios direct depuis un composant, aucun bouton retirer, aucune mise en file offline du binaire, textes en anglais.

## Auto Run Result

Status: done
Blocking condition: aucune

### Changement implémenté

L'interface PWA du **dépôt et de la consultation contradictoire des preuves** est câblée sur l'écran de détail transaction (`/escrow/:id`). Deux composants Vue 3 `<script setup>` sont montés dans une nouvelle carte « Evidence » : `EvidenceDeposit` (visible uniquement si `transaction.state ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}`) permet de sélectionner un ou plusieurs fichiers JPG/PNG/PDF, en affiche nom/type/taille, valide côté client (type + taille ≤ 10 485 760 octets, confort d'UX — le serveur reste l'autorité), accepte un commentaire optionnel et envoie en `multipart/form-data` avec les parts figées (`files` répété, `comment`) ; `EvidenceList` affiche **toutes** les pièces (y compris `WITHDRAWN`) en ordre chronologique avec déposant (« You » si l'utilisateur courant), horodatage, type, taille lisible, commentaire, badge de statut et un bouton **Download** par ligne. Le téléchargement passe par un `GET ... responseType: blob` (JWT porté par l'intercepteur) puis déclenche l'enregistrement navigateur via un `<a download>` synthétique. Le flux d'architecture front est respecté partout : composant → action de store (`stores/evidence.js`) → module API (`api/evidence.js`) → `apiClient`. Textes d'UI en anglais (cohérence avec l'app existante, aucun i18n).

### Fichiers

**Créés**
- `frontend/src/utils/evidence.js` — source unique des limites miroir serveur (`MAX_EVIDENCE_SIZE`, MIME/extensions autorisés) + `formatBytes`, `uploaderLabel`, `validateFile`.
- `frontend/src/api/evidence.js` — couche API mince : `listEvidence`, `uploadEvidence` (en-tête multipart), `downloadEvidence` (blob).
- `frontend/src/stores/evidence.js` — store Pinia (state + `loadEvidence`/`uploadEvidence`/`downloadFile`), avec garde de séquence anti-course et effacement inter-transactions.
- `frontend/src/components/EvidenceDeposit.vue` — formulaire de dépôt avec validation client, émet `uploaded`.
- `frontend/src/components/EvidenceList.vue` — liste chronologique contradictoire + téléchargement authentifié par ligne.

**Modifiés**
- `frontend/src/views/TransactionDetailView.vue` — carte « Evidence » (dépôt conditionné à l'état + liste), chargement des preuves en `onMounted` et sur `escrow:sync`, nettoyage en `onBeforeUnmount`.

### Revue

2 revues adversariales parallèles (Blind Hunter + Edge Case Hunter, même capacité de modèle). Le contrat backend a d'abord été vérifié : tous les noms de champs/enums/parts multipart concordent (aucun mismatch). Aucun défaut d'autorisation exploitable (validation UX-only correctement subordonnée au serveur, aucun bouton retirer, aucun accès direct axios depuis un composant). **0 intent_gap, 0 bad_spec** → zéro boucle de reprise.

- **Patchés (6 : 1 medium, 5 low)** : (medium) fuite d'état inter-transactions + course de réponses dans le store → effacement au changement de transaction + jeton de séquence ; (low) message serveur avalé au téléchargement (corps `Blob`) → extraction du `message` ; double `GET` après dépôt → rechargement unique par la vue ; collision de `:key` d'aperçu → index ajouté ; `clientCapturedAt` = heure d'upload trompeuse → champ retiré du dépôt en ligne ; couplage mort `transaction` dans `uploaderLabel`/`EvidenceList` → supprimé.
- **Rejetés (11, tous low)** : offline (hors périmètre Epic 4), et 10 findings non atteignables (garanties de contrat serveur : size>0, id numérique, ISO valide, tableau) ou cosmétiques/par-design — détail en commentaire du *Review Triage Log*.

### Vérification

- `cd frontend && npm run build` → **build Vite réussi** (102 modules, PWA précache régénéré, 0 erreur), avant et après application des correctifs.
- Grep : aucun composant n'importe `axios`/`apiClient` ; aucun bouton retirer ; part multipart exactement `files` (pas `files[]`) ; `EvidenceDownload`/blob + `responseType` corrects ; garde de séquence et effacement présents dans le store.

### Risques résiduels

- **Pas de test front automatisé** : le projet n'a aucun harnais de test front (ni vitest ni @vue/test-utils) ; en introduire un était hors périmètre de cette story. Vérification par build propre + contrôles manuels. Un harnais de test front reste à décider comme travail d'infrastructure séparé.
- **Dépôt en ligne uniquement** : la mise en file hors-ligne du binaire (IndexedDB) est l'Epic 4 ; hors réseau, le dépôt échoue avec un message générique (par-design).
- **Retrait (withdraw) absent** : par-design — l'endpoint de retrait est l'Epic 2 (Story 2.3), non encore implémenté.
