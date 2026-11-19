ALTER TABLE driver_verification_applications
    ADD COLUMN IF NOT EXISTS gplx_qr_live_scan_json TEXT NULL;
