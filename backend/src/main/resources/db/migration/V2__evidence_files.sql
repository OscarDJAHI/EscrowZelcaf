-- ============================================================================
-- Evidence files — metadata for binaries held in object storage (MinIO).
-- The binary itself never lands in Postgres: storage_key is the opaque handle.
-- Retention is unlimited: no purge, no TTL, no lifecycle rule.
-- ============================================================================

CREATE TABLE evidence_files (
    id                   BIGSERIAL PRIMARY KEY,
    transaction_id       BIGINT       NOT NULL REFERENCES escrow_transactions (id),
    uploaded_by_user_id  BIGINT REFERENCES users (id),
    uploader_type        VARCHAR(20)  NOT NULL,
    partner_company_id   BIGINT REFERENCES companies (id),
    original_filename    VARCHAR(255),
    mime_type            VARCHAR(100),
    size_bytes           BIGINT,
    storage_key          VARCHAR(500) NOT NULL,
    comment              TEXT,
    status               VARCHAR(20)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    withdrawn_at         TIMESTAMPTZ,
    withdrawn_by_user_id BIGINT REFERENCES users (id)
);

CREATE INDEX idx_evidence_transaction ON evidence_files (transaction_id, created_at);
