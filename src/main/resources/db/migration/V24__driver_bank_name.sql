-- Admin cần biết ngân hàng nhận tiền khi duyệt yêu cầu rút.
-- bank_account_number đã có từ V1; bổ sung tên ngân hàng còn thiếu.
ALTER TABLE drivers ADD COLUMN bank_name varchar(255);
