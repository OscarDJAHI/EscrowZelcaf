# Réconciliation UX → Architecture Spine

- **Entrée** : `_bmad-output/planning-artifacts/ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md` + `EXPERIENCE.md`
- **Spine** : `_bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md`
- **Date** : 2026-07-24
- **Méthode** : lecture intégrale des trois documents ; seules les exigences UX à implication architecturale que le spine ne couvre **ni** ne défère sont rapportées.

## Vérifié et correctement couvert (non rapporté)

| Exigence UX | Couverture spine |
|---|---|
| Contrat de tokens (DESIGN.md = contrat, pas de lib UI imposée) | Conventions frontend + Structural Seed `frontend/src/design/tokens.css (DESIGN.md)` ; lib UI éventuelle explicitement déférée |
| Mapping état→couleur unique app + back-office | Conventions frontend (UX-DR2) + `stateColors.js` dans le seed |
| File d'attente offline (preuves, litige atomique), rejeu idempotent, écran offline-reject | AD-9, AD-10 (hérités), AD-27 (whitelist non financière, zéro optimisme financier) |
| Un seul composant « file opérateur » | Conventions frontend : composant unique né en Story 3.3, implémentations parallèles interdites |
| i18n par clés + garde CI clé manquante ; backend sans texte utilisateur | AD-23 + Structural Seed `i18n/` |
| Brouillons de messages hors-ligne `[ASSUMPTION]` | Explicitement déféré (UX-DR44 — à ratifier ou abandonner) |
| Idempotence anti double-clic des engagements financiers (« deux vitesses ») | AD-18 (clé d'idempotence obligatoire) |
| Pagination des données financières, routage trois espaces par rôle | Conventions frontend (UX-DR36) ; AD-21 (UX-DR20) |

## Manques — ni couverts ni déférés

### M1 — Cache de consultation horodaté hors-ligne
`EXPERIENCE.md` (§Offline & Synchronisation pt.1, §State Patterns, carte wallet) exige un **cache de lecture** : dernières transactions + détail consultables hors-ligne « données au… », dernière valeur connue du solde horodatée. Le spine ne connaît qu'une seule brique offline : la file IndexedDB (AD-9/AD-27), qu'il qualifie de « seul canal de rejeu ». Le cache de consultation est mentionné en parenthèse dans AD-27 (« consultation via cache ») mais **aucune décision** n'existe sur son support (store IndexedDB dédié ? cache Service Worker ? persistance Pinia ?), son horodatage, son invalidation, ni sa survie à la ré-authentification (State Pattern « Session expirée » : la file locale survit — le cache aussi ?). C'est une pièce d'architecture front distincte de la file, ni couverte ni déférée.

### M2 — Canal d'annonces `aria-live` (accessibilité structurelle)
`EXPERIENCE.md` (§Accessibility Floor) impose des annonces `aria-live` pour : transitions d'état, progression d'upload, passage online/offline, résultat du rejeu de file. Émettre ces annonces depuis des sources dispersées (stores Pinia, offlineQueue, composant upload) implique un **canal d'événements/annonceur centralisé côté front** (live region unique + bus). Le spine ne contient aucun invariant ni convention à ce sujet — et plus largement ne cite nulle part le plancher WCAG 2.2 AA pourtant transverse aux trois espaces.

### M3 — Équivalent devise locale indicatif + formats localisés
`EXPERIENCE.md` impose l'affichage systématique « 12 500,00 USD (≈ 1 615 000 KES) » (FR-P26) et des formats dates/nombres localisés (fr-FR / en-KE…). Or AD-14 cantonne la conversion **aux frontières** (dépôt/retrait, taux PSP) et AD-17 n'expose que « le taux appliqué » d'une opération : **la source du taux indicatif d'affichage** (endpoint taux courant ? fréquence ? mise en cache ? comportement hors-ligne ?) n'est ni couverte ni déférée. De même, AD-23 ne couvre que les catalogues de clés — aucune convention sur la couche de formatage localisé (module `Intl` unique, devise, chiffres tabulaires) alors que l'UX en fait une règle globale.

### M4 — Budget de poids / bas débit : aucune décision front associée
`EXPERIENCE.md` (§Responsive & Platform, §Accessibility Floor) fixe : « budget de poids strict sur les surfaces client », cible Android entrée de gamme en 3G, miniatures compressées + chargement paresseux des images de preuves. Deux implications architecturales absentes du spine :
1. **Front** : rien sur le code-splitting par espace (client vs `/admin` vs console — pourtant les trois vivent dans la même PWA), ni sur une garde de budget (CI bundle-size) qui rendrait la contrainte exécutoire.
2. **Backend/stockage** : les « miniatures compressées » supposent une **génération de vignettes** des preuves — le port `EvidenceStorage` (AD-6 hérité) n'en dit rien, aucun service ni story n'en est porteur.

### M5 — PWA installable et précache du shell
`EXPERIENCE.md` (§Offline pt.5, §Responsive & Platform) exige : manifest + icônes + écran de démarrage, **shell applicatif précaché**. Cela implique un **Service Worker** avec stratégie de précache, de versionnement et de mise à jour (interaction avec les déploiements, avec le cache de consultation M1, avec la garde d'espaces par rôle). Le spine dit « offline PWA » dans son scope mais ne pose aucun invariant ni décision sur le Service Worker — la seule brique offline nommée est la file IndexedDB. Ni couvert ni déféré.

## Recommandation

M1, M2 et M5 relèvent d'un même angle mort : le spine traite l'offline par la seule file de rejeu et ignore la couche « lecture/annonce/shell » du front. Un AD-28 (offline de lecture : SW + cache horodaté + annonceur) et une ligne de conventions frontend (formatage localisé, budget CI, code-splitting par espace) fermeraient l'essentiel ; la génération de miniatures (M4.2) mérite soit une extension d'AD-6/`EvidenceStorage`, soit une entrée Deferred explicite.
