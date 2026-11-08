ALTER TABLE withdrawal_requests DROP CONSTRAINT withdrawal_requests_status_check;
UPDATE withdrawal_requests SET status = 'REJECTED' WHERE status = 'FAILED';
ALTER TABLE withdrawal_requests ADD COLUMN reject_reason varchar(500);
-- Preserve legacy rejections without inventing a bank error explanation.
UPDATE withdrawal_requests SET reject_reason = 'Yêu cầu đã bị từ chối trước khi lưu lý do' WHERE status = 'REJECTED';
ALTER TABLE withdrawal_requests ADD CONSTRAINT withdrawal_requests_status_check CHECK(status IN ('PENDING','SUCCESS','REJECTED'));
ALTER TABLE withdrawal_requests ADD CONSTRAINT withdrawal_rejection_reason_check CHECK
 ((status = 'REJECTED' AND reject_reason IS NOT NULL AND length(trim(reject_reason)) > 0) OR
  (status <> 'REJECTED' AND reject_reason IS NULL));
