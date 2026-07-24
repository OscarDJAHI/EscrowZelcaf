---
name: Escrow ZLECAf
status: final
sources:
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/prd.md
  - _bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/addendum.md
  - _bmad-output/planning-artifacts/analyse-maquettes-et-references-zlecaf.md
updated: 2026-07-24
---

# Escrow ZLECAf — Experience Spine

> Produit en mode autonome depuis le PRD (source de vérité, préséance sur ce document pour le périmètre). Les maquettes EscrowLab sont une référence visuelle, pas une spécification ; ce spine et `DESIGN.md` gagnent sur toute maquette. Les hypothèses UX sont taguées `[ASSUMPTION]`.

## Foundation

**PWA Vue 3 responsive, offline-first**, backend Spring Boot (stack conservée). Une seule application, trois espaces sous la même authentification JWT + rôles :

- **App client** (Amina, Thabo) — mobile-first, installable, fonctionne en connectivité instable.
- **Console d'arbitrage** (Koffi) — desktop-first, accessible au rôle arbitre uniquement (FR-P11).
- **Back-office** (Nadia) — desktop-first, sous `/admin`, rôle ADMIN existant, pas de portail de login distinct (FR-P36). `[ASSUMPTION]`

Identité visuelle et tokens : `DESIGN.md`. i18n EN/FR par clés dès la fondation (NFR-P24) ; `[ASSUMPTION]` défaut EN, bascule persistée par utilisateur. Devise : montants en **USD** (pivot), équivalent devise locale affiché en secondaire à titre indicatif (FR-P26).

**Frontière offline (FR-P40), rappelée partout où elle s'applique :** consultation en cache, versement de preuves et ouverture de litige fonctionnent hors-ligne ; toute action financière (dépôt de fonds, financement, acceptation engageante, retrait) exige une connexion et se désactive hors-ligne avec explication.

## Information Architecture

### Espace public (non authentifié)

| Surface | Accès | Contenu |
|---|---|---|
| Landing | URL racine | Proposition de valeur, CTA « Créer un compte », lien connexion (FR-P41) |
| Pages légales | Pied de page | CGU, confidentialité, politique de paiement (éditées via FR-P37) |
| Inscription | CTA landing / invitation par email | Email + mot de passe, consentement légal horodaté (FR-P27) |
| Vérification OTP | Post-inscription | Code 6 chiffres, renvoi limité avec compte à rebours |
| Connexion | Lien landing | Email + mot de passe, puis TOTP si 2FA active (FR-P28) |
| Réinitialisation mot de passe | Lien connexion | Email → lien → nouveau mot de passe (FR-P13) |

### App client — Amina (acheteuse) & Thabo (vendeur) — mêmes surfaces, rôle par transaction

Navigation principale `[ASSUMPTION]` : barre latérale desktop / barre d'onglets mobile à 5 entrées — **Accueil · Transactions · Wallet · Support · Profil** — plus cloche de notifications dans l'en-tête.

