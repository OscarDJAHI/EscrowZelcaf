# Réconciliation PRD ↔ backlog production (`epics-production.md`)

**Date** : 2026-07-24
**Source normative amont** : `_bmad-output/planning-artifacts/epics-production.md` (22 FR-P, 21 NFR-P, 6 AR-P, 10 epics / 46 stories)
**Documents vérifiés** : `prd.md` + `addendum.md` (dossier `prd-Escrow_claude-2026-07-24`)
**Prétention du PRD** : « les identifiants `FR-P1..P22` et `NFR-P1..P21` proviennent du backlog production et restent normatifs (résumés en une ligne) ».

Verdict global : la traçabilité des identifiants est **quasi complète et les résumés sont fidèles au mot près**, à trois exceptions près (NFR-P21 omis du résumé, FR-P18 tronqué, FR-P12 réécrit silencieusement). En revanche, **4 des 6 AR ne sont pas référencés**, et surtout la **réintégration du wallet contredit la lettre de FR-P1/FR-P2 et les stories de l'Epic 2**, qui ont été écrites sous l'hypothèse « flux par transaction » — le PRD conserve ces identifiants « normatifs » tout en décrivant un modèle qui ne leur est plus conforme.

---

## 1. FR-P1 → FR-P22 : couverture et fidélité

