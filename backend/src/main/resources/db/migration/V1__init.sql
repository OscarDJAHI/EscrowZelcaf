-- ============================================================================
-- Escrow platform — initial relational schema (PostgreSQL)
-- Source of truth for the database structure; owned by Flyway.
-- ============================================================================

CREATE TABLE companies (
    id                  BIGSERIAL PRIMARY KEY,
    name                VARCHAR(150) NOT NULL,
    registration_number VARCHAR(100) UNIQUE,
    country             VARCHAR(3),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    role          VARCHAR(50)  NOT NULL,
    company_id    BIGINT REFERENCES companies (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE escrow_transactions (
    id          BIGSERIAL PRIMARY KEY,
    buyer_id    BIGINT         NOT NULL REFERENCES users (id),
    seller_id   BIGINT         NOT NULL REFERENCES users (id),
    amount      NUMERIC(15, 2) NOT NULL,
    currency    VARCHAR(3)     NOT NULL,
    state       VARCHAR(50)    NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version     BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX idx_escrow_buyer  ON escrow_transactions (buyer_id);
CREATE INDEX idx_escrow_seller ON escrow_transactions (seller_id);
CREATE INDEX idx_escrow_state  ON escrow_transactions (state);

CREATE TABLE webhook_subscriptions (
    id         BIGSERIAL PRIMARY KEY,
    company_id BIGINT REFERENCES companies (id),
    target_url VARCHAR(255) NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(50)  NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Immutable audit trail. Application code only ever INSERTs here.
CREATE TABLE audit_logs (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT REFERENCES escrow_transactions (id),
    action_by      BIGINT REFERENCES users (id),
    previous_state VARCHAR(50),
    next_state     VARCHAR(50),
    payload        JSONB,
    timestamp      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_transaction ON audit_logs (transaction_id);
