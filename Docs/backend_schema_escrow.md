# Architecture Données & Schéma Backend

Ce document détaille la modélisation des données dans PostgreSQL ainsi que la structure logique de la machine à états gérant le séquestre financier.

## 1. Modèle de Données (Schéma Relationnel SQL)

### Table : `users`
Contient les informations des utilisateurs de la plateforme (Acheteurs, Vendeurs, Arbitres).
* `id` (BIGSERIAL, Primary Key)
* `email` (VARCHAR(150), Unique, Not Null)
* `password_hash` (VARCHAR(255), Not Null)
* `first_name` (VARCHAR(100))
* `last_name` (VARCHAR(100))
* `role` (VARCHAR(50)) -- BUYER, SELLER, ADMIN
* `created_at` (TIMESTAMP, Not Null)

### Table : `companies`
Représente l'entité légale de l'entreprise B2B dans le cadre de la ZLECAf.
* `id` (BIGSERIAL, Primary Key)
* `name` (VARCHAR(150), Not Null)
* `registration_number` (VARCHAR(100), Unique) -- Numéro de registre du commerce national
* `country` (VARCHAR(3)) -- Code ISO-3166alpha3 (ex: KEN, ZAF, NGA)
* `created_at` (TIMESTAMP)

### Table : `escrow_transactions`
La table maîtresse stockant les états financiers et le cycle de vie du séquestre.
* `id` (BIGSERIAL, Primary Key)
* `buyer_id` (BIGINT, Foreign Key to users)
* `seller_id` (BIGINT, Foreign Key to users)
* `amount` (NUMERIC(15, 2), Not Null) -- Support de haute précision pour la finance
* `currency` (VARCHAR(3), Not Null) -- USD, EUR, KES, ZAR, etc.
* `state` (VARCHAR(50), Not Null) -- INITIATED, FUNDS_LOCKED, SHIPPED, RELEASED, DISPUTED, REFUNDED
* `description` (TEXT)
* `created_at` (TIMESTAMP)
* `updated_at` (TIMESTAMP)

### Table : `webhook_subscriptions`
Permet aux partenaires tiers de s'enregistrer pour recevoir des alertes automatiques.
* `id` (BIGSERIAL, Primary Key)
* `company_id` (BIGINT, Foreign Key to companies)
* `target_url` (VARCHAR(255), Not Null)
* `secret_key` (VARCHAR(255), Not Null) -- Utilisée pour signer le HMAC-SHA256
* `event_type` (VARCHAR(50), Not Null) -- ex: FUNDS_LOCKED, SHIPPED, ALL
* `is_active` (BOOLEAN, Default True)

### Table : `audit_logs`
Piste d'audit obligatoire pour la conformité et la sécurité.
* `id` (BIGSERIAL, Primary Key)
* `transaction_id` (BIGINT, Foreign Key to escrow_transactions)
* `action_by` (BIGINT, Foreign Key to users)
* `previous_state` (VARCHAR(50))
* `next_state` (VARCHAR(50))
* `payload` (JSONB) -- Historique complet de la requête technique
* `timestamp` (TIMESTAMP, Not Null)

## 2. Matrice des Transitions de la Machine à États

| État Initial | Événement (Trigger) | État Cible | Rôle Autorisé | Action Métier Associée |
| :--- | :--- | :--- | :--- | :--- |
| `NONE` | `CREATE` | `INITIATED` | Acheteur / Vendeur | Création initiale du contrat d'accord commercial. |
| `INITIATED` | `PAY_FUNDS` | `FUNDS_LOCKED` | Système / Passerelle | L'acheteur a payé, les fonds sont bloqués en séquestre. |
| `FUNDS_LOCKED` | `SHIP_GOODS` | `SHIPPED` | Vendeur | Le vendeur déclare l'envoi et fournit les preuves logistiques. |
| `FUNDS_LOCKED` | `OPEN_DISPUTE` | `DISPUTED` | Acheteur / Vendeur | Blocage de sécurité en cas d'anomalie détectée en amont. |
| `SHIPPED` | `DELIVERY_CONFIRMED` | `RELEASED` | Acheteur / Système | Validation finale. Les fonds sont transférés au vendeur. |
| `SHIPPED` | `OPEN_DISPUTE` | `DISPUTED` | Acheteur | L'acheteur déclare n'avoir rien reçu ou produit non conforme. |
| `DISPUTED` | `RESOLVE_RELEASE` | `RELEASED` | Administrateur | Arbitrage validant que le vendeur doit être payé. |
| `DISPUTED` | `RESOLVE_REFUND` | `REFUNDED` | Administrateur | Arbitrage validant que l'acheteur doit être remboursé. |

## 3. Spécifications des APIs REST Principales (Endpoints du POC)

* `POST /api/v1/escrow` : Initialise une transaction financière.
* `POST /api/v1/escrow/{id}/event` : Envoie un événement à la machine à états (ex: payload `{"event": "PAY_FUNDS"}`).
* `GET /api/v1/escrow/{id}` : Récupère l'état actuel et l'historique d'une transaction.
* `POST /api/v1/webhooks/subscriptions` : Permet à un partenaire de configurer son URL de callback.