ALTER TABLE driver_verification_applications
    ADD COLUMN approved_revision bigint,
    ADD COLUMN approved_submission_revision bigint,
    ADD COLUMN locked_cccd_id varchar(12),
    ADD COLUMN locked_full_name varchar(150),
    ADD COLUMN locked_dob date,
    ADD COLUMN verified_gplx_number varchar(30),
    ADD COLUMN identity_hold boolean NOT NULL DEFAULT false,
    ADD COLUMN identity_hold_reason text;

CREATE INDEX idx_driver_verification_apps_locked_cccd
    ON driver_verification_applications(locked_cccd_id)
    WHERE locked_cccd_id IS NOT NULL;

CREATE INDEX idx_driver_verification_apps_verified_gplx
    ON driver_verification_applications(verified_gplx_number)
    WHERE verified_gplx_number IS NOT NULL;

-- Populate identity lock columns for any existing APPROVED applications.
UPDATE driver_verification_applications a
SET approved_revision = COALESCE(a.approved_revision, a.revision),
    approved_submission_revision = COALESCE(a.approved_submission_revision, a.submission_revision),
    locked_cccd_id = COALESCE(
        a.locked_cccd_id,
        CASE
            WHEN NULLIF(TRIM(a.confirmed_fields), '') IS NOT NULL
                 AND (a.confirmed_fields::jsonb ->> 'cccd_id') ~ '^[0-9]{12}$'
                THEN a.confirmed_fields::jsonb ->> 'cccd_id'
            ELSE NULL
        END
    ),
    locked_full_name = COALESCE(
        a.locked_full_name,
        CASE
            WHEN NULLIF(TRIM(a.confirmed_fields), '') IS NOT NULL
                THEN NULLIF(TRIM(a.confirmed_fields::jsonb ->> 'cccd_name'), '')
            ELSE NULL
        END
    ),
    locked_dob = COALESCE(
        a.locked_dob,
        CASE
            WHEN NULLIF(TRIM(a.confirmed_fields), '') IS NOT NULL
                 AND (a.confirmed_fields::jsonb ->> 'cccd_dob') ~ '^\d{4}-\d{2}-\d{2}$'
                THEN (a.confirmed_fields::jsonb ->> 'cccd_dob')::date
            ELSE NULL
        END
    ),
    verified_gplx_number = COALESCE(
        a.verified_gplx_number,
        CASE
            WHEN NULLIF(TRIM(a.confirmed_fields), '') IS NOT NULL
                THEN NULLIF(TRIM(a.confirmed_fields::jsonb ->> 'gplx_number'), '')
            ELSE NULL
        END
    )
WHERE a.status = 'APPROVED';

-- Backfill native workflow application + submission snapshot for legacy-approved projections
-- so verified drivers see their read-only verified identity state and can initiate non-destructive re-verification.
WITH legacy_backfill AS (
    SELECT
        p.application_id,
        p.owner_uid,
        p.revision AS approval_revision,
        p.cccd_expires_on,
        NULLIF(TRIM(d.full_name), '') AS full_name,
        (
            SELECT NULLIF(TRIM(doc.document_number), '')
            FROM driver_documents doc
            WHERE doc.driver_id = d.id AND doc.is_current AND doc.document_type = 'CCCD'
            LIMIT 1
        ) AS cccd_number,
        COALESCE(
            NULLIF(TRIM((
                SELECT doc.document_number
                FROM driver_documents doc
                WHERE doc.driver_id = d.id AND doc.is_current AND doc.document_type = 'DRIVER_LICENSE'
                LIMIT 1
            )), ''),
            NULLIF(TRIM(d.license_number), '')
        ) AS gplx_number,
        UPPER(COALESCE(NULLIF(TRIM(d.license_class), ''), 'A1')) AS license_class,
        d.license_expiry_date
    FROM driver_verification_projection p
    JOIN drivers d ON d.firebase_uid = p.owner_uid
    WHERE p.status = 'APPROVED'
      AND d.deleted_at IS NULL
      AND NOT EXISTS (
          SELECT 1 FROM driver_verification_applications a
          WHERE a.owner_uid = p.owner_uid OR a.id = p.application_id
      )
),
prepared AS (
    SELECT
        lb.*,
        CASE WHEN lb.cccd_number ~ '^[0-9]{12}$' THEN lb.cccd_number ELSE NULL END AS valid_cccd_id,
        (
            jsonb_strip_nulls(
                jsonb_build_object(
                    'cccd_name', lb.full_name,
                    'cccd_id', CASE WHEN lb.cccd_number ~ '^[0-9]{12}$' THEN lb.cccd_number ELSE NULL END,
                    'cccd_expiry_date', CASE WHEN lb.cccd_expires_on IS NOT NULL THEN to_char(lb.cccd_expires_on, 'YYYY-MM-DD') ELSE NULL END,
                    'gplx_number', lb.gplx_number,
                    'gplx_name', lb.full_name,
                    'gplx_classes', jsonb_build_array(lb.license_class),
                    'no_expiry', (lb.license_expiry_date IS NULL)
                )
            ) || CASE
                WHEN lb.license_expiry_date IS NOT NULL
                    THEN jsonb_build_object('expiry_date', to_char(lb.license_expiry_date, 'YYYY-MM-DD'))
                ELSE jsonb_build_object('expiry_date', NULL)
            END
        ) AS confirmed_json
    FROM legacy_backfill lb
),
inserted_apps AS (
    INSERT INTO driver_verification_applications (
        id, owner_uid, status, revision,
        confirmed_fields, confirmed_at, confirmed_revision, confirmation_current,
        submission_revision, submitted_at, resubmission_count,
        approved_revision, approved_submission_revision,
        locked_cccd_id, locked_full_name, locked_dob, verified_gplx_number,
        identity_hold, created_at, updated_at
    )
    SELECT
        pr.application_id,
        pr.owner_uid,
        'APPROVED',
        pr.approval_revision,
        pr.confirmed_json::text,
        CURRENT_TIMESTAMP,
        1,
        true,
        1,
        CURRENT_TIMESTAMP,
        0,
        pr.approval_revision,
        1,
        pr.valid_cccd_id,
        pr.full_name,
        NULL,
        pr.gplx_number,
        false,
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    FROM prepared pr
    RETURNING id, owner_uid, revision
)
INSERT INTO driver_verification_submissions (application_id, revision, submitted_at, snapshot_json)
SELECT
    pr.application_id,
    1,
    CURRENT_TIMESTAMP,
    jsonb_build_object(
        'captures', '{}'::jsonb,
        'proposals', '{}'::jsonb,
        'confirmed', pr.confirmed_json,
        'confirmationCurrent', true,
        'revision', 1,
        'submittedAt', to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
        'legacyBackfill', true,
        'warningCodes', '[]'::jsonb,
        'warningCount', 0,
        'verificationWarnings', '[]'::jsonb
    )::text
FROM prepared pr
JOIN inserted_apps ia ON ia.id = pr.application_id;

INSERT INTO driver_verification_audit (application_id, revision, actor_uid, actor_role, action, metadata_json, created_at)
SELECT
    a.id,
    a.revision,
    'system',
    'admin',
    'LEGACY_BACKFILL',
    jsonb_build_object('reason', 'Đồng bộ hồ sơ đã duyệt trước Phase 11.1.1 để bật khóa danh tính')::text,
    CURRENT_TIMESTAMP
FROM driver_verification_applications a
WHERE a.approved_revision IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM driver_verification_audit au
      WHERE au.application_id = a.id
  );
