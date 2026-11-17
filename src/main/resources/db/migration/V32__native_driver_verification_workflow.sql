CREATE TABLE driver_verification_applications (
    id uuid PRIMARY KEY,
    owner_uid varchar(128) NOT NULL UNIQUE,
    status varchar(32) NOT NULL,
    revision bigint NOT NULL DEFAULT 0,
    confirmed_fields text,
    confirmed_at timestamptz,
    confirmed_revision bigint,
    confirmation_current boolean NOT NULL DEFAULT false,
    warning_ack text,
    submission_revision bigint,
    submitted_at timestamptz,
    resubmission_count int NOT NULL DEFAULT 0,
    decision_reason text,
    requested_changes text,
    face_consent text,
    face_status text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_driver_verification_applications_status_updated
    ON driver_verification_applications(status, updated_at DESC);

CREATE TABLE driver_verification_captures (
    id uuid PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES driver_verification_applications(id) ON DELETE CASCADE,
    slot varchar(32) NOT NULL,
    storage_key varchar(255) NOT NULL,
    mime_type varchar(64) NOT NULL,
    byte_size int NOT NULL,
    sha256 varchar(64) NOT NULL,
    quality_json text NOT NULL,
    proposal_json text,
    captured_revision bigint NOT NULL,
    retired_at timestamptz,
    first_submitted_revision bigint,
    uploaded_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_driver_verification_active_capture
    ON driver_verification_captures(application_id, slot)
    WHERE retired_at IS NULL;

CREATE INDEX idx_driver_verification_captures_app_slot
    ON driver_verification_captures(application_id, slot, captured_revision DESC);

CREATE TABLE driver_verification_submissions (
    application_id uuid NOT NULL REFERENCES driver_verification_applications(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    submitted_at timestamptz NOT NULL,
    snapshot_json text NOT NULL,
    PRIMARY KEY (application_id, revision)
);

CREATE TABLE driver_verification_submit_keys (
    application_id uuid NOT NULL REFERENCES driver_verification_applications(id) ON DELETE CASCADE,
    idempotency_key varchar(128) NOT NULL,
    expected_revision bigint NOT NULL,
    request_fingerprint varchar(128) NOT NULL,
    response_json text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (application_id, idempotency_key)
);

CREATE TABLE driver_verification_audit (
    id bigserial PRIMARY KEY,
    application_id uuid NOT NULL REFERENCES driver_verification_applications(id) ON DELETE CASCADE,
    revision bigint NOT NULL,
    actor_uid varchar(128) NOT NULL,
    actor_role varchar(32) NOT NULL,
    action varchar(64) NOT NULL,
    metadata_json text NOT NULL DEFAULT '{}',
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_driver_verification_audit_app_rev
    ON driver_verification_audit(application_id, revision DESC, id DESC);

CREATE TABLE driver_verification_upload_attempts (
    id bigserial PRIMARY KEY,
    owner_uid varchar(128) NOT NULL,
    attempted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_driver_verification_upload_attempts_owner_time
    ON driver_verification_upload_attempts(owner_uid, attempted_at);

-- Backfill existing legacy-APPROVED drivers into the unified projection & capability preferences
-- so PostgreSQL acts as a single source of truth immediately after migration.
WITH legacy_approved AS (
    SELECT
        d.id AS driver_id,
        d.firebase_uid AS owner_uid,
        gen_random_uuid() AS application_id,
        UPPER(COALESCE(NULLIF(TRIM(d.license_class), ''), 'A1')) AS license_class,
        d.license_expiry_date,
        CASE
            WHEN d.license_expiry_date IS NULL
                 AND UPPER(COALESCE(NULLIF(TRIM(d.license_class), ''), 'A1')) IN ('A1', 'A2', 'A3', 'A', 'B1')
                THEN 'NO_EXPIRY'
            WHEN d.license_expiry_date IS NOT NULL
                THEN 'HAS_EXPIRY'
            ELSE 'NO_EXPIRY'
        END AS validity_kind
    FROM drivers d
    WHERE d.verification_status = 'APPROVED'
      AND d.deleted_at IS NULL
      AND d.firebase_uid IS NOT NULL
      AND TRIM(d.firebase_uid) <> ''
      AND NOT EXISTS (
          SELECT 1 FROM driver_verification_projection p WHERE p.owner_uid = d.firebase_uid
      )
),
entitlement_rows AS (
    SELECT
        la.*,
        json_build_array(
            json_build_object(
                'licenseClass', la.license_class,
                'kind', la.validity_kind,
                'expiresOn', CASE WHEN la.validity_kind = 'HAS_EXPIRY' THEN to_char(la.license_expiry_date, 'YYYY-MM-DD') ELSE NULL END
            )
        )::text AS entitlements_json,
        COALESCE((
            SELECT json_agg(DISTINCT g.group_id)::text
            FROM (
                SELECT 'moto-50-125' AS group_id
                WHERE la.license_class IN ('A1', 'A2', 'A', 'B1')
                   OR EXISTS (SELECT 1 FROM driver_vehicle_skills s WHERE s.driver_id = la.driver_id AND s.verified AND s.vehicle_type = 'MOTORCYCLE')
                UNION ALL
                SELECT 'moto-electric-4-11' AS group_id
                WHERE la.license_class IN ('A1', 'A2', 'A', 'B1')
                   OR EXISTS (SELECT 1 FROM driver_vehicle_skills s WHERE s.driver_id = la.driver_id AND s.verified AND s.vehicle_type = 'MOTORCYCLE')
                UNION ALL
                SELECT 'moto-126-174' AS group_id
                WHERE la.license_class IN ('A2', 'A')
                UNION ALL
                SELECT 'moto-175-plus' AS group_id
                WHERE la.license_class IN ('A2', 'A')
                UNION ALL
                SELECT 'moto-electric-over-11-under-14' AS group_id
                WHERE la.license_class IN ('A2', 'A')
                UNION ALL
                SELECT 'moto-electric-14-plus' AS group_id
                WHERE la.license_class IN ('A2', 'A')
                UNION ALL
                SELECT 'car-auto' AS group_id
                WHERE la.license_class IN ('B', 'B1', 'B2', 'C1', 'C', 'D1', 'D2', 'D', 'E')
                   OR EXISTS (SELECT 1 FROM driver_vehicle_skills s WHERE s.driver_id = la.driver_id AND s.verified AND s.vehicle_type = 'CAR')
                UNION ALL
                SELECT 'car-manual' AS group_id
                WHERE la.license_class IN ('B', 'B2', 'C1', 'C', 'D1', 'D2', 'D', 'E')
            ) g
        ), '[]') AS group_ids_json
    FROM legacy_approved la
)
INSERT INTO driver_verification_projection
    (owner_uid, application_id, revision, status, entitlements, cccd_expires_on, approved_capabilities)
SELECT
    er.owner_uid,
    er.application_id,
    2,
    'APPROVED',
    er.entitlements_json,
    NULL,
    COALESCE((
        SELECT json_agg(
            json_build_object(
                'groupId', grp,
                'applicationId', er.application_id::text,
                'submissionRevision', 1,
                'approvalRevision', 2,
                'policyVersion', 'VN_36_2024_108_2026_MVP_1',
                'entitlements', er.entitlements_json::json
            )
        )::text
        FROM json_array_elements_text(er.group_ids_json::json) AS grp
    ), '[]')
FROM entitlement_rows er;

INSERT INTO driver_capability_preferences (driver_id, approval_revision, version, enabled_groups)
SELECT
    d.id,
    p.revision,
    1,
    COALESCE((
        SELECT json_agg(cap->>'groupId')::text
        FROM json_array_elements(p.approved_capabilities::json) AS cap
    ), '[]')
FROM driver_verification_projection p
JOIN drivers d ON d.firebase_uid = p.owner_uid
WHERE p.status = 'APPROVED'
  AND NOT EXISTS (
      SELECT 1 FROM driver_capability_preferences prefs WHERE prefs.driver_id = d.id
  );

UPDATE verification_sync_checkpoint SET caught_up_at = clock_timestamp() WHERE singleton;