| Surface | Accès | Contenu |
|---|---|---|
| Accueil (dashboard) | Ouverture app | Carte wallet (solde USD), bannière KYB si non approuvé, compteurs par état (en attente / en cours / litiges / terminées), actions rapides, dernières notifications |
| Transactions (liste) | Onglet | Liste bidirectionnelle « J'achète / Je vends », filtres par état, recherche |
| Nouvelle transaction (wizard) | CTA liste/accueil | 2 étapes : ① rôle, contrepartie (email — inscrite ou non, FR-P29), marchandise, catégorie, montant USD ② conditions & délai de livraison, répartition des frais B/S/50-50 avec **récapitulatif chiffré avant envoi** (FR-P24) |
| Détail transaction | Ligne de liste / notification | Timeline du cycle de vie, montants & frais, contrepartie, **dossier de preuves**, **messagerie**, actions contextuelles selon état et rôle (accepter, financer, marquer expédié, confirmer réception, ouvrir litige, télécharger récapitulatif) |
| Dossier de preuves | Onglet du détail | Versements contradictoires typés (photos, transport, **CoO ZLECAf** avec métadonnées FR-P32), retrait logique, verrou en état terminal — fonctionne hors-ligne |
| Litige | Action du détail | Ouverture motivée avec ≥ 1 preuve (atomique, offline capable), suivi de la décision motivée |
| Wallet | Onglet | Solde, actions Déposer / Retirer, **ledger** (FR-P42) : mouvements signés, référence, solde après opération, filtres type/période |
| Dépôt de fonds | Action wallet | Choix méthode : **PSP hébergé** (carte/mobile money/virement instantané — redirection, NFR-P22) ou **virement manuel** (référence + preuve jointe, statut « en rapprochement », FR-P23) ; frais par méthode affichés avant confirmation |
| Retrait | Action wallet | KYB requis ; coordonnées bancaires/mobile money, devise locale de versement, limites & frais affichés, statut demandé → approuvé/rejeté (montant réservé, FR-P43) |
| KYB entreprise | Bannière / Profil | Formulaire fixe ZLECAf (identité légale, registre, représentant, bénéficiaires effectifs, justificatifs — FR-P7/P30), statuts : brouillon / soumis / en revue / approuvé / rejeté (motif + re-soumission) |
| Profil & entreprise | Onglet Profil | Compte (mot de passe, 2FA TOTP avec QR + codes de récupération, langue), entreprise (membres & rôles internes FR-P14, invitation FR-P13) |
| Support | Onglet | Tickets : création (sujet, priorité, pièces jointes), fil de réponses, statuts (FR-P38) |
| Notifications | Cloche | Liste in-app des transitions clés + messages reçus (FR-P39), badge non-lus |

### Console d'arbitrage — Koffi

| Surface | Accès | Contenu |
|---|---|---|
| File des litiges | Entrée console | Litiges ouverts, assignation, ancienneté vs SLA |
| Dossier de litige | Ligne de file | Dossier contradictoire complet (preuves des deux parties + partenaire), messagerie (visible dès litige ouvert), chronologie, décision motivée `RELEASE`/`REFUND` — verrouille le dossier |

### Back-office — Nadia

| Surface | Accès | Contenu |
|---|---|---|
| Vue synthétique | Entrée `/admin` | Files en attente (KYB, dépôts manuels, retraits, litiges, tickets) avec compteurs et alertes SLA ; volumes clés (FR-P36) |
| File KYB | Carte de la vue | Dossiers soumis, revue pièce par pièce, approbation / rejet motivé notifié (FR-P30) |
| Dépôts manuels | Carte | Rapprochement : déclaration + preuve de virement côte à côte, Approuver (crédite le wallet) / Rejeter motivé (FR-P23 — inspiré maquette 36) |
| Retraits | Carte | Contrôle des coordonnées, montant réservé, Approuver / Rejeter (restitution) — maquettes 37-38 |
| Transactions | Menu | Liste filtrable état/corridor/période, détail complet (fonds, preuves, messages, audit), export CSV (FR-P36) |
| Utilisateurs & entreprises | Menu | Recherche, fiche détaillée (statuts, solde, transactions), suspension/réactivation motivée — **pas d'impersonation, pas de ±solde** (FR-P35) |
| Configuration | Menu | Barème de frais, catégories, délais/SLA, textes légaux — versionné et audité (FR-P37) ; clés HMAC partenaires (générer/afficher une fois/révoquer, FR-P15) |
| Tickets support | Menu | File, réponse, clôture (FR-P38) |

Fermeture de surface : chaque exigence FR-P du périmètre MVP ci-dessus atteint une surface, et chaque surface est atteinte par un parcours (§ Key Flows ou navigation directe). Les besoins hors MVP (milestones, marketplace, CMS, transporteur interactif) n'ont volontairement aucune surface.

## Voice and Tone

Microcopie bilingue par clés ; le ton est celui de `DESIGN.md.Brand & Style` : calme, factuel, précis. Vocabulaire **verrouillé sur le glossaire PRD §3** — c'est une règle de rédaction, pas une préférence :

