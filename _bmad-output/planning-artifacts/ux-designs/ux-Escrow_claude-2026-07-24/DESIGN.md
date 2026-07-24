---
name: Escrow ZLECAf
description: Identité visuelle de la plateforme d'escrow B2B ZLECAf — fintech de confiance, sobre et lisible, PWA Vue 3, bilingue EN/FR. Tokens CSS maison (pas de bibliothèque UI imposée).
status: final
sources:
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/prd.md
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/addendum.md
  - _bmad-output/planning-artifacts/analyse-maquettes-et-references-zlecaf.md
updated: 2026-07-24
colors:
  # Marque
  brand-navy: '#101E5A'
  brand-navy-soft: '#1B2C7A'
  primary: '#047857'
  primary-foreground: '#FFFFFF'
  primary-hover: '#065F46'
  # Surfaces & texte
  surface-page: '#F6F7FB'
  surface-card: '#FFFFFF'
  surface-inverse: '#101E5A'
  text-strong: '#111827'
  text-body: '#374151'
  text-muted: '#6B7280'
  text-on-inverse: '#F3F4F6'
  border: '#E5E7EB'
  border-strong: '#9CA3AF'
  focus-ring: '#2563EB'
  # Sémantique financière & états
  success: '#047857'
  success-surface: '#ECFDF5'
  warning: '#B45309'
  warning-surface: '#FFFBEB'
  danger: '#B91C1C'
  danger-surface: '#FEF2F2'
  info: '#1D4ED8'
  info-surface: '#EFF6FF'
  neutral-state: '#4B5563'
  neutral-surface: '#F3F4F6'
  offline: '#92400E'
  offline-surface: '#FEF3C7'
  # Montants signés (ledger)
  amount-credit: '#047857'
  amount-debit: '#B91C1C'
typography:
  display:
    fontFamily: 'Inter'
    fontSize: 28px
    fontWeight: '700'
    lineHeight: '1.2'
    letterSpacing: -0.01em
  heading:
    fontFamily: 'Inter'
    fontSize: 20px
    fontWeight: '600'
    lineHeight: '1.3'
  body:
    fontFamily: 'Inter'
    fontSize: 15px
    fontWeight: '400'
    lineHeight: '1.5'
  label:
    fontFamily: 'Inter'
    fontSize: 13px
    fontWeight: '500'
    lineHeight: '1.4'
  caption:
    fontFamily: 'Inter'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.4'
  amount:
    fontFamily: 'Inter'
    fontSize: 15px
    fontWeight: '600'
    lineHeight: '1.4'
    letterSpacing: 0
  amount-hero:
    fontFamily: 'Inter'
    fontSize: 32px
    fontWeight: '700'
    lineHeight: '1.1'
rounded:
  sm: 4px
  md: 8px
  lg: 12px
  full: 9999px
  DEFAULT: 8px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
  '7': 48px
  gutter: 16px
  gutter-desktop: 24px
components:
  button-primary:
    background: '{colors.primary}'
    foreground: '{colors.primary-foreground}'
    radius: '{rounded.md}'
    minHeight: 44px
  button-danger:
    background: '{colors.danger}'
    foreground: '#FFFFFF'
    radius: '{rounded.md}'
    minHeight: 44px
  card:
    background: '{colors.surface-card}'
    border: '1px solid {colors.border}'
    radius: '{rounded.lg}'
    padding: '{spacing.4}'
  status-badge:
    radius: '{rounded.full}'
    typography: '{typography.label}'
    padding: '4px 10px'
  wallet-card:
    background: '{colors.surface-inverse}'
    foreground: '{colors.text-on-inverse}'
    radius: '{rounded.lg}'
    amount: '{typography.amount-hero}'
  banner-kyb:
    background: '{colors.warning-surface}'
    foreground: '{colors.warning}'
    border: '1px solid {colors.warning}'
    radius: '{rounded.md}'
  banner-offline:
    background: '{colors.offline-surface}'
    foreground: '{colors.offline}'
    radius: '0'
