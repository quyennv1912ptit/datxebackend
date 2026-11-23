-- F-017: Database-level partial unique indexes for active/approved driver identity locks.
-- Draft, REJECTED, and REVOKED records are excluded so existing APPROVED records remain
-- intact and unsubmitted/rejected records cannot lock another account's CCCD/GPLX.

CREATE UNIQUE INDEX IF NOT EXISTS uq_driver_verification_apps_locked_cccd
    ON driver_verification_applications(locked_cccd_id)
    WHERE locked_cccd_id IS NOT NULL
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO');

CREATE UNIQUE INDEX IF NOT EXISTS uq_driver_verification_apps_verified_gplx
    ON driver_verification_applications(UPPER(TRIM(verified_gplx_number)))
    WHERE verified_gplx_number IS NOT NULL
      AND TRIM(verified_gplx_number) <> ''
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO');
