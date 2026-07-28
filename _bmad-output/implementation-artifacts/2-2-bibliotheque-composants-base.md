# Story 2.2: Bibliothèque de composants de base réutilisables

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a développeur frontend de la plateforme,
I want des composants Vue réutilisables (bouton, carte standard, carte wallet, badge d'état, skeletons) construits exclusivement sur les tokens de la Story 2.1,
so that chaque story d'écran à venir assemble des composants éprouvés au lieu de réinventer le style.

**Portée réelle :** ces composants seront montés par les dix epics suivants. Un défaut posé ici se paie partout — la Story 2.1 en a fait la démonstration coûteuse (3 passes de revue).

## Acceptance Criteria

**AC1 — Bouton**
**Given** les tokens livrés en 2.1
**When** le composant Bouton est utilisé
**Then** il offre les variantes primary/danger (tokenisées, hauteur minimale **44 px**) plus secondaire et ghost, avec libellés passés par clés i18n
**And** il supporte un libellé porteur de montant pour les confirmations financières, et la règle « un seul primaire par surface » est documentée dans la démo.

**AC2 — Cartes**
**Given** les composants Carte
**When** la carte standard et la carte wallet sont rendues
**Then** la carte standard respecte : `surface-card`, bordure, rayon `lg`, padding 16
**And** la carte wallet — **composant de présentation piloté par props, sans aucune dépendance au backend wallet** — respecte : fond navy inverse, solde en `amount-hero` avec chiffres tabulaires, mention « adossé au compte cantonné », actions Déposer/Retirer désactivables, et variante hors-ligne affichant la dernière valeur horodatée, actions désactivées.

**AC3 — Badge d'état**
**Given** le composant Badge d'état
**When** il reçoit un état du cycle de vie escrow
**Then** il rend une pilule utilisant **exclusivement** le mapping sémantique central de 2.1, avec libellé i18n, jamais une icône seule
**And** des tests Vitest couvrent chaque composant (états, variantes, désactivation) **dans les deux langues**.

**AC4 — Skeletons**
**Given** les états de chargement des composants livrés
**When** une surface attend ses données
**Then** des composants skeleton calqués sur la mise en page cible sont fournis
**And** la règle « jamais de spinner plein écran après la première peinture, toute attente a couleur + libellé » est documentée et appliquée dans la démo.

**AC5 — Page de démonstration**
**Given** une page de démonstration interne (route de développement, **exclue du build de production**)
**When** un relecteur l'ouvre
**Then** tous les composants y sont présentés dans leurs variantes et les deux langues
**And** la revue visuelle est possible sans dépendre d'aucun écran métier futur.

## Tasks / Subtasks

- [ ] **T1 — Lire avant d'écrire** (préalable, AC: 1,2,3)
  - [ ] `frontend/src/style.css` : inventaire des tokens réellement disponibles (couleurs, `--text-*`, `--radius*`, `--shadow-floating`, `--space-*`, utilitaire `.tabular-amount`).
  - [ ] `frontend/src/components/StateBadge.vue` **et son test** : le badge de l'AC3 EXISTE DÉJÀ et est couvert. Ne pas le recréer.
  - [ ] `frontend/src/utils/stateMachine.js` : `STATE_TOKENS`, `STATE_COLORS`, `OFFLINE_CLASSES`, `SYNCING_CLASSES`.
  - [ ] `frontend/src/i18n/labels.js` : la règle de dégradation (clé connue → traduction, sinon forme lisible, jamais de clé brute, jamais de levée).
  - [ ] `frontend/src/i18n/__tests__/noHardcodedStrings.spec.js` : ce que les gardes interdisent, pour ne pas écrire du code qui rougira.

- [ ] **T2 — Bouton** (AC: 1)
  - [ ] Quatre variantes : primary, danger, secondary, ghost. Hauteur minimale 44 px sur toutes.
  - [ ] Libellé par clé i18n ; prop de libellé porteur de montant (ex. « Financer — 12 500,00 USD »), montant formaté selon la langue ACTIVE et en chiffres tabulaires.
  - [ ] État désactivé et état « en cours » distincts, avec libellé.

- [ ] **T3 — Cartes** (AC: 2)
  - [ ] Carte standard : `surface-card`, bordure, rayon `lg`, padding 16, **aucune ombre au repos**.
  - [ ] Carte wallet : props uniquement (`balance`, `currency`, `updatedAt`, `offline`, `canDeposit`, `canWithdraw`) — aucun store, aucun appel réseau.
  - [ ] Variante hors-ligne : dernière valeur + horodatage « données au… », actions désactivées, famille `offline`.

- [ ] **T4 — Badge d'état** (AC: 3)
  - [ ] **Vérifier et compléter `StateBadge` existant**, ne pas en créer un second. Compléter son test si des variantes manquent.

- [ ] **T5 — Skeletons** (AC: 4)
  - [ ] Skeletons calqués sur la carte standard, la carte wallet et la ligne de liste.
  - [ ] Documenter et appliquer la règle « pas de spinner plein écran après la première peinture ».

- [ ] **T6 — Page de démonstration** (AC: 5)
  - [ ] Route de développement présentant chaque composant dans toutes ses variantes et les deux langues.
  - [ ] **Exclusion du build de production PROUVÉE par test**, pas seulement configurée — voir « Le piège de l'AC5 » ci-dessous.

- [ ] **T7 — Dette d'élévation, reportée de la Story 2.1** (AC: 2)
  - [ ] Retirer `shadow-sm`/`shadow-lg`/`shadow-md`/`shadow-xl` des surfaces qui ne sont ni menu, ni toast, ni modale : `TransactionCard.vue`, `TransactionDetailView.vue` (×3), `RecoveryView.vue` (×4), `AuthView.vue`, `StepperEscrow.vue`. `NewTransactionModal.vue` est une **modale** : elle a droit à l'ombre, mais via `--shadow-floating`.
  - [ ] Garde de non-régression : un test interdit toute ombre hors `shadow-floating` sur les composants de la bibliothèque.

- [ ] **T8 — Tests** (AC: 1,2,3,4)
  - [ ] Un test de composant par composant livré, **dans les deux langues** (l'AC l'exige explicitement).
  - [ ] Suite complète verte : **305 tests au départ**, aucun ne doit rougir. `npm run lint` propre.

## Dev Notes

### ⚠️ Anti-réinvention : le badge d'état existe déjà

L'AC3 décrit le « composant Badge d'état » comme s'il fallait le créer. **`frontend/src/components/StateBadge.vue` existe, est câblé sur le mapping central, et possède `StateBadge.spec.js` (5 tests).** Créer un second composant de badge serait l'erreur la plus probable de cette story.

Neuf composants existent aujourd'hui, dont **quatre seulement sont testés** (`StateBadge`, `OnlineBanner`, `SyncFailureNotice`, `LanguageSwitcher`). Les cinq autres — `AuditTimeline`, `EvidenceDeposit`, `EvidenceList`, `NewTransactionModal`, `OpenDisputeForm`, `StepperEscrow`, `TransactionCard` — n'ont aucun test. La Story 2.1 a montré ce que cela coûte : un correctif de `StateBadge` a été effacé sans que rien ne l'annonce, précisément parce qu'aucun test ne le tenait.

### ⚠️ Le piège de l'AC5 : « exclue du build de production »

Une route de démonstration qui part en production est une surface offerte. La configurer ne suffit pas — **il faut le prouver**. Une garde possible : un test qui construit (`npm run build`) et vérifie qu'aucun artefact de `dist/` ne contient le composant de démonstration ni sa route. Un simple `if (import.meta.env.DEV)` autour de la route est facile à écrire ET facile à contourner par un import statique qui embarque quand même le code dans le bundle.

C'est exactement le motif que la Story 2.1 a rencontré trois fois : **une garde qu'aucune assertion ne relit n'est pas une garde**.

### Tokens disponibles (livrés par 2.1, à consommer tels quels)

| Besoin | Token |
|---|---|
| Fond de carte / page / inverse | `surface-card`, `surface-page`, `surface-inverse` |
| Action primaire + survol | `primary`, `primary-hover`, `primary-foreground` |
| Familles d'état | `success`, `warning`, `danger`, `info`, `neutral-state`, `offline` (+ `-surface`) |
| Montants signés | `amount-credit`, `amount-debit` |
| Typographie | `--text-display|heading|body|label|caption|amount|amount-hero` |
| Rayons | `--radius` (8 px, utilitaire nu `rounded`), `-sm`, `-md`, `-lg`, `-full` |
| Ombre | `--shadow-floating` — **la seule qui existe**, réservée aux surfaces flottantes |
| Chiffres tabulaires | classe `.tabular-amount` |

**Échelle d'espacement — ambiguïté connue, tranchée en 2.1 et à confirmer ici.** La rampe de `DESIGN.md` (5=24 px, 6=32, 7=48) diverge de Tailwind (20/24/28) à partir de 5. Les tokens sont exposés sous des noms propres (`--space-*`) et l'échelle Tailwind est restée intacte, pour ne pas décaler en silence les écrans déjà livrés. **Correspondance pour du code neuf : design 5 = `6` Tailwind, design 6 = `8`, design 7 = `12`.** Si cette story juge le compromis intenable pour une bibliothèque, c'est le bon moment pour le dire — pas dans six epics.

### Règles héritées de 2.1, non négociables

- **Aucune chaîne littérale.** Les gardes de `noHardcodedStrings.spec.js` scannent `components/` et `views/`, gabarits ET blocs script, littéraux simples, doubles et backticks compris. Un composant neuf avec du texte en dur rougit immédiatement.
- **Aucune clé brute rendue.** Passer par `stateLabel`/`roleLabel`/`eventLabel` de `@/i18n/labels`, jamais par `$t(\`prefix.${valeur}\`)` en direct : une valeur hors catalogue afficherait `state.EXPIRED` à l'utilisateur, et `$t(null)` LÈVE.
- **Aucune couleur d'état redéfinie par un écran.** Lire `STATE_COLORS` / `OFFLINE_CLASSES` / `SYNCING_CLASSES`. La Story 2.1 a livré `OFFLINE_CLASSES` sans consommateur, puis codé la couleur en dur dans la bannière : les deux défauts ont été trouvés en revue.
- **Classes Tailwind écrites en toutes lettres.** Jamais composées (`bg-${token}-surface`) : l'extraction est statique, une classe construite à l'exécution n'existe pas dans la feuille de style et le rendu est muet, sans erreur.
- **Français avec accents.** La garde d'encodage prouve la validité UTF-8 à chaque push.
- **Vérification par mutation** sur toute garde livrée : retirer la garde, constater le rouge, restaurer, consigner.

### Dette à ne pas aggraver

`PENDING_MIGRATION` (dans `noHardcodedStrings.spec.js`) énumère six modules purs portant encore des libellés anglais : `utils/evidence.js`, `utils/frozenEntry.js`, `utils/replayFailure.js`, `stores/auth.js`, `stores/escrow.js`, `stores/evidence.js`. **Ne rien y ajouter.** Le correctif type est connu : le module expose une CLÉ, la vue traduit — comme `EVENT_LABEL_KEYS`.

### Previous story intelligence — ce que 2.1 a coûté

6 commits, **3 passes de revue adversariale**, 305 tests contre 241 au départ. Enseignements directement applicables :

- **La revue a trouvé plus de défauts que l'implémentation n'en a évité.** Prévoir la revue comme la moitié du travail, pas comme une formalité.
- **Une case cochée n'est pas une preuve.** Un item est resté coché plusieurs commits alors que le code ne correspondait pas.
- **Ne jamais restaurer par `git checkout --`** un fichier porteur de travail non commité : c'est ainsi qu'un correctif a été effacé en silence pendant un test de mutation. Sauvegarder le contenu et le réécrire.
- **Un composant sans test peut perdre son correctif sans que rien ne l'annonce.**
- **Attention à l'insertion automatique de point-virgule** : un commentaire glissé entre `return` et son expression a transformé un calcul en code mort, build et suite verts.
- Les gardes elles-mêmes se trompent : borner un gabarit, reconnaître les backticks, ignorer les commentaires, accepter les échappements — chacun de ces points a fait l'objet d'un correctif.

### Git intelligence

Les 6 derniers commits sont ceux de la Story 2.1 : tokens, i18n, trois passes de correctifs. Aucun autre travail frontend n'a eu lieu. L'état du dépôt est exactement celui que 2.1 a laissé.

Barrières actives et **bloquantes** sur `main` et `develop` : `npm run lint --max-warnings 0`, la suite Vitest, et le job `Hygiène (encodage / octets NUL)` — les trois sont des required checks vérifiés au niveau de la protection de branche.

### Library & framework requirements

Aucune dépendance nouvelle n'est nécessaire. Tout est installé : `vue` 3.5.13, `tailwindcss` 4, `vue-i18n` 11.4.8, `@vue/test-utils` 2.4.11, `@fontsource-variable/inter` 5.3.0. **Toute dépendance supplémentaire doit être validée avant installation** (règle du workflow, appliquée en 2.1 pour la police).

Pas de bibliothèque UI (PrimeVue, Naive…) : `DESIGN.md` acte des composants maison sur tokens CSS.

### File structure

- `frontend/src/components/` — composants livrés (NOUVEAUX), tests colocalisés en `__tests__/`
- `frontend/src/components/StateBadge.vue` — EXISTANT, à compléter et non à recréer
- Route de démonstration : à placer de façon à ne PAS entrer dans le bundle de production (voir le piège de l'AC5)

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 2.2`] — AC d'origine
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md`] — **contrat des tokens** (frontmatter) et règles de composants (un seul primaire par surface, jamais d'icône seule, élévation)
- [Source: `_bmad-output/implementation-artifacts/2-1-fondation-ui-tokens-design-i18n.md`] — story précédente, ses trois passes de revue et sa clôture
- [Source: `_bmad-output/implementation-artifacts/deferred-work.md`] — dette d'élévation reportée ici, et `PENDING_MIGRATION`
- [Source: `_bmad-output/project-context.md`] — règle de mutation, conventions d'encodage, de lint et de test

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
