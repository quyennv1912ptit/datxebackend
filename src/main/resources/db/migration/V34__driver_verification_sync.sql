CREATE TABLE verification_sync_checkpoint (
    singleton boolean PRIMARY KEY CHECK (singleton),
    cursor_seq bigint NOT NULL DEFAULT 0,
    stream_id uuid,
    caught_up_at timestamptz
);
INSERT INTO verification_sync_checkpoint(singleton) VALUES (true);

CREATE TABLE driver_verification_projection (
    owner_uid varchar(128) PRIMARY KEY,
    application_id uuid NOT NULL,
    revision bigint NOT NULL,
    status varchar(32) NOT NULL,
    entitlements text NOT NULL
);
