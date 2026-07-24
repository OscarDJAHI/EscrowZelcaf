# Réconciliation source → PRD — analyse des maquettes & références ZLECAf

**Date : 2026-07-24 — Objet : vérifier que le PRD production + addendum n'ont rien perdu ni contredit de `analyse-maquettes-et-references-zlecaf.md`.**

Convention : le wallet (solde d'entreprise, dépôts, ledger, retraits approuvés) a été **réintégré** au PRD le 2026-07-24 — il n'est donc pas traité comme écart. Les écarts sont classés par gravité : 🔴 perte substantielle, 🟠 incohérence interne, 🟡 abandon silencieux ou imprécision mineure.

---

## 1. Écarts réglementaires (source §3)

### 🔴 1.1 — Voie « Approved Exporter » perdue dans FR-P32
La source (§3.1) définit **deux** conditions alternatives pour la déclaration d'origine sur facture : « envois ≤ 5 000 USD **ou** "Approved Exporter" avec n° d'autorisation ». Le PRD (FR-P32) n'a retenu que la première : « La déclaration d'origine sur facture (envois ≤ 5 000 USD) est acceptée comme alternative ». Le n° « Approved Exporter » survit uniquement comme métadonnée du CoO, alors que dans la source c'est une **voie de preuve autonome sans plafond de montant**. Un vendeur Approved Exporter avec un envoi > 5 000 USD serait indûment contraint de produire un CoO. → Corriger FR-P32 : la déclaration sur facture est valable si (envoi ≤ 5 000 USD) **ou** (exportateur agréé avec n° d'autorisation).

### 🔴 1.2 — Le blocage KYC/KYB des retraits a disparu
La source (§2.E) relève explicitement le toggle « KYC bloque les retraits » et (§2.C) l'« alerte KYC bloquante » du dashboard. Le PRD FR-P31 borne le blocage KYB à « ni création, ni acceptation, ni financement de transaction » — **les retraits n'y figurent pas** — et FR-P43 (retraits) ne pose aucune condition KYB, seulement l'approbation opérateur. Avec le wallet réintégré, c'est un trou AML réel : un wallet crédité (par remboursement, par virement manuel approuvé, ou par une entreprise dont le KYB a été révoqué/suspendu après approbation initiale) pourrait être vidé vers un compte bancaire sans vérification d'identité aboutie. → Ajouter à FR-P31 ou FR-P43 : aucun retrait sans KYB approuvé (et statut non suspendu).

