# Annexe spike AR-P3 — Recherche fournisseurs KYB/AML (2026-07-25)

> Rapport de recherche brut. Sert de base factuelle à l'ADR-AR-P3.
> Corridors de lancement : Kenya↔Afrique du Sud, Nigéria↔Ghana.

## Constat structurant registres africains
- Registres cibles : CAC (NG), ORC (GH), BRS (KE), CIPC (ZA).
- **Ghana = trou de couverture API** : aucun fournisseur compliance-grade identifié pour l'ORC ; consultation manuelle du portail (OpenCorporates indexe avec retard).
- OHADA/RCCM (expansion future) : pas d'API tiers fiable (scraping/redistribution interdits) ; seul Youverify expose un endpoint Côte d'Ivoire (company filings).
- Conséquence : la file opérateur (Story 3.3) reste le chemin nominal ; l'API registre est un ENRICHISSEMENT du dossier, pas un décideur — valide FR-P30 et neutralise le risque Ghana.

## Comparatif (6 candidats)

| Critère | Smile ID | Dojah | Youverify | Sumsub | ComplyAdvantage Mesh | OpenSanctions/yente (repli) |
|---|---|---|---|---|---|---|
| KYB registres Afrique | NG (CAC+fisc), KE, ZA ; dirigeants+UBO | NG (CAC basic/advanced+TIN), KE, global search | Global + NG (TIN), KE premium, ZA, CI filings | 220+ pays (Enterprise) | Non (screening pur) | Non |
| Sanctions/PEP | 1 100+ listes mondiales ET africaines, 70 000+ sources adverse media | PEP/OFAC/sanctions/adverse media 200+ pays | ONU OFAC UE UK OFSI BAD + listes locales (FIC ZA, NG) ; MàJ 48 h | inclus plan Compliance | Référence marché : 60+ juridictions, PEP hiérarchisés, adverse media NLP | dataset public consolidé |
| Monitoring continu | auto 12 mois par nom | non documenté | non détaillé | « Ongoing AML Monitoring » | **re-screening auto 24 h + cases + webhooks** | à faire soi-même (= notre scheduler) |
| API/sandbox | REST sync + async recommandé (tolère indispo registre) | webhooks + sandbox | doc complète ; webhooks non confirmés | complet | OpenAPI, webhooks at-least-once | self-host (ES+yente) ou SaaS |
| Tarif indicatif | AML dès ~0,05 $/check (secondaire) ; KYB non publié | 0,04-0,06 $/appel | non publié | KYC 1,35-1,85 $/vérif (min 149-299 $/mois) ; KYB = Enterprise | Starter 99 $/mois (par entité monitorée) ; tier gratuit ComplyLaunch | SaaS 0,10 €/appel ; self-host licence forfaitaire |
| Localisation données (NFR-P23) | ISO 27001, SOC 2 II ; localisation non publiée | non documenté | SOC 2 II + ISO 27001/27018 + **accréditations DPA NG, KE, ZA, UG** | défaut Allemagne ; Local Data Processing MEA sur demande | UE/GDPR (UK/US) | **self-host = maîtrise totale** |
| Compat file opérateur (FR-P30) | résultats bruts | match_score paramétrable | résultats bruts | case management PROPRE (doublon avec notre file) | cases par webhook → routables vers notre file | totale |

Écartés : Prembly/IdentityPass (aucun registre nommé dans la doc publique), VerifyMe/QoreID (NG only, sans avantage).

## Pré-recommandation issue de la recherche (duo)
1. **KYB registres : Smile ID** (NG CAC+fiscal, KE, ZA, dirigeants/UBO ; API async tolérante aux registres indisponibles), **Youverify challenger** à confronter en POC (accréditations DPA NG/KE/ZA = 3 de nos 4 pays ; tarifs et webhooks à clarifier). **Ghana : revue documentaire manuelle** via la file opérateur (portail ORC) — aucun fournisseur fiable.
2. **Screening AML + monitoring : ComplyAdvantage Mesh** — seul avec re-screening 24 h natif, cases, webhooks ; alimente notre file (le fournisseur ne décide jamais) ; scheduler interne = réconciliation/filet. Entrée 99 $/mois compatible MVP.
3. **Repli (exigé par l'AC) : OpenSanctions/yente self-hosted** + revue 100 % manuelle — viable au MVP si la contractualisation traîne ; NFR-P23 par construction.
Alternative mono-fournisseur : Sumsub (KYB Enterprise + LDP Afrique) — profondeur registres africains moindre, tarif opaque, case management redondant avec UX-DR16. Non recommandé en premier choix.

## À verrouiller avant l'ADR finale (POC)
Localisation données Smile ID ; webhooks Youverify ; tarifs KYB réels des deux ; profondeur du champ UBO retourné par le CAC sur structures complexes.

## Contrat d'intégration esquissé
Soumission KYB (3.2) → appel registre sync/async (résultat stocké chiffré AD-24, horodaté, rétention WORM ≥5 ans — 3.5) + screening à l'appel → tout hit route vers la file 3.3 (JAMAIS d'auto-rejet) ; monitoring continu par webhook Mesh → alerte opérateur tracée (FR-P8) ; re-screening scheduler conservé en réconciliation.

### Sources principales
docs.usesmileid.com (KYB) · usesmileid.com blog (AML, ongoing monitoring) · smile.id/certifications · docs.dojah.io (CAC, AML business, sandbox) · dojah.io/pricing · doc.youverify.co (sitemap KYB, screening sources) · youverify.co (certifications DPA) · sumsub.com (pricing, privacy/LDP) · docs.mesh.complyadvantage.com (overview, webhooks) · opensanctions.org (self-hosted, licensing) · businessdataguide.com (Ghana ORC, RCCM — secondaire) · beverified.org (tarifs CA — secondaire).
