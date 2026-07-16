-- ============================================================================
-- Inbound partner HMAC keys + nonce store (Epic 3, Story 3.1).
-- Machine-to-machine partner deposits are authenticated by HMAC-SHA256 and
-- protected against replay. These keys are the INBOUND credentials, resolved by
-- key-id to a company — strictly separate from the OUTBOUND webhook secret
-- (webhook_subscriptions.secret_key), which this feature never reads or writes.
-- Nonce uniqueness is scoped per key-id: the same nonce may recur under two
-- distinct key-ids but never twice under one. No scheduled purge here; the
-- bounded deleteBySeenAtBefore repository method is the only retention tool.
-- ============================================================================

CREATE TABLE partner_hmac_keys (
    id         BIGSERIAL PRIMARY KEY,
    key_id     VARCHAR(100) NOT NULL UNIQUE,
    company_id BIGINT       NOT NULL REFERENCES companies (id),
    secret_key VARCHAR(255) NOT NULL,
    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_partner_hmac_keys_company_id ON partner_hmac_keys (company_id);

CREATE TABLE partner_key_nonces (
    id      BIGSERIAL PRIMARY KEY,
    key_id  VARCHAR(100) NOT NULL REFERENCES partner_hmac_keys (key_id) ON DELETE CASCADE,
    nonce   VARCHAR(255) NOT NULL,
    seen_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_partner_key_nonces_key_nonce UNIQUE (key_id, nonce)
);

CREATE INDEX idx_partner_key_nonces_seen_at ON partner_key_nonces (seen_at);
