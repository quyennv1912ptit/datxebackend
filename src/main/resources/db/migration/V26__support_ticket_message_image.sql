-- Cho phép tin nhắn chỉ có ảnh (body rỗng) hoặc chỉ có chữ.
ALTER TABLE support_ticket_messages ADD COLUMN image_url VARCHAR(2048);

ALTER TABLE support_ticket_messages
    DROP CONSTRAINT IF EXISTS support_ticket_messages_body_check;
ALTER TABLE support_ticket_messages
    ADD CONSTRAINT support_ticket_messages_body_or_image_check
    CHECK (length(btrim(body)) > 0 OR image_url IS NOT NULL);
