-- M13/M14: mọi tin nhắn ticket phải có khoá idempotency ổn định theo (ticket, client_request_id).
-- Giữ DEFAULT gen_random_uuid() để các writer cũ (endpoint legacy, script, dữ liệu cũ) vẫn ghi được;
-- luồng mới luôn gửi khoá rõ từ client/derived key nên double-submit không tạo thêm dòng timeline.
ALTER TABLE support_ticket_messages
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

UPDATE support_ticket_messages
SET client_request_id = gen_random_uuid()
WHERE client_request_id IS NULL;

ALTER TABLE support_ticket_messages
    ALTER COLUMN client_request_id SET DEFAULT gen_random_uuid();

ALTER TABLE support_ticket_messages
    ALTER COLUMN client_request_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'support_ticket_messages_ticket_client_request_id_key'
    ) THEN
        ALTER TABLE support_ticket_messages
            ADD CONSTRAINT support_ticket_messages_ticket_client_request_id_key
            UNIQUE (ticket_id, client_request_id);
    END IF;
END $$;
