-- Whisper2 Initial Database Schema
-- Following WHISPER-REBUILD.md Section 12.2

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =============================================================================
-- USERS TABLE
-- Stores identity records: Whisper ID and public keys
-- Keys are immutable for an identity in this rebuild (no key rotation)
-- =============================================================================
CREATE TABLE users (
    whisper_id VARCHAR(19) PRIMARY KEY,  -- Format: WSP-XXXX-XXXX-XXXX
    enc_public_key VARCHAR(64) NOT NULL,  -- X25519 public key, base64
    sign_public_key VARCHAR(64) NOT NULL, -- Ed25519 public key, base64
    status VARCHAR(20) NOT NULL DEFAULT 'active', -- active, banned
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT users_whisper_id_format CHECK (whisper_id ~ '^WSP-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}$'),
    CONSTRAINT users_status_valid CHECK (status IN ('active', 'banned'))
);

CREATE INDEX idx_users_status ON users(status);
CREATE INDEX idx_users_created_at ON users(created_at);

-- =============================================================================
-- DEVICES TABLE
-- Each install generates deviceId = UUIDv4 once
-- All sessions bind to {whisperId, deviceId}
-- Single-active-device: new login invalidates old sessions
-- =============================================================================
CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id) ON DELETE CASCADE,
    device_id UUID NOT NULL,
    platform VARCHAR(10) NOT NULL, -- ios, android
    push_token TEXT,
    voip_token TEXT, -- iOS only, for CallKit
    last_seen_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT devices_platform_valid CHECK (platform IN ('ios', 'android')),
    CONSTRAINT devices_whisper_device_unique UNIQUE (whisper_id, device_id)
);

CREATE INDEX idx_devices_whisper_id ON devices(whisper_id);
CREATE INDEX idx_devices_device_id ON devices(device_id);

-- =============================================================================
-- GROUPS TABLE
-- Groups use pairwise fanout only (no group keys)
-- =============================================================================
CREATE TABLE groups (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title VARCHAR(255) NOT NULL,
    created_by VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_groups_created_by ON groups(created_by);

-- =============================================================================
-- GROUP_MEMBERS TABLE
-- Membership tracking for routing validation
-- =============================================================================
CREATE TABLE group_members (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL DEFAULT 'member', -- admin, member
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT group_members_role_valid CHECK (role IN ('admin', 'member')),
    CONSTRAINT group_members_unique UNIQUE (group_id, whisper_id)
);

CREATE INDEX idx_group_members_group_id ON group_members(group_id);
CREATE INDEX idx_group_members_whisper_id ON group_members(whisper_id);

-- =============================================================================
-- BANS TABLE
-- User bans for abuse handling
-- =============================================================================
CREATE TABLE bans (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    reason TEXT NOT NULL,
    banned_by VARCHAR(100) NOT NULL, -- admin identifier
    banned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ, -- NULL = permanent
    unbanned_at TIMESTAMPTZ,
    unbanned_by VARCHAR(100),

    CONSTRAINT bans_whisper_id_active UNIQUE (whisper_id) -- only one active ban per user
);

CREATE INDEX idx_bans_whisper_id ON bans(whisper_id);
CREATE INDEX idx_bans_expires_at ON bans(expires_at) WHERE expires_at IS NOT NULL;

-- =============================================================================
-- REPORTS TABLE
-- Abuse reports from users
-- =============================================================================
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    reporter_whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    reported_whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    reason VARCHAR(50) NOT NULL, -- spam, harassment, illegal_content, other
    details TEXT,
    message_id UUID, -- optional: the message being reported
    status VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending, reviewed, actioned, dismissed
    reviewed_by VARCHAR(100),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT reports_reason_valid CHECK (reason IN ('spam', 'harassment', 'illegal_content', 'other')),
    CONSTRAINT reports_status_valid CHECK (status IN ('pending', 'reviewed', 'actioned', 'dismissed'))
);

CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_reported_whisper_id ON reports(reported_whisper_id);
CREATE INDEX idx_reports_created_at ON reports(created_at);

-- =============================================================================
-- AUDIT_EVENTS TABLE
-- Structured logging for security-relevant events
-- =============================================================================
CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(50) NOT NULL, -- register, login, logout, ban, unban, report, etc.
    whisper_id VARCHAR(19), -- can be NULL for system events
    device_id UUID,
    ip_address INET,
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_events_event_type ON audit_events(event_type);
CREATE INDEX idx_audit_events_whisper_id ON audit_events(whisper_id);
CREATE INDEX idx_audit_events_created_at ON audit_events(created_at);

-- =============================================================================
-- ATTACHMENTS TABLE
-- Metadata for attachment GC (garbage collection)
-- Delete when all recipients ACK or TTL expires (30 days)
-- =============================================================================
CREATE TABLE attachments (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    object_key VARCHAR(255) NOT NULL UNIQUE, -- S3 path: att/2026/01/uuid.bin
    uploader_whisper_id VARCHAR(19) NOT NULL REFERENCES users(whisper_id),
    content_type VARCHAR(100) NOT NULL,
    ciphertext_size BIGINT NOT NULL,
    message_id UUID, -- the message this attachment belongs to
    recipient_whisper_ids TEXT[] NOT NULL, -- array of intended recipients
    delivered_to TEXT[] DEFAULT '{}', -- recipients who ACKed
    expires_at TIMESTAMPTZ NOT NULL, -- TTL for GC
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attachments_object_key ON attachments(object_key);
CREATE INDEX idx_attachments_message_id ON attachments(message_id);
CREATE INDEX idx_attachments_expires_at ON attachments(expires_at);
CREATE INDEX idx_attachments_uploader ON attachments(uploader_whisper_id);

-- =============================================================================
-- CONTACT_BACKUPS TABLE
-- Zero-knowledge encrypted contact list backup
-- Server stores single encrypted blob per Whisper ID
-- Section 14.1-14.4 of spec
-- =============================================================================
CREATE TABLE contact_backups (
    whisper_id VARCHAR(19) PRIMARY KEY REFERENCES users(whisper_id) ON DELETE CASCADE,
    nonce_b64 VARCHAR(44) NOT NULL, -- base64(24 bytes) = 32 chars, but allow padding
    ciphertext_b64 TEXT NOT NULL, -- secretbox encrypted blob
    size_bytes INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- HELPER FUNCTIONS
-- =============================================================================

-- Function to update updated_at timestamp
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Triggers for updated_at
CREATE TRIGGER update_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_devices_updated_at
    BEFORE UPDATE ON devices
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_groups_updated_at
    BEFORE UPDATE ON groups
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_contact_backups_updated_at
    BEFORE UPDATE ON contact_backups
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();
