-- Đánh dấu voucher thưởng cá nhân đã được hoàn dự phòng phát hành để trả lại quỹ tổng đúng một lần.
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS reserve_released boolean NOT NULL DEFAULT false;
