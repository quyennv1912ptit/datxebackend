ALTER TABLE support_ticket_messages
    ADD COLUMN client_message_id UUID,
    ADD COLUMN requested_status VARCHAR(24);

CREATE UNIQUE INDEX uq_support_message_client_id
    ON support_ticket_messages (ticket_id, sender_id, sender_role, client_message_id)
    WHERE client_message_id IS NOT NULL;

CREATE INDEX idx_support_message_chronology
    ON support_ticket_messages (ticket_id, created_at, id);

CREATE INDEX idx_support_ticket_image_order
    ON support_ticket_images (ticket_id, id);
