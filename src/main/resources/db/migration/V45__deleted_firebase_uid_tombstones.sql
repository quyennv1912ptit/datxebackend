-- A deleted account's Firebase UID must never come back: a stale upstream verification
-- decision or a re-registration with a still-valid token must not inherit the old
-- verification state. The original UID is recorded permanently when the local account
-- is anonymized (the driver/customer row itself is rewritten to a synthetic UID).
CREATE TABLE IF NOT EXISTS deleted_firebase_uids (
    firebase_uid VARCHAR(128) PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
