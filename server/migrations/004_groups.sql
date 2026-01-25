-- Step 5: Groups Tables
-- Following WHISPER-REBUILD.md Section 5

-- =============================================================================
-- GROUPS TABLE
-- Stores group metadata
-- =============================================================================
CREATE TABLE groups (
    group_id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    owner_whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    created_at_ms BIGINT NOT NULL,
    updated_at_ms BIGINT NOT NULL,

    CONSTRAINT groups_title_length CHECK (char_length(title) >= 1 AND char_length(title) <= 64)
);

CREATE INDEX idx_groups_owner ON groups(owner_whisper_id);

-- =============================================================================
-- GROUP_MEMBERS TABLE
-- Tracks group membership with roles
-- =============================================================================
CREATE TABLE group_members (
    group_id TEXT NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    joined_at_ms BIGINT NOT NULL,
    removed_at_ms BIGINT NULL,

    PRIMARY KEY (group_id, whisper_id),
    CONSTRAINT group_members_role_valid CHECK (role IN ('owner', 'admin', 'member'))
);

CREATE INDEX idx_group_members_whisper ON group_members(whisper_id);
CREATE INDEX idx_group_members_group ON group_members(group_id);
CREATE INDEX idx_group_members_active ON group_members(group_id) WHERE removed_at_ms IS NULL;
