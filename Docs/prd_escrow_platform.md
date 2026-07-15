# Product Requirement Document (PRD) - Plateforme d'Escrow B2B pour le Commerce Intra-Africain (ZLECAf)

## 1. Vision du Projet & Contexte
L'accord sur la Zone de Libre-Échange Continentale Africaine (ZLECAf) crée le plus grand marché unique au monde en termes de nombre de pays participants. Cependant, le commerce intra-africain souffre d'un manque criant de confiance entre les acheteurs et les vendeurs transfrontaliers, de la fragmentation des moyens de paiement (Mobile Money, banques locales) et de défis logistiques complexes.

Ce projet vise à concevoir une plateforme B2B dotée d'un mécanisme de **séquestre (Escrow)** robuste. L'argent de l'acheteur est bloqué temporairement par la plateforme et n'est libéré au vendeur que lorsque la livraison est attestée et validée. Cela élimine le risque de fraude et stimule les échanges économiques sur le continent.

## 2. Objectifs Stratégiques
* **Instaurer la confiance :** Garantir le risque zéro pour l'acheteur (non-livraison) et le vendeur (non-paiement).
* **Interopérabilité inter-pays :** Connecter les acteurs économiques malgré la diversité des devises et des systèmes financiers.
* **Résilience technologique :** Fournir un service accessible même dans les zones à connectivité internet limitée ou instable (Edge/PWA).

## 3. Personas
* **Acheteur B2B (ex: Grossiste au Kenya) :** Souhaite commander des marchandises en Afrique du Sud. Il exige que ses fonds soient en sécurité tant que la marchandise n'a pas passé la douane ou n'est pas arrivée à ses entrepôts.
* **Vendeur B2B (ex: Producteur en Afrique du Sud) :** Souhaite expédier sa production mais exige la certitude que l'acheteur possède les fonds et qu'ils sont bloqués en sa faveur avant d'engager les frais logistiques.
* **Administrateur / Arbitre (Plateforme) :** Intervient en cas de litige pour analyser les preuves (bordereaux de livraison, rapports de douane) et trancher en faveur d'un remboursement ou d'une libération des fonds.

## 4. Fonctionnalités Clés & Spécifications Fonctionnelles

### 4.1. Gestion des Transactions et du Séquestre (Escrow Core)
* **Création de contrat :** L'acheteur et le vendeur s'accordent sur un montant, une devise, des conditions de livraison et un délai.
* **Blocage des fonds :** Intégration avec des passerelles de paiement locales. Le statut passe à `FUNDS_LOCKED`.
* **Suivi de l'expédition :** Le vendeur fournit les informations de transport. Le statut passe à `SHIPPED`.
* **Libération des fonds :** Validation par l'acheteur ou par preuve logistique automatique. Le statut passe à `RELEASED`.

### 4.2. Système de Gestion des Litiges (Dispute Management)
* **Ouverture de litige :** À tout moment après le blocage des fonds et avant la validation finale, l'une des parties peut geler le processus en ouvrant un litige (`DISPUTED`).
* **Dépôt de preuves :** Interface permettant d'uploader des documents justificatifs (photos, signatures de réception, documents douaniers).
* **Résolution d'arbitrage :** L'administrateur peut exécuter un `RESOLVE_DISPUTE_RELEASE` (paiement du vendeur) ou `RESOLVE_DISPUTE_REFUND` (remboursement de l'acheteur).

### 4.3. Module d'Intégration Tiers & Webhooks
* **Abonnement des partenaires :** Les transporteurs et agrégateurs de paiement peuvent configurer des URLs de callback (Webhooks) pour être notifiés automatiquement à chaque changement d'état.
* **Sécurisation :** Signature obligatoire de chaque payload avec une clé secrète partagée (HMAC-SHA256).

### 4.4. Interface Utilisateur Mobile-First (PWA)
* **Accessibilité offline :** Consultation des contrats et initiation des actions de validation même sans réseau. Synchronisation automatique dès reconnexion.
* **Optimisation data :** Payload minimaliste pour économiser la bande passante.

## 5. Exigences Non-Fonctionnelles
* **Sécurité & Conformité :** Chiffrement des données au repos et en transit. Piste d'audit immuable pour chaque changement d'état financier (logs d'audit).
* **Disponibilité :** Architecture résiliente capable de fonctionner de manière asynchrone pour absorber les pannes d'API tierces.
* **ACIDité :** Cohérence stricte des transactions en base de données pour éviter tout double débit ou perte de statut.