| À écrire | À ne jamais écrire |
|---|---|
| « Dépôt de fonds » (alimentation du wallet) | « Dépôt » seul pour une preuve |
| « Versement de preuve » (pièce au dossier) | « Upload », « dépôt de preuve » |
| « Fonds sécurisés en votre faveur — Thabo peut expédier » | « Paiement reçu ! » |
| « Votre dossier KYB est en revue (sous 2 jours ouvrés) » | « Vérification en cours… » sans délai |
| « Virement en rapprochement — nous validons votre preuve » | « En attente » sans explication |
| « Hors-ligne : votre preuve est en file et partira à la reconnexion » | « Erreur réseau » |
| « Retrait demandé — 1 200,00 USD réservés sur votre solde » | Solde qui change sans explication |
| Montants toujours « 12 500,00 USD (≈ 1 615 000 KES) » | Montant sans devise ou conversion présentée comme garantie |

Les erreurs disent quoi faire ensuite ; les rejets (KYB, virement, retrait) affichent toujours le **motif** et l'action de reprise.

## Component Patterns

Comportemental — le visuel vit dans `DESIGN.md.Components`.

| Composant | Où | Règles de comportement |
|---|---|---|
| Badge d'état | Partout | Toujours libellé i18n + couleur ; même vocabulaire d'états app/console/back-office. |
| Timeline de transaction | Détail | États passés horodatés, état courant mis en avant, prochaine action attendue nommée avec son responsable (« En attente : financement par Amina ») ; échéances SLA affichées (auto-libération, expiration — FR-P12). |
| Carte wallet | Accueil, Wallet | Solde temps réel en ligne ; hors-ligne : dernière valeur connue + horodatage « au JJ/MM à HH:MM », actions financières désactivées. |
| Ligne de ledger | Wallet | Montant signé + solde après opération ; tap/clic → détail du mouvement lié (transaction, dépôt, retrait). |
| Confirmation financière | Financer, déposer, retirer, accepter | Modale récapitulative systématique : montant, frais détaillés par partie, solde après opération ; le bouton porte le montant. Jamais d'engagement en un clic. |
| Zone de versement de preuve | Dossier, litige, ticket | Multi-fichiers (plafond 20 — FR-P22), types/tailles contrôlés, progression par fichier, reprise sur échec, mode offline : mise en file locale visible. Variante CoO : formulaire de métadonnées FR-P32 + alerte validité 12 mois. |
| Messagerie de transaction | Détail | Texte seul `[ASSUMPTION PRD]`, horodatée, participants identifiés par rôle ; l'arbitre apparaît dès litige ouvert ; verrouillée (lecture seule) en état terminal ; renvoi vers le dossier de preuves pour les fichiers. |
| Bannière KYB | Toute l'app tant que non approuvé | 4 variantes : non soumis (CTA « Compléter »), en revue (délai annoncé), rejeté (motif + « Corriger et re-soumettre »), approuvé (disparaît après un toast). Les actions bloquées (créer/accepter/financer/retirer) restent visibles mais désactivées avec l'explication « Nécessite un KYB approuvé » (FR-P31). |
| File opérateur | Back-office | Tri ancienneté par défaut, badge SLA, détail latéral, rejet ⇒ motif obligatoire, chaque décision auditée ; après action, passage auto au dossier suivant. `[ASSUMPTION]` |
| Cloche de notifications | En-tête | Badge non-lus, liste antéchronologique, clic → surface concernée ; pas de push navigateur (FR-P39). |

## State Patterns

