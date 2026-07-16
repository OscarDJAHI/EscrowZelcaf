-- ============================================================================
-- Integrity constraints for evidence_files (architecture AD-12).
-- The V2 ERD only had NOT NULL + FKs; these CHECK/UNIQUE constraints enforce
-- the invariants documented in the entity javadoc but not previously held —
-- decided before Epic 2/3 start writing evidence rows (see deferred-work.md).
-- Safe on an empty table; the Story 1.2 deposit path already satisfies them.
-- ============================================================================

-- Enum domains (mirror UploaderType / EvidenceStatus).
ALTER TABLE evidence_files
    ADD CONSTRAINT ck_evidence_uploader_type
    CHECK (uploader_type IN ('BUYER', 'SELLER', 'ADMIN', 'CARRIER_PARTNER'));

ALTER TABLE evidence_files
    ADD CONSTRAINT ck_evidence_status
    CHECK (status IN ('ACTIVE', 'WITHDRAWN'));

-- Attribution coherence: a machine (CARRIER_PARTNER) deposit is attributed to a
-- company and never a user; a human deposit is attributed to a user and never a
-- company. Prevents unattributable evidence in the very table arbitration rests on.
ALTER TABLE evidence_files
    ADD CONSTRAINT ck_evidence_attribution
    CHECK (
        (uploader_type = 'CARRIER_PARTNER'
            AND partner_company_id IS NOT NULL
            AND uploaded_by_user_id IS NULL)
        OR
        (uploader_type IN ('BUYER', 'SELLER', 'ADMIN')
            AND uploaded_by_user_id IS NOT NULL
            AND partner_company_id IS NULL)
    );

-- Withdrawal coherence: an ACTIVE row carries no withdrawal trace; a WITHDRAWN
-- row always carries both when + by whom. No silent (traceless) withdrawal.
ALTER TABLE evidence_files
    ADD CONSTRAINT ck_evidence_withdrawal
    CHECK (
        (status = 'ACTIVE'
            AND withdrawn_at IS NULL
            AND withdrawn_by_user_id IS NULL)
        OR
        (status = 'WITHDRAWN'
            AND withdrawn_at IS NOT NULL
            AND withdrawn_by_user_id IS NOT NULL)
    );

-- Metadata ↔ object is 1:1: an opaque storage key backs exactly one row.
ALTER TABLE evidence_files
    ADD CONSTRAINT uq_evidence_storage_key UNIQUE (storage_key);