---

# Escrow ZLECAf — DESIGN.md

> `[ASSUMPTION]` Aucune charte de marque n'a été fournie. Cette identité dérive de la référence visuelle EscrowLab (navy institutionnel + accent vert) recalibrée pour une posture fintech B2B et un contraste WCAG AA. Les maquettes EscrowLab restent une référence, pas une spécification ; ce document gagne en cas de conflit avec toute maquette.

## Brand & Style

La plateforme vend une seule chose : la **confiance** dans une transaction transfrontalière où les deux parties ne se connaissent pas. L'expression visuelle suit : sobriété bancaire, hiérarchie limpide, zéro décoration gratuite. Le navy profond porte l'institutionnel (chrome, en-têtes, carte wallet) ; le vert n'est pas un vert « croissance startup » mais un vert engagement — il signifie *fonds sécurisés, action validée*. Tout ce qui touche à l'argent est présenté avec la précision d'un relevé bancaire : chiffres tabulaires, montants signés, solde après opération.

La voix de marque est **calme, factuelle, bilingue** (EN/FR). Elle n'exagère jamais (« vos fonds sont en sécurité », pas « 100 % garanti ! ») et ne cache jamais un état d'attente : KYB en revue, virement en rapprochement, retrait en approbation — chaque attente a une couleur, un libellé et un délai annoncé.

`[ASSUMPTION]` Implémentation en **tokens CSS maison sur Vue 3** (variables CSS custom properties), sans bibliothèque UI imposée — le choix d'une lib (PrimeVue, Naive UI…) relève de l'architecture ; ces tokens en sont le contrat.

## Colors

- **Brand Navy (`{colors.brand-navy}`)** — chrome applicatif : barre de navigation, carte wallet, pied de page de la landing. Jamais utilisé comme couleur d'état.
- **Primary Green (`{colors.primary}`)** — actions primaires et confirmations financières. Contraste ≥ 4,5:1 avec du texte blanc (le vert clair EscrowLab `#50DF77` est explicitement rejeté pour cette raison). Un seul bouton primaire par surface.
- **Sémantique d'état** — le cycle de vie escrow a un code couleur stable dans toute l'app (badges, timeline, filtres, back-office) :

| État / statut | Couleur | Surface |
|---|---|---|
| Invitation en attente / `INITIATED` | `{colors.warning}` | `{colors.warning-surface}` |
| `FUNDS_LOCKED` | `{colors.info}` | `{colors.info-surface}` |
| `SHIPPED` | `{colors.info}` (icône camion) | `{colors.info-surface}` |
| `RELEASED` / crédit | `{colors.success}` | `{colors.success-surface}` |
| `DISPUTED` | `{colors.danger}` | `{colors.danger-surface}` |
| `REFUNDED` / expirée / annulée | `{colors.neutral-state}` | `{colors.neutral-surface}` |
| Hors-ligne / en file de sync | `{colors.offline}` | `{colors.offline-surface}` |

- **Montants signés** — crédits en `{colors.amount-credit}` préfixés `+`, débits en `{colors.amount-debit}` préfixés `−` ; le signe et le libellé portent l'information, jamais la couleur seule (accessibilité).
- Interdits : dégradés décoratifs, plus d'un accent de marque, rouge/vert sans texte associé.

## Typography

**Inter** partout (excellente couverture latin étendu FR/EN, chiffres tabulaires via `font-variant-numeric: tabular-nums` obligatoires sur tout montant, solde et tableau financier). Rampe : `display` (titres de page), `heading` (sections/cartes), `body`, `label` (boutons, badges, libellés de champ), `caption` (métadonnées, horodatages), `amount` et `amount-hero` (solde wallet). Pas de serif, pas de fantaisie : la personnalité vient de la couleur et de la rigueur, pas de la typo.

