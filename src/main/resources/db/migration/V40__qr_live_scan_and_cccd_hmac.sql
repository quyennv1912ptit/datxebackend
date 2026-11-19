ALTER TABLE driver_verification_applications
    ADD COLUMN qr_live_scan_json text,
    ADD COLUMN cccd_id_hmac varchar(64),
    ADD COLUMN qr_cccd_id_hmac varchar(64);

CREATE INDEX idx_dva_cccd_id_hmac
    ON driver_verification_applications(cccd_id_hmac)
    WHERE cccd_id_hmac IS NOT NULL;

CREATE INDEX idx_dva_qr_cccd_id_hmac
    ON driver_verification_applications(qr_cccd_id_hmac)
    WHERE qr_cccd_id_hmac IS NOT NULL;
