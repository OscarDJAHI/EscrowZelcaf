# Réconciliation PRD ↔ Architecture Spine — ce qui N'A PAS atterri

- **Entrée** : `prds/prd-Escrow_claude-2026-07-24/prd.md` + `addendum.md`
- **Spine** : `architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md`
- **Date** : 2026-07-24
- **Méthode** : parcours exhaustif FR-P1..P43, NFR-P1..P25, §2/§3/§8/§9, addendum §1–§7, confronté aux AD-13..27, invariants hérités, conventions, capability map et section Deferred. Ne sont listés que les manques ou déformations — pas ce qui est couvert ou explicitement déféré.

---

## Manques majeurs (exigence normative sans AD, sans convention, sans entrée Deferred)

### M1 — FR-P28 : 2FA TOTP totalement absente du spine
Le PRD exige une 2FA TOTP optionnelle (enrôlement QR, activation par OTP, codes de récupération, exigible à la connexion une fois activée). Le spine ne mentionne TOTP nulle part : ni AD, ni convention Sécurité (qui ne parle que de JWT/HMAC/signature PSP), ni entité domaine (stockage chiffré du secret TOTP et des codes de récupération — pourtant lié au « chiffrement au repos » de NFR §7), ni Deferred. C'est une exigence d'authentification structurante (flux de login à deux étapes, impact sur l'émission du JWT) qui a été perdue.

### M2 — FR-P14 : les rôles internes d'entreprise n'existent pas dans l'architecture
Le PRD (§3 et FR-P14) distingue les rôles **plateforme** et des **rôles internes** à l'entreprise (« une entreprise peut avoir plusieurs utilisateurs avec des rôles internes »). AD-21 ne modélise que les rôles plateforme `{BUYER, SELLER, ADMIN, ARBITRATOR}` ; aucune notion de rôle intra-entreprise (qui, au sein de « Nairobi Wholesale Ltd », peut financer vs consulter) dans le domaine, l'ERD ou une garde. La structure en AD a écrasé la distinction : FR-P14 est cité dans le `binds` global mais n'a aucun point d'atterrissage.

### M3 — FR-P8 : le screening AML « en continu » n'est pas adressé
FR-P8 exige un screening « à l'onboarding **et en continu** ». Le spine fixe le port `KybScreeningProvider` et AD-20 (gating), mais AD-20 ne lie pas FR-P8 et aucun mécanisme de re-screening périodique n'apparaît (le `scheduler/` n'est cité que pour les SLA du cycle escrow, AD-19). Le volet « continu » — job récurrent, réaction à un hit post-approbation (repasser l'entreprise hors d'`APPROVED` ? geler le wallet ?) — n'a ni règle ni entrée Deferred, alors qu'il interagit directement avec la machine KYB d'AD-20.

### M4 — NFR-P7 : l'antivirus obligatoire à l'ingestion a disparu
Le PRD (§6.E) lève explicitement le risque accepté POC : « NFR-6 (absence d'antivirus, risque accepté POC) est **levée par NFR-P7 (scan obligatoire)** » ; §7 liste « antivirus à l'ingestion ». Le spine n'en porte aucune trace : AD-7 hérité (validation d'ingestion Tika) date du spine POC qui avait précisément accepté l'absence d'antivirus ; Apache Tika est un détecteur de type, pas un antivirus ; aucun composant (ClamAV ou équivalent), aucun AD, aucune entrée Stack ni Deferred. Comme la brique upload est réutilisée pour KYB, tickets et preuves de virement, le manque s'étend à tout le périmètre.

### M5 — FR-P35 + addendum §1 : l'interdiction d'impersonation n'est pas reprise ; la suspension d'entreprise n'a ni état ni garde
Deux garde-fous back-office explicitement « conservés malgré le retour du wallet » (addendum §1, FR-P35) :
- **Pas d'impersonation (« Login as User »)** : nulle part dans le spine. AD-13 couvre bien l'autre garde-fou (pas d'ajustement manuel de solde), mais rien n'interdit architecturalement un mécanisme d'impersonation — pattern du template maquettes que le PRD rejette et qu'une story back-office pourrait réintroduire de bonne foi.
- **Suspension/réactivation motivée et auditée** : la machine à états d'AD-20 (`DRAFT→SUBMITTED→UNDER_REVIEW→APPROVED|REJECTED`) n'a pas d'état suspendu, et la garde serveur centralisée ne couvre que le gating KYB. Une entreprise suspendue doit être bloquée sur toute action engageante (y compris avec KYB approuvé) — aucun mécanisme d'application n'est défini.

## Manques secondaires (à trancher ou à rattacher explicitement)

### S1 — Approbation humaine des flux financiers : jamais élevée en invariant
FR-P23 pose un invariant fort : « **Aucun crédit sans validation humaine** » (virement manuel) ; FR-P43 : « **approbation opérateur** avant exécution » du retrait. AD-15 décrit la mécanique comptable (réservation → exécution/rejet) sans exiger l'approbation opérateur comme précondition de l'exécution, et AD-17/AD-18 sont muets sur le crédit manuel. La capability map cite la « file opérateur », mais aucune règle n'empêche un chemin de code de créditer un dépôt manuel ou d'exécuter un payout sans approbation — exactement le genre de double-chemin que les AD-13/15 verrouillent pour le reste.

### S2 — Addendum §1 : les contraintes réglementaires par corridor ne sont ni reflétées ni déférées
L'addendum les déclare « exigences du plan de lancement », mais deux au moins ont une incidence architecturale que AR-P2 (Deferred) ne capture pas :
- **UEMOA — cantonnement continu** (BCEAO Instr. 008-05-2015) : AD-16 prévoit un contrôle « planifié et déclenchable » sans exigence de fréquence ; un rapprochement quotidien ne vaut pas cantonnement continu. À citer dans AR-P2 comme contrainte d'entrée du spike.
- **Afrique du Sud — TPPP « durée limitée »** : un wallet à solde dormant (contre-métrique explicite du PRD §2 : « solde dormant = risque réglementaire ») est en tension avec une détention à durée limitée ; aucune notion d'ancienneté/dormance des fonds dans le modèle (ledger, alerte, politique de restitution). Ni AD, ni Deferred.

### S3 — NFR-P23 : seule la localisation est déférée, pas le volet Malabo
AR-P5 défère les régions d'hébergement. Mais NFR-P23 exige aussi « minimisation, finalité, **notification d'incidents** » (principes UA/Malabo). Aucun AD, aucune convention, aucune entrée Deferred ne porte ces trois obligations (notamment la notification d'incidents, qui demande un dispositif opérationnel).

### S4 — FR-P5 : le « spread » a disparu
FR-P5 (normatif, résumé du backlog) : « taux figé à l'engagement, **spread**, devise de règlement définie ». AD-14 reprend le taux figé et les conversions aux frontières mais ne dit rien du spread (marge plateforme sur le change, ou son absence assumée au MVP USD-pivot). À trancher explicitement : soit le spread est un frais de méthode (FR-P24, AD-24), soit il est hors MVP — mais le silence actuel est une perte, pas une décision.

### S5 — Addendum §3 : critère PAPSS du choix du partenaire bancaire absent d'AR-P2
« Le choix du partenaire bancaire de cantonnement devrait privilégier une banque déjà participante PAPSS » (prépare le post-MVP §10.2). AR-P2 (Deferred) ne mentionne pas ce critère de sélection ; c'est précisément le genre d'intrant que la section Deferred existe pour transporter vers le spike.

## Points vérifiés et NON signalés (échantillon)
Couverts ou correctement déférés : terminologie du glossaire (« versement de preuve » vs « dépôt de fonds » respectée), wallet USD/pivot (AD-14), invariant de ségrégation (AD-16), PCI-DSS/parcours hébergés (AD-17, rejet maquette 14), invitations hors machine (AD-19), gating KYB (AD-20), non-rétroactivité et consentement versionné (AD-24), WORM ≥ 5 ans (AD-25), messagerie (AD-26), frontière offline non financière (AD-27), statut « en douane » §9.7 (Deferred), sandbox avant contrat bancaire (AD-17), i18n (AD-23), écritures compensatoires via writer unique (AD-13). L'ERD « un wallet par devise / unicité company+currency » généralise l'hypothèse « un wallet par entreprise » (FR-P25) sans la contredire au MVP USD-seul — non signalé.