## Layout & Spacing

Échelle en base 4 (`{spacing.1}`–`{spacing.7}`). App client **mobile-first** : une colonne, gouttière `{spacing.gutter}`, contenu max 720 px pour les formulaires et détails ; listes financières max 960 px. Back-office **desktop-first** : layout à barre latérale fixe, tableaux pleine largeur max 1280 px. Cibles tactiles ≥ 44 px. Les formulaires financiers (dépôt, retrait, wizard) sont toujours en une colonne avec récapitulatif des frais en carte adjacente (desktop) ou empilée (mobile).

## Elevation & Depth

Élévation quasi nulle : cartes bordées (`{colors.border}`) sans ombre au repos ; ombre légère (`0 1px 3px rgb(0 0 0 / 0.1)`) réservée aux surfaces flottantes (menus, toasts, modales). La hiérarchie vient du fond de page `{colors.surface-page}` contre les cartes blanches, pas des ombres.

## Shapes

`{rounded.md}` (8 px) pour champs et boutons, `{rounded.lg}` (12 px) pour cartes et modales, `{rounded.full}` uniquement pour badges d'état et avatars. Lisibilité « outil financier », ni angles durs ni rondeurs consumer.

## Components

- **Bouton primaire** — un par surface ; les confirmations financières affichent le montant dans le libellé (« Financer — 12 500,00 USD »). Variantes secondaire (bordure navy), ghost, danger.
- **Carte wallet** — fond `{colors.surface-inverse}`, solde en `{typography.amount-hero}` blanc, mention « adossé au compte cantonné » en `{typography.caption}`, deux actions : Déposer / Retirer.
- **Badge d'état** — pilule `{components.status-badge}`, couleur sémantique + libellé i18n ; jamais d'icône seule.
- **Ligne de ledger** — description, référence, horodatage, montant signé tabulaire, **solde après opération** en `{typography.caption}` sous le montant.
- **Bannière KYB** — `{components.banner-kyb}` persistante tant que le KYB n'est pas approuvé ; variante rejet en `{colors.danger-surface}` avec motif et CTA de re-soumission.
- **Bannière offline** — bandeau pleine largeur `{components.banner-offline}` collé sous la barre de navigation ; compteur d'éléments en file de synchronisation.
- **Timeline de transaction** — verticale, jalons = états du cycle de vie avec horodatage ; l'état courant en couleur pleine, les futurs en `{colors.text-muted}`.
- **Zone de versement de preuve** — carte en pointillés `{colors.border-strong}`, états : prêt / envoi / en file offline / échec / versé ; la variante CoO ZLECAf ajoute le panneau de métadonnées (n° certificat, autorité, date, Approved Exporter, état DUPLICATE/RETROSPECTIVE/REPLACEMENT, alerte validité 12 mois).
- **File opérateur** — tableau desktop : ligne = dossier, tri par ancienneté, badge SLA (vert < 50 % du SLA, ambre, rouge dépassé) ; panneau de détail latéral avec actions Approuver / Rejeter (motif obligatoire au rejet).

## Do's and Don'ts

| À faire | À éviter |
|---|---|
| Chiffres tabulaires et solde après opération sur tout ce qui est financier | Montants en typographie proportionnelle ou sans devise |
| Un code couleur d'état unique app + back-office | Réinventer des couleurs d'état par écran |
| Vert `{colors.primary}` ≥ AA pour texte blanc | Le vert clair EscrowLab `#50DF77` sur texte blanc |
| Attentes visibles (KYB, rapprochement, approbation) avec délai annoncé | Spinners muets ou états d'attente cachés |
| Parcours PSP hébergé (redirection/iframe tokenisée) | Toute saisie de carte dans nos écrans (NFR-P22) |
| Libellés bilingues par clés i18n | Texte en dur, drapeaux comme sélecteur de langue (utiliser « EN / FR ») |