| ID | Référencé dans le PRD | Résumé fidèle ? | Remarques |
|----|----|----|----|
| FR-P1 | §6.A | ⚠️ Fidèle au texte, **infidèle au modèle** | Le résumé reprend mot pour mot « encaissement… via PSP avant `FUNDS_LOCKED` ». Mais §1/§5 (UJ-2) décrivent un financement **depuis le wallet** : au moment du `FUNDS_LOCKED`, aucun PSP n'intervient, c'est un débit de solde. Le PRD est incohérent avec lui-même (voir §5 ci-dessous). |
| FR-P2 | §6.A | ⚠️ Idem | « `RELEASED` = versement effectif au vendeur » — dans le modèle wallet, `RELEASED` **crédite le wallet** ; le versement effectif est différé au retrait approuvé (FR-P43). Le résumé n'est pas déformé, mais le sens opérationnel a changé sans que l'écart soit signalé. |
| FR-P3 | §6.A | ✅ | Fidèle. Renforcé par NFR-P25 (invariant de ségrégation) — cohérent. |
| FR-P4 | §6.A | ✅ / ⚠️ | Fidèle, mais **recouvrement avec FR-P24** : la répartition des frais acheteur/vendeur/50-50 (FR-P24) contredit partiellement « déduite du versement au release » (la part acheteur n'est pas déduite du versement vendeur). Voir §3. |
| FR-P5 | §6.B | ✅ / ⚠️ | Fidèle. Tension avec FR-P26 (USD pivot) : voir §3. |
| FR-P6 | §6.B | ✅ | Fidèle. |
| FR-P7 | §6.D | ✅ | Fidèle ; raffiné par FR-P30/P31 (cohérent). |
| FR-P8 | §6.D | ✅ | Fidèle. |
| FR-P9 | §3 + §6.C | ✅ | Fidèle. |
| FR-P10 | §6.D | ✅ | Fidèle ; concrétisé par FR-P33 (≥ 5 ans), cross-référencé explicitement — bon exemple de raffinement propre. |
| FR-P11 | §6.F | ✅ | Fidèle. |
| FR-P12 | §6.F | ⚠️ **Amendé silencieusement** | Le backlog dit « expiration d'une transaction non financée » ; le PRD écrit « non financées **ou non acceptées** » (extension induite par FR-P29). L'extension est cohérente mais un identifiant déclaré normatif a été réécrit sans mention d'amendement. Par ailleurs l'« auto-remboursement » devient un crédit wallet (même glissement que FR-P2). |
| FR-P13 | §6.C | ✅ | Fidèle. |
| FR-P14 | §3 + §6.C | ✅ | Fidèle. |
| FR-P15 | §5 (UJ-3) + §6.G | ✅ | Fidèle. |
| FR-P16 | §3 + §6.C | ✅ | Fidèle, marqué « (livré) » — exact (commit 204def7, Story 1.1). |
| FR-P17 | §6.H | ✅ | Fidèle ; complété par FR-P39 (in-app), sans collision. |
| FR-P18 | §6.H | ⚠️ **Tronqué** | Le résumé PRD omet « **secret généré côté serveur** » (présent dans le backlog et dans la Story 6.2). Le backlog reste normatif, mais le résumé une-ligne perd une clause de sécurité. |
| FR-P19 | §6.I | ✅ | Fidèle (regroupé). Le détail « trancher le risque double-soumission » est couvert par la mention de l'idempotence. |
| FR-P20 | §6.I | ✅ | Fidèle (« cache local du détail de transaction »). |
| FR-P21 | §6.I | ✅ | Fidèle (« dépôt de preuve simple hors-ligne »). |
| FR-P22 | §6.I | ✅ | Fidèle (« plafond 20 fichiers miroité côté front »). |

Note mineure sur §6.I : la ligne groupée FR-P19→P22 inclut « idempotence du rejeu », qui est en réalité **NFR-P10** — mélange FR/NFR sans conséquence (NFR-P10 est aussi listé en §7), mais à corriger pour la propreté de la traçabilité.

**Bilan FR** : 22/22 référencés. 0 déformation flagrante du texte ; 2 glissements sémantiques majeurs non signalés (FR-P1, FR-P2 — cause : wallet), 1 amendement silencieux (FR-P12), 1 troncature (FR-P18).

---

## 2. NFR-P1 → NFR-P21 : couverture et fidélité

Le PRD §7 les couvre par une déclaration globale (« restent normatives ») suivie d'une énumération compressée. Vérification terme à terme de l'énumération :

| ID | Élément du résumé §7 | Statut |
|----|----|----|
| NFR-P1 | « secrets externalisés » | ✅ (le « échec au démarrage si var manquante » n'est pas repris — acceptable en résumé, le backlog fait foi) |
| NFR-P2 | « rate-limiting » | ✅ |
| NFR-P3 | « TLS/HSTS/CSP » | ✅ |
| NFR-P4 | « CORS » | ⚠️ La clause « **Swagger/OpenAPI fermés en production** » n'est pas reprise dans le résumé. Mineur (backlog normatif), mais c'est la moitié de l'exigence. |
| NFR-P5 | « politique de mots de passe et révocation JWT » | ✅ |
| NFR-P6 | « chiffrement au repos » | ✅ |
| NFR-P7 | « antivirus à l'ingestion » | ✅ |
| NFR-P8 | « hygiène de session » | ✅ |
| NFR-P9 | « anti-énumération » | ✅ |
| NFR-P10 | « idempotence du rejeu offline » | ✅ (doublement cité, cf. §6.I) |
| NFR-P11 | « CI/CD » | ✅ |
| NFR-P12 | « SBOM » | ✅ |
| NFR-P13 | « observabilité » | ✅ (relié à NFR-P25 — cohérent) |
| NFR-P14 | « backup/restore testé » | ✅ |
| NFR-P15 | « stack déployable » | ✅ |
| NFR-P16 | « profils d'environnements » | ✅ |
| NFR-P17 | « résilience » | ✅ |
| NFR-P18 | « rétention d'audit » | ✅ |
| NFR-P19 | « tests de charge » | ✅ |
| NFR-P20 | « E2E » | ✅ |
| NFR-P21 | — | ❌ **Absent de l'énumération.** La « résorption de la dette de tests » (ledger Cluster 4 : chemin backend relatif, mock de clé, commentaire trompeur, couverture composant) n'apparaît nulle part dans le PRD. La déclaration chapeau « NFR-P1 à NFR-P21 restent normatives » la couvre formellement, mais le lecteur du PRD n'a aucun moyen de savoir ce que recouvre NFR-P21. |

**Bilan NFR** : 21/21 couverts par la déclaration chapeau ; 20/21 réellement résumés ; 1 omission de contenu (NFR-P21), 1 troncature (NFR-P4).

---

## 3. Nouveaux FR-P23..P43 et NFR-P22..P25 : collisions et redondances

Les 21 nouveaux FR (P23→P43, tous présents, quoique P42/P43 soient rangés hors ordre dans la section A) et 4 nouveaux NFR ne réutilisent **aucun identifiant existant** — pas de collision de numérotation. Sur le fond :

### Collisions / tensions réelles

1. **FR-P23 (virement manuel) vs FR-P1** — FR-P1 dit « encaissés **via un PSP** » ; FR-P23 introduit un canal d'encaissement **hors PSP** (virement + preuve + validation opérateur). Extension légitime mais contradictoire avec la lettre de FR-P1, qui devrait être amendée (« via PSP **ou** virement manuel validé »).
2. **FR-P24 (barème + répartition des frais) vs FR-P4** — recouvrement : FR-P24 subsume le calcul de commission de FR-P4 et le contredit partiellement. FR-P4 : commission « déduite du versement au release » (modèle 100 % vendeur). FR-P24 : répartition acheteur / vendeur / 50-50 — la part acheteur est nécessairement **ajoutée au financement**, pas déduite du versement. FR-P4 devrait être marqué comme amendé par FR-P24.
3. **FR-P26 (USD pivot) vs FR-P5 (bi-devise, taux figé à l'engagement)** — tension non résolue : si les contrats sont en USD et que le versement au vendeur se fait « dans sa devise locale via le PSP », à quel moment le taux figé à l'engagement (FR-P5) s'applique-t-il, sachant que le versement effectif est différé au **retrait** (FR-P43) ? Le taux figé à l'engagement et la conversion au retrait ne peuvent pas être tous deux vrais sans règle explicite. La Story 8.2 (« versement et remboursement utilisent le taux figé ») est directement mise en tension. De plus, la **devise du wallet** (FR-P25 « solde unique ») n'est jamais définie — USD ? multi-poches ?
4. **FR-P25/P42/P43 (wallet, ledger, retraits) vs FR-P1/P2** — voir §5 : c'est la collision structurante.

### Redondances bénignes (raffinements cohérents)

- FR-P30/P31 raffinent FR-P7 (formulaire fixe, revue manuelle, blocage lecture-seule) — cohérent.
- FR-P33 concrétise FR-P10 (≥ 5 ans, WORM) avec cross-référence explicite — propre.
- FR-P29 étend FR-P12 (expiration des invitations non acceptées) — cohérent, mais c'est ce qui a motivé la réécriture silencieuse de FR-P12 (cf. §1).
- FR-P37 (config des SLA) recouvre le paramétrage de FR-P12 — cohérent.
- FR-P39 (in-app) complète FR-P17 sans le recouvrir.
- FR-P40 (périmètre offline : pas d'action financière hors-ligne) borne FR-P19→P22 sans les contredire.
- FR-P27/P28 (OTP, 2FA TOTP) complètent NFR-P5 — pas de collision ; noter que le backlog ne prévoyait ni OTP ni 2FA, ce sont de vrais ajouts.
- NFR-P25 (invariant de ségrégation) recouvre partiellement le « rapprochement » de FR-P3 — redondance assumée et utile (le rapprochement devient un invariant alerté), cross-référencée.
- NFR-P22 (PCI-DSS), NFR-P23 (localisation des données), NFR-P24 (i18n) — nouveaux, sans collision. NFR-P24 est un ajout à fort impact transversal (bilingue dès le MVP) absent du backlog : les 46 stories existantes n'en tiennent aucun compte.

---

## 4. Les 6 AR (spikes) : référencement

| AR | Objet | Référencé dans le PRD ? |
|----|----|----|
| AR-P1 | Choix PSP | ✅ §8 (« Flutterwave ou Paystack [décision en spike AR-P1] »), §9.4, addendum §4. Le §8 note à raison que la voie réglementaire du wallet **conditionne** ce spike. |
| AR-P2 | Modèle de cantonnement & comptabilité | ❌ **Non référencé** — et c'est le plus problématique : le wallet change précisément le modèle de cantonnement (soldes portés par un partenaire licencié, immobilisation via compte cantonné et non via « hold » PSP — addendum §4). AR-P2 et la Story 2.1 (spike) devraient être explicitement re-cadrés par le PRD. |
| AR-P3 | Fournisseur KYB/KYC/AML | ✅ §8, §9.4. |
| AR-P4 | Gestion des secrets | ❌ Non référencé. Couvert implicitement par « NFR-P1..P21 restent normatives », mais le spike n'est pas cité. |
| AR-P5 | Cible de déploiement | ❌ Non référencé (même situation qu'AR-P4, via NFR-P15). |
| AR-P6 | Fournisseur notifications | ❌ Non référencé, alors que FR-P17 est repris et que la Story 6.1 en dépend. |

**Bilan AR : 2/6 référencés.** AR-P4/P5/P6 sont des omissions vénielles (les NFR porteurs sont déclarés normatifs) ; **AR-P2 est une omission de fond**, car son périmètre est exactement celui que le wallet redéfinit.

---

## 5. Le wallet contredit-il le backlog « flux par transaction » ? — Oui, sur 4 points

Le backlog (Epic 2 en particulier) a été écrit sous l'hypothèse d'un **flux financier par transaction** : l'acheteur paie via PSP *pour une transaction donnée*, le release *verse* au vendeur, le refund *rembourse* l'acheteur. Le PRD réintègre le wallet (décision utilisateur du 2026-07-24 après-midi, documentée addendum §1) : dépôt → solde d'entreprise → financement par débit de solde → crédit du solde vendeur → retrait approuvé.

**Contradictions précises :**

1. **Story 2.2 (AC)** : « *Given* une transaction `INITIATED`, *When* **l'acheteur paie via le PSP et l'encaissement est confirmé**, *Then* la transaction passe `FUNDS_LOCKED` ». Dans le modèle wallet (UJ-2), le passage à `FUNDS_LOCKED` est un **débit de solde** ; l'encaissement PSP a eu lieu en amont, découplé, au dépôt. L'AC est invalidée telle quelle. FR-P1 reste satisfiable *transitivement* (les fonds sont bien sur le compte cantonné avant `FUNDS_LOCKED`), mais le mécanisme décrit ne correspond plus.
2. **Story 2.3 (AC) / FR-P2** : « *When* la transition [`RELEASED`] est committée, *Then* **un versement au vendeur est initié… suivi jusqu'à confirmation** ». Dans le modèle wallet, `RELEASED` produit un crédit de solde ; le versement effectif n'a lieu qu'au **retrait**, potentiellement bien plus tard, après approbation opérateur (FR-P43), voire jamais (solde dormant — le PRD en fait d'ailleurs une contre-métrique). Idem pour `REFUNDED` → crédit du solde acheteur, pas remboursement effectif. FR-P2 et la Story 2.3 doivent être réécrites (découplage transition ↔ mouvement externe).
3. **Story 8.2 / FR-P5** : « versement et remboursement utilisent le **taux figé** » — avec conversion au dépôt et/ou au retrait (USD pivot, FR-P26), le taux applicable au versement en devise locale n'est plus mécaniquement celui figé à l'engagement. Règle de conversion à trancher (spike AR-P1/AR-P2 élargi).
4. **FR-P12** : « auto-remboursement » devient un crédit wallet — bénin isolément, mais participe du même glissement.

**Ce qui reste cohérent** : FR-P3 (grand livre) et NFR-P25 sortent **renforcés** par le wallet ; les garde-fous back-office (pas d'ajustement de solde en saisie libre, pas d'impersonation — FR-P35, addendum §1) sont alignés avec l'esprit du backlog.

**Trou de couverture epics** : le wallet (FR-P25, FR-P42, FR-P43, FR-P23) n'a **aucune story** dans `epics-production.md` — pas d'epic « Wallet & retraits », et l'Epic 2 existant décrit un flux devenu faux. La FR Coverage Map du backlog n'est plus exhaustive. Attendu (le PRD est postérieur), mais le PRD affirme conserver le backlog comme normatif sans signaler que l'Epic 2 (stories 2.2, 2.3) et la Story 8.2 doivent être révisées.

---

## 6. Synthèse des écarts et recommandations

| # | Écart | Gravité | Recommandation |
|---|---|---|---|
| 1 | FR-P1/FR-P2 (et stories 2.2, 2.3) écrits en « flux par transaction », contredits par le modèle wallet que le PRD adopte, tout en déclarant ces identifiants normatifs sans amendement | **Majeure** | Ajouter au PRD un encart d'amendement explicite : FR-P1 → « encaissement au dépôt wallet (PSP ou FR-P23), fonds sur compte cantonné avant tout `FUNDS_LOCKED` » ; FR-P2 → « `RELEASED`/`REFUNDED` = crédit de solde adossé au grand livre ; sortie effective des fonds au retrait (FR-P43) ». Réviser Epic 2 (stories 2.2/2.3) et créer un epic Wallet (FR-P23/P25/P42/P43). |
| 2 | AR-P2 (cantonnement/comptabilité) non référencé alors que le wallet en redéfinit le périmètre ; AR-P4/P5/P6 non cités | **Moyenne** | Référencer les 6 AR en §8 ; élargir explicitement le spike Story 2.1 (AR-P1+AR-P2) au modèle wallet et à la voie réglementaire (§9.6). |
| 3 | FR-P5 vs FR-P26 vs FR-P43 : moment d'application du taux figé indéterminé (engagement ? dépôt ? retrait ?) + devise du wallet non définie | **Moyenne** | Trancher en question ouverte §9 (ou au spike) ; amender la Story 8.2. |
| 4 | NFR-P21 (dette de tests) absent du résumé §7 ; FR-P18 tronqué (secret côté serveur) ; NFR-P4 tronqué (Swagger fermé en prod) ; FR-P12 réécrit sans mention | **Mineure** | Compléter le résumé §7 et §6.H/§6.F ; marquer FR-P12 « amendé par FR-P29 ». |
| 5 | FR-P4 vs FR-P24 : « commission déduite du versement » incompatible avec la répartition acheteur/50-50 | **Mineure** | Marquer FR-P4 « amendé par FR-P24 ». |
| 6 | NFR-P24 (i18n bilingue MVP) : exigence transversale nouvelle dont aucune des 46 stories du backlog ne tient compte | **Mineure** (mais coût transversal) | En tenir compte au re-découpage des epics. |

**Conclusion** : le PRD est une bonne synthèse fidèle sur 40 des 43 identifiants hérités ; les écarts ne sont pas des pertes d'exigences mais des **conflits de modèle non déclarés** (wallet vs flux par transaction) et des **références de spikes manquantes**. Un passage de « correct course » sur l'Epic 2, la Story 8.2 et la FR Coverage Map suffit à refermer l'écart.