### 🟡 1.3 — Principes UA : « portabilité » (et « sécurité ») absents de NFR-P23
Source §3.5 : « finalité, minimisation, **sécurité**, notification d'incidents, **portabilité** ». NFR-P23 ne reprend que « minimisation, finalité, notification d'incidents ». La sécurité est couverte ailleurs (NFR-P1..21), mais la **portabilité des données** n'apparaît nulle part dans le PRD ni l'addendum. Impact produit potentiel (export du dossier KYB/évidences à la demande de l'entreprise). → Compléter NFR-P23 ou l'écarter explicitement.

### 🟡 1.4 — Attributs du CoO non repris dans le normatif
- « Papier **OU** électronique selon la législation nationale » (source §3.1) : présent en note d'addendum (§6) mais absent de FR-P32 — le PRD ne dit pas que le dépôt d'un scan de CoO papier et d'un CoO nativement électronique sont tous deux acceptés.
- « Tolérance aux erreurs formelles (art. 33) » : la source en fait une implication produit (l'arbitrage/la revue ne doit pas rejeter un CoO pour vice de forme mineur) ; l'addendum §6 la note (« ne pas sur-valider automatiquement ») mais FR-P32 n'en dit rien alors que le typage « sert le dossier et l'arbitrage ». Risque : une console d'arbitrage conçue sans cette consigne. → Une phrase dans FR-P32 ou dans les consignes d'arbitrage (FR-P11).

### ✅ Correctement couverts
Rétention ≥ 5 ans (FR-P33), validité 12 mois + états DUPLICATE/ISSUED RETROSPECTIVELY/REPLACEMENT (FR-P32), vérification d'origine 6 mois / mainlevée sous caution (question ouverte §9.7), localisation des données financières type Nigeria (NFR-P23), statut « draft » des annexes + veille (PRD §8, addendum §6), PCI-DSS / rejet de la saisie carte directe de la maquette 14 (NFR-P22).

---

## 2. Écrans & parcours des maquettes ni repris ni explicitement écartés

### 🟡 2.1 — Site vitrine (maquettes 01–04) : trois éléments silencieusement abandonnés
L'addendum §5 réduit les maquettes 01–04 à « landing + légal (FR-P41) ; blog/CMS post-MVP », mais la source liste aussi :
- le **simulateur d'escrow** sur la landing (calcul de frais avant inscription) — outil d'acquisition cohérent avec FR-P24 (frais « visibles avant confirmation »), jamais mentionné ;
- le **formulaire de contact** (avec reCAPTCHA) — aucun canal de contact pré-inscription dans le PRD (FR-P38 exige un compte) ;
- la **newsletter**.
Aucun des trois n'apparaît en §10 (évolutions) ni comme écarté. → Trancher explicitement (le simulateur mérite au moins la liste post-MVP).

### 🟡 2.2 — Annulation volontaire de transaction (maquettes 16–19)
La source cite l'« annulation » dans le détail escrow (et le statut « Canceled » du dashboard). Le PRD ne couvre que les terminaisons automatiques (FR-P12 : expiration, auto-remboursement) et le litige. L'annulation **à l'initiative d'une partie** (avant financement ; ou d'un commun accord après) n'est ni reprise ni écartée. → À expliciter (même si le POC la possède déjà, le PRD production est censé être la référence).

### 🟡 2.3 — Écrans de compte (maquettes 09, 22–23) partiellement muets
Complétion de profil (pays + indicatif mobile — nécessaire aux notifications SMS FR-P17), page profil, changement de mot de passe **connecté** (distinct de la réinitialisation FR-P13) : non mentionnés. Probablement implicites, mais la table addendum §5 les déclare « Repris (FR-P27/P28) » alors que ces FR ne parlent que d'inscription/OTP/2FA.

### 🟡 2.4 — Dashboard utilisateur (maquette 11) réduit au wallet
La source décrit des **KPI escrow par statut** (Not Accepted / Running / Completed / Disputed / Canceled) et l'alerte KYC bloquante sur le dashboard. Le PRD reprend le volet wallet (FR-P25/P42) mais aucun FR/parcours ne décrit le dashboard de pilotage des transactions. Défendable comme détail UX — mais alors la table addendum §5 (ligne 11, 13–15, 20 : « Repris ») surdéclare.

### 🟡 2.5 — Accès admin (maquettes 25–26) sans exigence
Login admin **dédié** (surface séparée, reCAPTCHA) + récupération par code email : rien dans le PRD sur le modèle d'accès du back-office (mêmes comptes JWT ? surface distincte ? 2FA obligatoire pour les opérateurs ?). Pour un back-office qui approuve des retraits, l'absence d'exigence d'authentification renforcée opérateur (ex. 2FA TOTP **obligatoire** pour ADMIN/ops, pas seulement optionnelle FR-P28) est une vraie lacune sécurité.

### 🟡 2.6 — Détails financiers des dépôts (maquette 14)
Limites **min/max par méthode de dépôt** : FR-P43 les prévoit pour les retraits, FR-P24 ne parle que des frais de dépôt — les limites de dépôt ont sauté. De même la **conversion de devise au dépôt** (passerelle × devise) n'est traitée qu'au payout (FR-P43) et via USD pivot (FR-P26).

### ✅ Correctement repris ou explicitement écartés
SSO (post-MVP §10.5), OTP + consentement (FR-P27), 2FA optionnelle (FR-P28), form-builder KYC écarté (FR-P30), milestones (post-MVP §10.1 + question source §5.3 tranchée), messagerie par transaction (FR-P34), tickets support (FR-P38), impersonation et ±solde manuel **rejetés avec motif** (FR-P35 + addendum §1 — exemplaire), notifications de masse écartées (§4 hors périmètre), ledger admin sans UI MVP (addendum §5), config no-code réduite (FR-P37), méthodes de retrait avec frais/limites (FR-P43), i18n par clés sans gestionnaire no-code (NFR-P24), spécificités à préserver (HMAC partenaire, offline-first, dossier ZLECAf) toutes conservées.

---

## 3. Incohérences table addendum §5 ↔ contenu réel du PRD

### 🟠 3.1 — Ligne 25–28 : « Login admin + dashboard KPI → Repris minimum vital (§6.G) » — faux
Le §6.G du PRD (FR-P15, P35–P38) ne contient **ni** login admin dédié **ni** dashboard KPI opérateur (files en attente, volumes, litiges ouverts — pourtant l'outil de travail principal de Nadia en UJ-3, qui « traite quatre files » sans qu'aucun FR ne fournisse la vue synthétique). La table déclare repris quelque chose que le PRD ne spécifie pas. → Soit ajouter un FR « tableau de bord opérateur : compteurs des files KYB/virements/retraits/tickets/litiges », soit corriger la table en « Écarté / porté par l'UX ».

### 🟠 3.2 — Ligne 06–10, 22–24 : « Repris sans SSO (FR-P27/P28) » — incomplet
FR-P27/P28 couvrent inscription, OTP, 2FA. La récupération de compte relève de FR-P13 (non cité dans la table) ; profil et changement de mot de passe ne sont couverts nulle part (cf. §2.3). La ligne surdéclare la couverture.

### 🟠 3.3 — Addendum §6 : renvoi « question ouverte §9.6 » erroné
« Vérification d'origine : jusqu'à 6 mois, mainlevée sous caution — nourrit la question ouverte §9.6 ». Dans le PRD, §9.6 est la **voie réglementaire du wallet** ; le statut « en douane » est la question **§9.7**. Renvoi à corriger (probable décalage de numérotation lors de la réintégration du wallet).

### 🟠 3.4 — Ligne 11, 13–15, 20 : « Repris » — partiellement
Le volet wallet/dépôts/ledger est bien repris (FR-P25/P42/P23), mais le dashboard KPI + alerte KYC de la maquette 11 ne l'est pas (cf. §2.4), et les limites de dépôt de la maquette 14 non plus (cf. §2.6).

---

## 4. Idées qualitatives de la source silencieusement perdues

1. **Signal PSP panafricain du template** (source §1 : Flutterwave/PayStack/Mobile Money natifs) — bien exploité (addendum §4). ✅
2. **« Support réutilise la brique évidences »** (source §2.F/tableau §4) — repris dans FR-P38. ✅ En revanche les bornes concrètes des pièces jointes support (max 5 fichiers, 256 Mo, types) ont disparu — acceptable si « réutilise la brique upload » implique ses limites, mais 256 Mo ≠ limites actuelles de la brique : à harmoniser en architecture.
3. **Throttling/envoi par lots des notifications** — lié aux notifications de masse écartées, cohérent. ✅
4. **« Feature-flags : à doser »** (source §2.H) — tranché par FR-P37 réduit. ✅
5. **Questions produit ouvertes de la source (§5.1 à 5.6)** — toutes tranchées dans le PRD (wallet, PSP, milestones, back-office, KYC fixe, vitrine). ✅ Aucune question orpheline.

---

## 5. Synthèse des actions recommandées

| # | Gravité | Action | Cible |
|---|---|---|---|
| 1 | 🔴 | Rétablir la voie « Approved Exporter » (sans plafond) pour la déclaration d'origine sur facture | FR-P32 |
| 2 | 🔴 | Conditionner les retraits au KYB approuvé/non suspendu | FR-P31 ou FR-P43 |
| 3 | 🟠 | Ajouter un FR dashboard opérateur (files/KPI) ou corriger la ligne 25–28 de la table | §6.G / addendum §5 |
| 4 | 🟠 | Corriger le renvoi §9.6 → §9.7 (statut « en douane ») | addendum §6 |
| 5 | 🟠 | Spécifier le modèle d'accès admin (surface, 2FA obligatoire opérateurs) | §6.G |
| 6 | 🟡 | Trancher simulateur d'escrow, contact pré-inscription, newsletter (reprendre, post-MVP ou écarter) | §4 / §10 / FR-P41 |
| 7 | 🟡 | Expliciter l'annulation volontaire de transaction | FR-P12 ou nouveau FR |
| 8 | 🟡 | Ajouter portabilité (principes UA) ou l'écarter explicitement | NFR-P23 |
| 9 | 🟡 | CoO papier/électronique + tolérance art. 33 dans le normatif | FR-P32 / FR-P11 |
| 10 | 🟡 | Limites min/max de dépôt par méthode ; profil/changement de mot de passe ; exactitude des lignes 06–10 et 11–20 de la table | FR-P24 / §6.C / addendum §5 |
