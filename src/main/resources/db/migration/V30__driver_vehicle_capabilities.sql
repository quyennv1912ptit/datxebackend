ALTER TABLE driver_verification_projection
    ADD COLUMN approved_capabilities TEXT NOT NULL DEFAULT '[]';

CREATE TABLE driver_capability_preferences (
    driver_id bigint PRIMARY KEY REFERENCES drivers(id) ON DELETE CASCADE,
    approval_revision bigint NOT NULL CHECK (approval_revision > 0),
    version bigint NOT NULL CHECK (version > 0),
    enabled_groups TEXT NOT NULL
);
