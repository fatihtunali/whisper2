-- Step 4: Attachments Tables
-- Following WHISPER-REBUILD.md Section 4

-- =============================================================================
-- ATTACHMENTS TABLE
-- Tracks encrypted attachment blobs stored in Spaces
-- =============================================================================
CREATE TABLE attachments (
    object_key TEXT PRIMARY KEY,
    owner_whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    size_bytes BIGINT NOT NULL,
    content_type TEXT NOT NULL,
    created_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,
    deleted_at_ms BIGINT NULL,
    status TEXT NOT NULL DEFAULT 'active',

    CONSTRAINT attachments_status_valid CHECK (status IN ('active', 'deleted', 'expired')),
    CONSTRAINT attachments_size_valid CHECK (size_bytes > 0 AND size_bytes <= 104857600)
);

CREATE INDEX idx_attachments_owner_created ON attachments(owner_whisper_id, created_at_ms);
CREATE INDEX idx_attachments_expires_status ON attachments(expires_at_ms, status);

-- =============================================================================
-- ATTACHMENT_ACCESS TABLE
-- Grants download access to recipients when they receive a message with attachment
-- =============================================================================
CREATE TABLE attachment_access (
    object_key TEXT NOT NULL REFERENCES attachments(object_key) ON DELETE CASCADE,
    whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id) ON DELETE CASCADE,
    granted_at_ms BIGINT NOT NULL,
    expires_at_ms BIGINT NOT NULL,

    PRIMARY KEY (object_key, whisper_id)
);

CREATE INDEX idx_attachment_access_expiry ON attachment_access(expires_at_ms);
CREATE INDEX idx_attachment_access_whisper ON attachment_access(whisper_id);
