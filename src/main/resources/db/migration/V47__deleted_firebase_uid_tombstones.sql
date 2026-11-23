-- V47: Permanent tombstone for deleted driver Firebase UIDs (reconciled from origin/Merge A6/O6).
-- Preserves V43-V46 checksums untouched on integration/backend-release.
CREATE TABLE IF NOT EXISTS deleted_firebase_uids (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