| État | Surface | Traitement |
|---|---|---|
| Chargement | Listes & détails | Skeletons calqués sur la mise en page cible ; jamais de spinner plein écran après la première peinture. |
| Vide — première fois | Transactions | « Aucune transaction. Créez la première ou attendez une invitation. » + CTA (si KYB approuvé). |
| Vide — filtré | Listes | « Aucun résultat pour ces filtres » + réinitialiser. |
| Vide — wallet | Ledger | « Aucun mouvement. Faites votre premier dépôt de fonds. » |
| Erreur réseau (en ligne) | Toute action | Toast + relance ; les formulaires conservent la saisie. |
| Erreur serveur | Surface | Carte d'erreur avec référence d'incident et « Réessayer » ; jamais de page blanche. |
| Hors-ligne | Global | Bandeau offline persistant + compteur de file de sync ; surfaces en cache lisibles avec horodatage ; actions financières désactivées (explication) ; preuves/litiges mis en file *(brouillons de messages retirés le 2026-07-24 — hors FR-P40, hors whitelist AD-27 du spine production)*. |
| Reconnexion | Global | Rejeu idempotent de la file (FR-P20) avec progression ; conflits (ex. transaction passée en état terminal pendant l'absence) signalés par notification, jamais d'écrasement silencieux. Écran de récupération offline-reject existant conservé. |
| Invitation en attente | Détail (créateur) | « Invitation envoyée à amina@… — expire le JJ/MM » + renvoyer ; contrepartie non inscrite : mention « en attente d'inscription et de KYB » (FR-P29). |
| KYB en attente / rejeté | Global app | Voir bannière KYB (Component Patterns) — lecture seule fonctionnelle, actions engageantes désactivées. |
| Dépôt en rapprochement | Wallet | Mouvement « en attente » distinct du solde disponible ; ni comptabilisé ni utilisable avant approbation. |
| Retrait demandé | Wallet | Montant réservé visible (« réservé : 1 200,00 USD ») ; rejet ⇒ restitution + notification motivée. |
| Fonds reçus avant KYB | Wallet | Solde visible mais verrouillé : « Fonds immobilisés jusqu'à l'approbation de votre KYB » (FR-P31). |
| État terminal | Détail, dossier, messagerie | Lecture seule, mention « Dossier verrouillé le JJ/MM », téléchargement du récapitulatif. |
| Session expirée | Global | Retour connexion avec conservation de la cible ; la file offline locale survit à la ré-authentification. |

## Interaction Primitives

- **Tactile d'abord** (app client) : cibles ≥ 44 px, actions principales en bas d'écran mobile, pas d'affordance hover-only.
- **Deux vitesses** : consulter est instantané et optimiste ; **engager de l'argent est toujours lent volontairement** (modale récapitulative, montant dans le bouton, pas de double-soumission — idempotence côté API).
- **Formulaires** : validation à la volée, erreurs sous le champ, soumission conserve la saisie ; les wizards permettent le retour arrière sans perte.
- **Fichiers** : caméra ou galerie sur mobile pour les preuves ; glisser-déposer sur desktop.
- **Interdits partout** : scroll infini sur données financières (pagination), engagement financier en un clic, modales empilées > 1 niveau, saisie de carte dans nos écrans (NFR-P22), toute action destructrice sans confirmation textuelle du motif (back-office).

## Accessibility Floor

- **WCAG 2.2 AA** sur les trois espaces ; contrastes garantis par les tokens `DESIGN.md` (le vert clair du template est rejeté pour cette raison).
- L'état financier n'est jamais porté par la couleur seule : signe, libellé et badge textuel systématiques.
- Navigation clavier complète ; ordre de tabulation = ordre de lecture ; `Esc` ferme la surface flottante du dessus ; focus visible (`{colors.focus-ring}`).
- Lecteurs d'écran : annonces `aria-live` pour transitions d'état, progression d'upload, passage online/offline et résultat du rejeu de file.
- Formulaires : labels explicites, erreurs reliées par `aria-describedby`, OTP en champ unique acceptant le collage.
- i18n : aucune chaîne en dur, formats de dates/nombres localisés (fr-FR / en-KE…), libellés « EN / FR » (pas de drapeaux).
- Bas débit : images de preuves en miniatures compressées, chargement paresseux, l'app reste utilisable en 2G/3G intermittent.

## Offline & Synchronisation

Section produit-spécifique (FR-P19 → P22, P40 ; brique IndexedDB livrée).

1. **Cache de consultation** : dernières transactions consultées + leur détail disponibles hors-ligne, horodatées « données au… ».
2. **File d'attente locale** (IndexedDB) : versements de preuves (multipart), ouverture de litige (atomique preuve+motif). Chaque élément a un état visible : en file → envoi → confirmé / rejeté. *(Brouillons de messages retirés le 2026-07-24 — hors FR-P40 ; réintroduisibles par story dédiée.)*
3. **Rejeu idempotent** à la reconnexion, avec écran de récupération pour les rejets serveur (existant, conservé).
4. **Jamais d'optimisme financier** : aucun solde, financement ou retrait simulé hors-ligne ; l'UI affiche la dernière vérité serveur datée.
5. La PWA est installable ; le shell applicatif est précaché ; la bannière offline est le seul indicateur global (pas de toasts répétés).

## Inspiration & Anti-patterns

- **Repris d'EscrowLab** (maquettes 11, 13-20, 35-38) : dashboard à compteurs par état, bannière KYC bloquante et pédagogique, wizard de création en 2 étapes avec répartition des frais, ledger avec solde après opération, files d'approbation dépôts/retraits avec preuve à l'appui.
- **Rejetés (PRD)** : saisie carte en direct (maquette 14 — NFR-P22) ; impersonation « Login as User » et ±solde manuel (FR-P35) ; milestones dans le wizard/détail ; SSO social ; CMS/blog/simulateur public ; notifications de masse ; push navigateur ; form-builders KYC/retraits.
- **Repris du produit livré** : dossier de preuves contradictoire, upload offline IndexedDB, écran de récupération offline-reject, ingestion partenaire HMAC (sans UI — machine-à-machine).

## Key Flows

Wireflows des parcours PRD §5 — protagonistes nommés, notation : `Surface → action → Surface`.

### UJ-1 — Onboarding d'Amina (Nairobi, mardi matin, sur son téléphone)

1. **Landing** → « Créer un compte » → **Inscription** : email + mot de passe, cases de consentement légal (horodaté).
2. → **Vérification OTP** : code 6 chiffres reçu par email, renvoi possible après compte à rebours. Erreur : code invalide → message sous le champ, saisie conservée.
3. → **Création d'entreprise** : elle crée « Nairobi Wholesale Ltd » (ou rejoint sur invitation). → **Accueil** avec bannière KYB « non soumis ».
4. Bannière → **KYB entreprise** : formulaire fixe (identité légale, registre, représentant, bénéficiaires effectifs, justificatifs via la brique upload). Sauvegarde en brouillon possible ; soumission → statut « en revue, sous 2 jours ouvrés ».
5. Pendant la revue : elle explore l'app en lecture ; « Nouvelle transaction » visible mais désactivé (« Nécessite un KYB approuvé »). Elle active la **2FA TOTP** dans Profil (QR + codes de récupération).
6. **Climax :** notification (cloche + email) « Votre entreprise est vérifiée ». La bannière ambre disparaît, la carte wallet et « Nouvelle transaction » s'activent d'un coup — l'app entière change d'état sous ses yeux : elle est opérationnelle.

Échec : KYB rejeté → bannière rouge avec motif précis de Nadia + « Corriger et re-soumettre » (les pièces valides restent acquises).

### UJ-2 — Transaction heureuse (Thabo vend, Amina achète — fil rouge)

1. *Thabo, Johannesburg* : **Transactions** → « Nouvelle transaction » → **Wizard ①** : « Je vends », email d'Amina, marchandise, catégorie, 12 500 USD. → **Wizard ②** : conditions & délai de livraison, répartition des frais 50-50 — récapitulatif chiffré des frais des deux parties → « Envoyer l'invitation ».
2. **Détail (Thabo)** : état « Invitation en attente — expire le JJ/MM ».
3. *Amina* : notification → **Détail (Amina)** : elle examine conditions et frais → « Accepter » (confirmation) → l'écran demande le financement : solde 0 USD, insuffisant.
4. → **Dépôt de fonds** : elle choisit le PSP (mobile money) → **redirection parcours hébergé PSP** (aucune saisie carte chez nous) → retour → wallet crédité (ou : virement manuel avec preuve → « en rapprochement » → validation par Nadia, cf. UJ-3).
5. → **Détail** → « Financer — 12 531,25 USD (dont frais) » → modale récapitulative (montant, frais, solde après) → confirmation → **`FUNDS_LOCKED`**.
6. **Climax (le pivot de confiance du produit) :** chez Thabo, la timeline passe au bleu « Fonds sécurisés en votre faveur ». Il n'a plus aucun risque de non-paiement : il expédie. Il marque « Expédié » et **verse au dossier** le bordereau de transport et le **CoO ZLECAf** (métadonnées : n° certificat, autorité, date). Le transporteur partenaire pousse ses preuves (HMAC, sans UI) : elles apparaissent dans le dossier, attribuées « Partenaire transporteur ».
7. *Amina* reçoit la marchandise → **Détail** → « Confirmer la réception » (ou auto-libération à J+7 sans contestation, échéance affichée dans la timeline — FR-P12).
8. **Libération** : wallet de Thabo crédité (commission 50-50 déduite, visible dans le ledger avec solde après opération). → **Retrait** : coordonnées bancaires ZAR, frais et limites affichés → demande → approbation (Nadia) → versé en devise locale. Chacun télécharge le récapitulatif. Tout du long, ils se sont parlé dans la **messagerie** de la transaction.

Échecs : non-financement dans le délai → expiration (FR-P12) ; litige d'Amina → parcours UJ-litige livré (preuves à l'appui, Koffi tranche, dossier verrouillé) ; hors-ligne au moment de verser une preuve → mise en file locale, envoi à la reconnexion.

