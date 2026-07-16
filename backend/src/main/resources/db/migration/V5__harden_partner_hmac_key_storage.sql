-- ============================================================================
-- Harden inbound partner HMAC key storage (Epic 3, Story 3.3).
-- Closes three storage-layer gaps left by V4 before Story 3.2 relies on these
-- keys: (1) a length guard rejecting weak secrets at admission (>= 32 bytes,
-- the project's partner-side length reference); (2) the nonce FK switched from
-- ON DELETE CASCADE to ON DELETE RESTRICT so a hard-delete of a key can no
-- longer silently wipe its anti-replay history. Revocation stays the soft-delete
-- (active = false). Additive migration — V4 is left untouched.
-- ============================================================================

ALTER TABLE partner_hmac_keys
    ADD CONSTRAINT ck_partner_hmac_keys_secret_len CHECK (octet_length(secret_key) >= 32);

ALTER TABLE partner_key_nonces
    DROP CONSTRAINT partner_key_nonces_key_id_fkey;

ALTER TABLE partner_key_nonces
    ADD CONSTRAINT fk_partner_key_nonces_key_id FOREIGN KEY (key_id)
        REFERENCES partner_hmac_keys (key_id) ON DELETE RESTRICT;
