
-- V38/V39 made manual approvals look like eKYC projections and applications.
-- Mark only projections supported by an actual native submission or decision.
ALTER TABLE driver_verification_projection
    ADD COLUMN native_workflow boolean NOT NULL DEFAULT false,
    ADD COLUMN synced_workflow boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_driver_verification_projection_source CHECK (NOT (native_workflow AND synced_workflow));

UPDATE driver_verification_projection p
SET native_workflow = true
FROM driver_verification_applications a
WHERE a.id = p.application_id AND a.owner_uid = p.owner_uid
  AND (
      (p.status = 'APPROVED' AND NOT a.identity_hold AND p.revision = a.approved_revision
       AND EXISTS (
           SELECT 1 FROM driver_verification_submissions s
           WHERE s.application_id = a.id AND s.revision = a.approved_submission_revision
             AND s.snapshot_json::jsonb ->> 'legacyBackfill' IS DISTINCT FROM 'true'
       ) AND EXISTS (
           SELECT 1 FROM driver_verification_audit au WHERE au.application_id = a.id
             AND au.revision = a.approved_revision AND au.action = 'APPROVED' AND au.actor_role = 'admin'
       ))
      OR (p.status <> 'APPROVED' AND EXISTS (
           SELECT 1 FROM driver_verification_audit au
           WHERE au.application_id = a.id AND au.revision = p.revision
             AND (au.action = p.status OR (au.action IN ('SUBMIT', 'RESUBMIT') AND p.status = 'SUBMITTED')
                  OR (au.action = 'IDENTITY_HOLD_APPLIED' AND p.status = 'NEEDS_MORE_INFO'))
       ))
  );

-- Before any external stream has been applied, unclassified projections without a real
-- submission have no eKYC decision source. Purge these verified legacy projections.
DELETE FROM driver_verification_projection p
WHERE NOT p.native_workflow
  AND EXISTS (SELECT 1 FROM verification_sync_checkpoint c WHERE c.singleton AND c.stream_id IS NULL)
  AND NOT EXISTS (
      SELECT 1 FROM driver_verification_applications a
      JOIN driver_verification_submissions s ON s.application_id = a.id
      WHERE a.owner_uid = p.owner_uid AND s.snapshot_json::jsonb ->> 'legacyBackfill' IS DISTINCT FROM 'true'
  );

-- Strip synthetic baselines even if the driver already started a real re-verification.
-- Keep the application ID, real captures/submissions, review state and audit history.
WITH synthetic AS (
    SELECT application_id, revision
    FROM driver_verification_submissions
    WHERE snapshot_json::jsonb ->> 'legacyBackfill' = 'true'
)
UPDATE driver_verification_applications a
SET status = CASE WHEN a.submission_revision = s.revision AND a.status = 'APPROVED' THEN 'DRAFT' ELSE a.status END,
    submission_revision = CASE WHEN a.submission_revision = s.revision THEN NULL ELSE a.submission_revision END,
    submitted_at = CASE WHEN a.submission_revision = s.revision THEN NULL ELSE a.submitted_at END,
    approved_revision = CASE WHEN a.approved_submission_revision = s.revision THEN NULL ELSE a.approved_revision END,
    approved_submission_revision = CASE WHEN a.approved_submission_revision = s.revision THEN NULL ELSE a.approved_submission_revision END,
    locked_cccd_id = NULL,
    locked_full_name = NULL,
    locked_dob = NULL,
    verified_gplx_number = NULL,
    confirmed_fields = CASE WHEN a.confirmed_revision <= s.revision THEN NULL ELSE a.confirmed_fields END,
    confirmed_at = CASE WHEN a.confirmed_revision <= s.revision THEN NULL ELSE a.confirmed_at END,
    confirmed_revision = CASE WHEN a.confirmed_revision <= s.revision THEN NULL ELSE a.confirmed_revision END,
    confirmation_current = CASE WHEN a.confirmed_revision <= s.revision THEN false ELSE a.confirmation_current END,
    revision = a.revision + 1,
    updated_at = CURRENT_TIMESTAMP
FROM synthetic s
WHERE a.id = s.application_id AND (
    a.approved_submission_revision = s.revision
    OR (a.approved_submission_revision IS NULL AND a.submission_revision = s.revision)
);

DELETE FROM driver_verification_submissions
WHERE snapshot_json::jsonb ->> 'legacyBackfill' = 'true';

-- Previously synced rows lack provenance. The authenticated decision feed must replay
-- to establish it. Until then, the existing rows are retained but cannot grant rights.
UPDATE verification_sync_checkpoint
SET cursor_seq = 0, caught_up_at = NULL
WHERE singleton AND stream_id IS NOT NULL;

-- Move drivers to the effective native verification state without disturbing rides in flight.
-- Existing started trips may finish using normal trip transitions. Assignment/start still
-- use current eKYC eligibility, and drivers without approval go offline after the trip.
WITH target AS (
    SELECT d.id, CASE
        WHEN p.status = 'APPROVED' THEN 'APPROVED'
        WHEN p.status IN ('REJECTED', 'REVOKED') THEN 'REJECTED'
        ELSE 'PENDING'
    END AS verification_status,
    EXISTS (
        SELECT 1 FROM rides r
        WHERE r.driver_id = d.id AND r.status IN (
            'DRIVER_ASSIGNED', 'DRIVER_ARRIVING', 'DRIVER_ARRIVED', 'VEHICLE_HANDOVER',
            'IN_PROGRESS', 'ARRIVED_DESTINATION', 'VEHICLE_RETURNED'
        )
    ) AS active_ride
    FROM drivers d
    LEFT JOIN driver_verification_projection p ON p.owner_uid = d.firebase_uid AND p.native_workflow
    WHERE d.deleted_at IS NULL AND d.verification_status IS DISTINCT FROM 'SUSPENDED'
      AND (d.verification_status IN ('APPROVED', 'REJECTED') OR d.license_verified OR d.status IN ('ONLINE', 'BUSY') OR p.native_workflow)
)
UPDATE drivers d
SET verification_status = t.verification_status,
    license_verified = (t.verification_status = 'APPROVED'),
    status = CASE WHEN t.verification_status <> 'APPROVED' AND NOT t.active_ride
                         AND d.status IN ('ONLINE', 'BUSY') THEN 'OFFLINE' ELSE d.status END,
    online_vehicle_type = CASE WHEN t.verification_status <> 'APPROVED' AND NOT t.active_ride
                               THEN NULL ELSE d.online_vehicle_type END
FROM target t
WHERE d.id = t.id;

DELETE FROM notifications WHERE notification_type = 'DRIVER_DOCUMENT_REVIEWED';
DROP TABLE driver_documents RESTRICT;
ALTER TABLE drivers DROP COLUMN license_image_url RESTRICT;