### UJ-3 — Nadia opère (back-office, desktop)

1. **Vue synthétique** : quatre files avec compteurs et badges SLA — KYB (3), dépôts manuels (1), retraits (2), tickets (0) ; les litiges affichés pour information renvoient à la console de Koffi (FR-P11).
2. → **File KYB** : dossier « Nairobi Wholesale Ltd » — pièces en revue côte à côte → « Approuver » (ou rejet ⇒ motif obligatoire, notifié). Passage automatique au dossier suivant.
3. → **Dépôts manuels** : déclaration d'Amina (référence, montant) face à sa preuve de virement → rapprochement avec le relevé du compte cantonné → « Approuver ».
4. **Climax :** l'approbation **crédite le wallet d'Amina en un geste audité** — Nadia voit la ligne s'écrire au grand livre (référence, horodatage) ; aucun champ de solde éditable n'existe nulle part : c'est l'écriture, pas la saisie, qui fait foi (FR-P35).
5. → **Retraits** : demande de Thabo — coordonnées contrôlées, montant réservé → « Approuver » (rejet ⇒ restitution motivée).
6. En continu : **Transactions** (filtres état/corridor, export CSV), **Utilisateurs & entreprises** (suspension motivée), **Configuration** (barème de frais versionné, clés HMAC affichées une seule fois).

### UJ-litige (rappel — livré, enrichi)

Amina ouvre un litige avec preuves (offline capable) → Thabo verse ses contre-preuves → la messagerie s'ouvre à **Koffi** → console : dossier contradictoire complet → décision motivée `RELEASE`/`REFUND` → wallet crédité en conséquence → dossier et messagerie verrouillés.

## Responsive & Platform

| Point de rupture | App client | Back-office / console |
|---|---|---|
| < 768 px | Barre d'onglets basse, une colonne, actions financières en bas d'écran | Non optimisé `[ASSUMPTION — lecture possible, opérations sur desktop]` |
| 768–1023 px | Colonne centrale 720 px, navigation latérale repliée | Tableaux scrollables |
| ≥ 1024 px | Barre latérale fixe, détail transaction en 2 colonnes (timeline+infos / preuves+messagerie) | Barre latérale + tableaux pleine largeur, panneau de détail latéral |

PWA installable (manifest, icônes, écran de démarrage) ; cible : navigateurs mobiles Android d'entrée de gamme en 3G — budget de poids strict sur les surfaces client.
