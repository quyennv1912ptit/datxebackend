-- Ticketing chat: mỗi ticket có nhiều tin nhắn, trạng thái mở rộng cho workflow CSKH.
ALTER TABLE support_tickets DROP CONSTRAINT IF EXISTS support_tickets_status_check;
ALTER TABLE support_tickets ADD CONSTRAINT support_tickets_status_check
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING_FOR_USER', 'RESOLVED', 'CLOSED'));

CREATE TABLE support_ticket_messages (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    sender_id BIGINT NOT NULL,
    sender_role VARCHAR(16) NOT NULL CHECK (sender_role IN ('CUSTOMER', 'DRIVER', 'ADMIN', 'SYSTEM')),
    body TEXT NOT NULL CHECK (length(btrim(body)) > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_support_ticket_messages_ticket ON support_ticket_messages(ticket_id, id);

-- Ticket cũ: nội dung mô tả ban đầu trở thành tin nhắn đầu tiên của người tạo.
INSERT INTO support_ticket_messages(ticket_id, sender_id, sender_role, body, created_at)
SELECT t.id, t.owner_id, t.owner_role, t.description, t.created_at
FROM support_tickets t
WHERE NOT EXISTS (SELECT 1 FROM support_ticket_messages m WHERE m.ticket_id = t.id);

-- Phản hồi đơn cũ (nếu có) trở thành tin nhắn ADMIN để timeline liên tục.
INSERT INTO support_ticket_messages(ticket_id, sender_id, sender_role, body, created_at)
SELECT t.id, COALESCE(t.handled_by, 0), 'ADMIN', t.response, t.updated_at
FROM support_tickets t
WHERE t.response IS NOT NULL AND btrim(t.response) <> ''
  AND NOT EXISTS (
      SELECT 1 FROM support_ticket_messages m
      WHERE m.ticket_id = t.id AND m.sender_role = 'ADMIN' AND m.body = t.response
  );
