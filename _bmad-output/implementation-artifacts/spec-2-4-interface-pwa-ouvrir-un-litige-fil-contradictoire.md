---
title: 'Interface PWA — ouvrir un litige & fil contradictoire'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: '0bfdfc7a98392999e97cc41906f68c9a5085ffb6'
final_revision: '172e1f55975009f518f17484b4e8cad513c68e88'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Le backend d'Epic 2 est complet (ouverture composite `POST /{id}/dispute` en 2.1, verrou terminal en 2.2, retrait logique `POST .../evidence/{eid}/withdraw` en 2.3), mais la PWA ne l'expose pas. Pire, le bouton « Open dispute » actuel appelle le chemin générique `POST /{id}/event` avec `OPEN_DISPUTE` — que le serveur **rejette désormais en `400`** (« Opening a dispute requires evidence; use POST …/dispute ») : ouvrir un litige depuis l'app est donc **cassé**. Le retrait n'a aucune UI.

**Approach:** Sur `TransactionDetailView.vue`, (1) retirer `OPEN_DISPUTE` de la liste des événements simples et le remplacer par un bouton dédié qui déploie un formulaire d'ouverture de litige (`OpenDisputeForm.vue`) exigeant ≥ 1 fichier **ET** un commentaire ≥ 10 caractères avant soumission, envoyé en multipart à l'endpoint composite ; à la réussite, l'état affiché passe à `DISPUTED` et le fil se recharge. (2) Ajouter une action « Withdraw » par ligne dans `EvidenceList.vue`, visible uniquement sur **ses propres** pièces `ACTIVE`, câblée à l'endpoint de retrait 2.3. Le fil contradictoire chronologique (pièces de toutes les parties, y compris `WITHDRAWN`) est déjà rendu par `EvidenceList` (Story 1.5) et reste inchangé dans sa structure.

## Boundaries & Constraints

**Always:**
- **Le serveur est la seule autorité.** La validation client (≥ 1 fichier, commentaire ≥ 10 caractères après `trim`, type/taille) est un confort d'UX pré-envoi ; toute erreur `400/403/404/409` du serveur est affichée telle quelle via `err.response?.data?.message`. Le client ne rejoue jamais une décision serveur ni ne masque un refus (ex. le plancher FR-6 en `DISPUTED` est un `409` serveur affiché, jamais pré-calculé côté client).
- **Contrat multipart figé (AD-13).** L'ouverture POST utilise `FormData` avec les parts exactes : chaque fichier sous la clé répétée `files` (jamais `files[]`), `comment` (requis ici), `clientCapturedAt` **omis** (dépôt en ligne ; le serveur horodate — cohérent avec `EvidenceDeposit`). Mêmes noms que le dépôt simple.
- **Flux d'architecture front respecté.** Aucun composant n'appelle `axios`/`apiClient` directement : `vue → action de store → module API → apiClient`. Ouverture via `escrowStore.openDispute` ; retrait via `evidenceStore.withdrawEvidence`. Le JWT est attaché par l'intercepteur ; ne jamais poser `Authorization` à la main.
- **Source de vérité état/rôle unique.** L'éligibilité à l'ouverture (`FUNDS_LOCKED` → acheteur ou vendeur ; `SHIPPED` → acheteur seul) et au retrait est dérivée de `utils/stateMachine.js` (mêmes rôles que les `TRANSITIONS` existantes) — pas de règle dupliquée en dur dans un composant.
- **Retrait strictement sur sa propre pièce active.** Le bouton « Withdraw » n'apparaît que si `item.status === 'ACTIVE'` **ET** `item.uploadedByUserId === auth.user?.id`. À la réussite (`200`, `EvidenceDto` mis à jour `status=WITHDRAWN`), la ligne est remplacée en place (jamais retirée de la liste ni masquée).
- **Visibilité contradictoire (FR-9/AD-4).** Le fil affiche **toutes** les pièces, y compris `WITHDRAWN` (badge « Withdrawn », jamais masquées). Après ouverture, `currentDetail.transaction` reflète `DISPUTED` et la liste inclut la ou les pièces jointes à l'ouverture.
- **Conventions UI ratifiées.** `<script setup>` + Composition API JS pur ; Tailwind v4 utilitaire inline (aucun `<style>`) ; palette `brand-*`, rouge pour litige ; cartes/bouton aux tokens existants ; réutiliser `validateFile`/`formatBytes`/`uploaderLabel` de `utils/evidence.js` et `StateBadge` ; **textes d'UI en anglais** (pas d'i18n).

