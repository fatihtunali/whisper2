-- Add index on contact_backups.updated_at for ops queries
CREATE INDEX IF NOT EXISTS idx_contact_backups_updated_at ON contact_backups(updated_at);