**Block If:**
- Aucune décision humaine attendue. Le point litigieux connu (exposer `withdrawn_at`/`withdrawn_by_user_id` dans `EvidenceDto`) est **hors périmètre** : les AC n'exigent que la visibilité de la pièce retirée (badge), pas l'attribution du retrait — ne pas modifier le backend. (Voir Design Notes.)

**Never:**
- **Aucune modification backend** (endpoints, DTO, service, migration). Story frontend pure. En particulier ne pas étendre `EvidenceDto` : la story reste UI-only.
- **Aucun litige « à vide ».** Ne pas ré-autoriser `OPEN_DISPUTE` sur le chemin `/event` ; ne pas permettre la soumission sans fichier ou commentaire ≥ 10 (le serveur reste l'autorité, mais le bouton reste désactivé).
- **Aucun rejeu offline du binaire** (ouverture/dépôt/retrait sont en ligne uniquement — le binaire hors-ligne est l'Epic 4). Le retrait n'est pas mis en file offline.
- **Aucun nouveau harnais de test front** (aucun n'existe ; le décider est un travail d'infra séparé — précédent Story 1.5). Vérification = `npm run build` + contrôles manuels + relecture du diff.
- Aucun pré-calcul client du plancher de retrait, aucune pagination/tri « intelligent » (la liste arrive déjà chronologique), aucun gold-plating (pas d'aperçu image, drag-and-drop, barre de progression).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Bouton ouverture visible | tx `FUNDS_LOCKED` (acheteur/vendeur) ou `SHIPPED` (acheteur) | Bouton « Open dispute » dédié affiché ; `OPEN_DISPUTE` **absent** des boutons d'événement simple | — |
| Ouverture nominale | ≥ 1 fichier JPG/PNG/PDF ≤ 10 Mo + commentaire ≥ 10 car. | `POST /{id}/dispute` multipart → `200 DisputeOpenedDto` ; `currentDetail.transaction` passe `DISPUTED`, la liste se recharge avec la/les pièces | — |
| Ouverture incomplète (client) | 0 fichier, OU commentaire < 10 car. (après trim) | Soumission **bloquée** avant envoi, bouton désactivé, message client | Pas d'appel serveur |
| Refus serveur ouverture | Serveur renvoie `400/403/409` (transition illégale, non-partie, fichier invalide) | `message` de l'enveloppe affiché ; formulaire non réinitialisé, état inchangé | Message serveur tel quel |
| Bouton retrait visible | Pièce `ACTIVE` dont `uploadedByUserId === auth.user.id` | Bouton « Withdraw » affiché sur cette ligne | — |
| Retrait interdit (UI) | Pièce d'un tiers, ou pièce `WITHDRAWN` | Aucun bouton « Withdraw » | — |
| Retrait nominal | Clic « Withdraw » sur sa pièce `ACTIVE`, fenêtre ouverte, plancher tenu | `POST .../withdraw` → `200`, la ligne passe `WITHDRAWN` en place (reste visible) | — |
| Retrait sous plancher | tx `DISPUTED`, dernière pièce `ACTIVE` | Serveur `409` ; ligne inchangée | Message serveur (« … without evidence ») affiché |
| Retrait hors fenêtre / déjà retiré | tx terminale, ou pièce déjà `WITHDRAWN` (course) | Serveur `409` ; ligne inchangée | Message serveur affiché |
| Litige déjà ouvert | tx `DISPUTED`/`RELEASED`/`REFUNDED` | Pas de bouton « Open dispute » ; dépôt de contre-preuve possible si `DISPUTED` (déjà géré par 1.5) | — |

</intent-contract>

## Code Map

- `frontend/src/utils/stateMachine.js` -- MODIFIER : exclure `OPEN_DISPUTE` de `getAllowedEvents` (il n'est plus déclenchable via `/event`) — garder l'entrée dans `TRANSITIONS` comme source des rôles ; ajouter `canOpenDispute(transaction, user)` (lit `TRANSITIONS[state]?.OPEN_DISPUTE?.roles` + contrôle partie : `BUYER`↔`buyerEmail`, `SELLER`↔`sellerEmail`, jamais `ADMIN`).
- `frontend/src/api/escrow.js` -- MODIFIER : ajouter `openDispute(id, formData)` → `POST /api/v1/escrow/{id}/dispute`, en-tête `Content-Type: multipart/form-data`, retourne `res.data` (`DisputeOpenedDto { transaction, evidence }`).
- `frontend/src/api/evidence.js` -- MODIFIER : ajouter `withdrawEvidence(id, evidenceId)` → `POST /api/v1/escrow/{id}/evidence/{evidenceId}/withdraw` (pas de corps), retourne `res.data` (`EvidenceDto` mise à jour).
- `frontend/src/stores/escrow.js` -- MODIFIER : action `openDispute(id, { files, comment })` — construit le `FormData` (`files` répété, `comment`), appelle l'API ; à la réussite met à jour `currentDetail.transaction` avec `dto.transaction` et la liste `transactions` en place (miroir de `sendTransactionEvent`). En ligne uniquement (pas de file offline). Retourne `dto`.
- `frontend/src/stores/evidence.js` -- MODIFIER : action `withdrawEvidence(id, evidenceId)` — appelle l'API, remplace en place l'item correspondant dans `items` par l'`EvidenceDto` retournée (garde de séquence non requise : mutation ponctuelle). Erreurs propagées au composant.
- `frontend/src/components/OpenDisputeForm.vue` -- CRÉER : formulaire d'ouverture (mêmes patrons que `EvidenceDeposit.vue`) — `<input type="file" accept=".jpg,.jpeg,.png,.pdf" multiple>`, validation client par `validateFile`, textarea commentaire **requis**, `canSubmit = files.length > 0 && comment.trim().length >= 10 && !validationError && !submitting`. `defineProps({ transactionId })`, `defineEmits(['opened','cancel'])`. Appelle `escrowStore.openDispute`, émet `opened` au succès, affiche le message serveur à l'échec.
- `frontend/src/components/EvidenceList.vue` -- MODIFIER : ajouter, à côté de « Download », un bouton « Withdraw » conditionné à `canWithdraw(item)` (`item.status === 'ACTIVE' && item.uploadedByUserId === auth.user?.id`) ; appelle `evidenceStore.withdrawEvidence(transactionId, item.id)`, gère `withdrawingId` + `withdrawError` (message serveur). Réutilise le helper `errorMessage` existant.
- `frontend/src/views/TransactionDetailView.vue` -- MODIFIER : retirer le style/handler `OPEN_DISPUTE` de la rangée d'événements ; ajouter un bouton « Open dispute » (rouge) gaté par `canOpenDispute(transaction, auth.user)` qui bascule un ref `showDisputeForm` montant `<OpenDisputeForm>` (inline) ; sur `@opened`, fermer le formulaire et recharger le fil (`loadEvidence`). Ajuster le fallback « No actions available » pour tenir compte du bouton litige.
- `frontend/src/components/EvidenceDeposit.vue` -- LIRE : patron de formulaire multipart à mirrorer (ne pas modifier : son commentaire reste optionnel, contrat figé par 1.5).
- `frontend/src/api/client.js` -- LIRE : instance axios partagée (intercepteur JWT/401). Ne pas modifier.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/utils/stateMachine.js` -- exclure `OPEN_DISPUTE` de `getAllowedEvents` + ajouter `canOpenDispute(transaction, user)` (rôles depuis `TRANSITIONS`, contrôle partie).
- [x] `frontend/src/api/escrow.js` -- ajouter `openDispute(id, formData)` (multipart, retourne `DisputeOpenedDto`).
- [x] `frontend/src/api/evidence.js` -- ajouter `withdrawEvidence(id, evidenceId)` (POST sans corps, retourne `EvidenceDto`).
- [x] `frontend/src/stores/escrow.js` -- ajouter l'action `openDispute` (construit `FormData`, met à jour `currentDetail.transaction` + `transactions`).
- [x] `frontend/src/stores/evidence.js` -- ajouter l'action `withdrawEvidence` (remplace l'item en place par le DTO retourné).
- [x] `frontend/src/components/OpenDisputeForm.vue` -- créer le formulaire d'ouverture (fichier(s) + commentaire ≥ 10, validation client, émet `opened`/`cancel`, message serveur).
- [x] `frontend/src/components/EvidenceList.vue` -- ajouter le bouton « Withdraw » (propres pièces `ACTIVE`), câblé au store, message serveur (plancher/fenêtre `409`).
- [x] `frontend/src/views/TransactionDetailView.vue` -- remplacer le bouton `OPEN_DISPUTE` cassé par le bouton dédié + `<OpenDisputeForm>` gaté par `canOpenDispute` ; recharger le fil au succès.

**Acceptance Criteria:**
- Given une transaction `FUNDS_LOCKED`/`SHIPPED` dont je suis partie et habilité, when j'ouvre le détail, then aucun bouton d'événement `OPEN_DISPUTE` générique n'est présent, mais un bouton dédié « Open dispute » l'est ; le déployer montre un formulaire dont la soumission reste **désactivée** tant que je n'ai pas ≥ 1 fichier valide ET un commentaire ≥ 10 caractères.
- Given ce formulaire complété, when je soumets, then un `POST /{id}/dispute` multipart part (`files` répété, `comment`), le serveur répond `200 DisputeOpenedDto`, l'état affiché passe à `DISPUTED` sans rechargement de page, et la/les pièces jointes apparaissent dans le fil.
- Given une transaction `DISPUTED` avec des pièces de plusieurs parties (dont des `WITHDRAWN`), when je consulte le détail, then le fil chronologique contradictoire les affiche toutes (jamais masquées), et je peux déposer une contre-preuve (dépôt existant de 1.5, actif en `DISPUTED`).
- Given une pièce `ACTIVE` que j'ai déposée, when je la vois dans le fil, then un bouton « Withdraw » n'apparaît **que** sur mes propres pièces actives ; le clic appelle l'endpoint de retrait 2.3, et à la réussite la ligne passe « Withdrawn » en place (toujours visible).
- Given un retrait refusé par le serveur (plancher `409` en `DISPUTED`, hors fenêtre, ou déjà retiré), when j'essaie, then le `message` serveur est affiché et la ligne reste inchangée — le client ne pré-juge jamais la décision.
- Given le code livré, when on relit le diff, then aucun composant n'appelle `apiClient`/axios directement, aucun endpoint/DTO/backend n'est modifié, aucun binaire n'est mis en file offline, les textes sont en anglais.
- Given le code livré, when on exécute `cd frontend && npm run build`, then le build Vite réussit sans erreur.

## Spec Change Log

_(Aucune modification de spec : 0 bad_spec, 0 intent_gap.)_

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 2: (high 0, medium 1, low 1)
- defer: 1
- reject: 11
- addressed_findings:
  - `[medium]` `[patch]` **Fil d'audit périmé après ouverture d'un litige.** `onDisputeOpened` ne rechargeait que les preuves (`loadEvidence`) : le badge passait `DISPUTED` mais la carte « Audit history » n'affichait pas la transition `OPEN_DISPUTE` avant un rafraîchissement manuel (le chemin d'événement simple appelle `load()` complet). Corrigé : `onDisputeOpened` appelle désormais `load()` **et** `loadEvidence()`, cohérent avec le chemin `trigger`.
  - `[low]` `[patch]` **Code mort dans `OpenDisputeForm.vue`.** `const fileInput = ref(null)` + `ref="fileInput"` (copiés de `EvidenceDeposit`) n'étaient jamais lus (pas de `resetForm` : le composant est démonté au succès/annulation via `v-if`). Ref et binding retirés.
- deferred (résumé) : retrait en place hors garde `loadSeq` → réversion transitoire possible sous `loadEvidence` concurrent (voir deferred-work ; fenêtre étroite, auto-corrigée, serveur cohérent).
- rejected (résumé) : `===` id acteur (Long→JSON number, déjà tranché en 1.5) ; comparaison d'email sensible à la casse (miroir du patron `getAllowedEventsForTransaction`/`counterparty` déjà livré) ; absence de garde offline (par-design en-ligne-uniquement, précédent 1.5 — binaire offline = Epic 4) ; forme de `dto.transaction` en liste dashboard (même `TransactionDto` que `sendTransactionEvent` déjà livré) ; visibilité/reset du formulaire via `v-if` (démontage = état frais correct) ; confirmation de retrait et plafond de taille agrégée (gold-plating, hors AC ; retrait logique et visible) ; validation multi-fichiers ne montrant que la 1re erreur (tranché en 1.5, confort UX, soumission bloquée) ; règle client « ≥ 10 caractères » (miroir exact du serveur, spécifié par l'AC) ; extraction du message d'erreur divergente entre les deux flux (chacune correcte pour son content-type) ; `EVENT_LABELS.OPEN_DISPUTE`/branche `buttonClasses` résiduels (inoffensifs).

## Design Notes

**Pourquoi le bouton actuel est cassé.** `applyEvent` (backend) lève `400` sur `OPEN_DISPUTE` (« use POST …/dispute ») pour garantir l'invariant « pas de litige à vide » (Story 2.1). Le front doit donc cesser d'offrir `OPEN_DISPUTE` comme événement simple et router vers l'endpoint composite. On garde l'entrée `OPEN_DISPUTE` dans `TRANSITIONS` comme unique source des rôles autorisés (lue par `canOpenDispute`), mais on l'exclut de `getAllowedEvents` — évite un bouton mort et centralise la règle état/rôle.

**Attribution du retrait non exposée — hors périmètre assumé.** `EvidenceDto` s'arrête à `status` (pas de `withdrawn_at`/`withdrawn_by_user_id` ; report ledger deferred-work). Les AC de 2.4 exigent seulement que la pièce retirée **reste visible** (badge « Withdrawn ») — déjà satisfait par 1.5. Afficher *qui/quand* a retiré exigerait une modification backend du DTO, explicitement interdite ici (story UI-only). Le report reste ouvert au niveau contrat d'API.

**Réponses serveur consommées.**
```
POST /{id}/dispute  → 200 DisputeOpenedDto { transaction: TransactionDto, evidence: EvidenceDto[] }
POST /{id}/evidence/{eid}/withdraw → 200 EvidenceDto (status: WITHDRAWN)
```
`EvidenceDto` = `{ id, transactionId, uploadedByUserId, uploaderType, originalFilename, mimeType, sizeBytes, comment, status, createdAt }`. `openDispute` peut soit reposer sur `dto.evidence` soit rappeler `loadEvidence(id)` — préférer un `loadEvidence` unique côté vue (comme `@uploaded`) pour un seul GET et éviter la divergence d'état.

**Gotcha multipart / axios (repris de 1.5).** L'instance `apiClient` impose `application/json` ; l'ouverture doit passer `{ headers: { 'Content-Type': 'multipart/form-data' } }` pour qu'axios calcule la *boundary*. Construire le `FormData` dans le store :
```js
const form = new FormData()
for (const f of files) form.append('files', f)   // clé répétée exacte
form.append('comment', comment)                    // requis (≥10, validé client + serveur)
const dto = await openDispute(id, form)            // 200 DisputeOpenedDto
```

**Retrait — mise à jour en place.** `withdrawEvidence` remplace l'item par le DTO retourné (`items = items.map(i => i.id === dto.id ? dto : i)`) plutôt que de recharger : la pièce reste à sa place chronologique, badge basculé. Le message serveur (403/404/409) remonte au composant qui l'affiche par ligne. Pas de garde de séquence (mutation ciblée, pas un rechargement de liste).

## Verification

**Commands:**
- `cd frontend && npm run build` -- expected : build Vite réussi, 0 erreur de compilation/import.

**Manual checks:**
- Détail `FUNDS_LOCKED` (acheteur) : pas de bouton d'événement `OPEN_DISPUTE` ; bouton « Open dispute » présent → formulaire ; soumission désactivée sans fichier ou commentaire < 10 ; avec PDF + commentaire ≥ 10 → `200`, badge passe `DISPUTED`, la pièce apparaît dans le fil.
- Détail `DISPUTED` : le fil montre toutes les pièces (dont `WITHDRAWN`) ; sur ma pièce `ACTIVE` un bouton « Withdraw » apparaît, pas sur celle d'un tiers ni sur une pièce déjà retirée ; retrait de ma dernière pièce active → `409` avec message serveur, ligne inchangée.
- Relire le diff : aucun appel axios direct depuis un composant, aucun fichier backend modifié, textes en anglais, part multipart exactement `files`.

## Auto Run Result

Status: done

**Changement implémenté :** l'interface PWA d'Epic 2 est câblée sur `TransactionDetailView.vue`. (1) Le bouton « Open dispute » cassé (qui appelait `POST /{id}/event` avec `OPEN_DISPUTE`, désormais rejeté `400` côté serveur) est remplacé : `OPEN_DISPUTE` est exclu des événements simples (`getAllowedEvents`), un helper `canOpenDispute(transaction, user)` (rôles depuis `TRANSITIONS`, contrôle partie) gate un bouton dédié déployant `OpenDisputeForm.vue`, qui exige ≥ 1 fichier valide **ET** un commentaire ≥ 10 caractères avant soumission, envoie en multipart (`files` répété + `comment`) à l'endpoint composite `POST /{id}/dispute`, et au succès fait passer l'état affiché à `DISPUTED` + recharge le détail et le fil. (2) `EvidenceList.vue` gagne un bouton « Withdraw » visible uniquement sur ses **propres** pièces `ACTIVE`, câblé à `POST .../evidence/{eid}/withdraw` (2.3) ; à la réussite la ligne passe « Withdrawn » en place (toujours visible), les refus serveur (`403/404/409` — plancher/fenêtre/déjà-retiré) sont affichés tels quels. Le fil contradictoire chronologique (toutes parties, pièces `WITHDRAWN` incluses) est déjà rendu par 1.5. Flux `vue → store → api → apiClient` respecté ; aucune modification backend/DTO ; textes en anglais.

**Fichiers :**
- `frontend/src/utils/stateMachine.js` — exclut `OPEN_DISPUTE` de `getAllowedEvents` ; ajoute `canOpenDispute(transaction, user)`.
- `frontend/src/api/escrow.js` — ajoute `openDispute(id, formData)` (multipart → `DisputeOpenedDto`).
- `frontend/src/api/evidence.js` — ajoute `withdrawEvidence(id, evidenceId)` (POST sans corps → `EvidenceDto`).
- `frontend/src/stores/escrow.js` — action `openDispute` (FormData figé, met à jour `currentDetail.transaction` + liste).
- `frontend/src/stores/evidence.js` — action `withdrawEvidence` (remplace l'item en place).
- `frontend/src/components/OpenDisputeForm.vue` — **nouveau** : formulaire d'ouverture (fichier(s) + commentaire ≥ 10, validation client, `opened`/`cancel`, message serveur).
- `frontend/src/components/EvidenceList.vue` — bouton « Withdraw » (propres pièces `ACTIVE`) + gestion d'erreur serveur par ligne.
- `frontend/src/views/TransactionDetailView.vue` — bouton dédié + `<OpenDisputeForm>` gaté par `canOpenDispute` ; `onDisputeOpened` recharge détail + fil.

**Revue :** 2 revues adversariales parallèles (Blind Hunter + Edge Case Hunter, même capacité Opus 4.8). 0 intent_gap, 0 bad_spec → aucune boucle de reprise. 2 patches appliqués (1 medium : rechargement du fil d'audit après ouverture ; 1 low : code mort `fileInput` retiré), 1 report différé (retrait en place hors garde `loadSeq`), 11 findings rejetés (voir Review Triage Log — majoritairement déjà tranchés en 1.5, par-design en-ligne-uniquement, ou gold-plating hors AC).

**Vérification :** `cd frontend && npm run build` → build Vite réussi (103 modules, PWA précache régénéré, 0 erreur), avant et après application des patches. Grep : aucun composant n'importe `apiClient`/`axios` directement ; part multipart exactement `files` (jamais `files[]`) ; aucun fichier backend modifié.

**Risques résiduels :** (1) Retrait en place hors `loadSeq` — réversion visuelle transitoire possible sous `loadEvidence` concurrent (différé, fenêtre étroite, auto-corrigée, serveur cohérent). (2) Ouverture/retrait en ligne uniquement — hors réseau, échec avec message générique (par-design ; binaire offline = Epic 4). (3) Attribution du retrait (`withdrawn_at`/`withdrawn_by_user_id`) non exposée en réponse API — hors périmètre (AC n'exige que la visibilité de la pièce retirée) ; report ouvert au niveau contrat d'API. (4) Aucun test front automatisé — le projet n'a pas de harnais front (décision d'infra séparée, précédent 1.5) ; vérification par build propre.
