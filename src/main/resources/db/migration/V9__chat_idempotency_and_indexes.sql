ALTER TABLE chat_messages ADD COLUMN client_message_id uuid;

CREATE UNIQUE INDEX uq_chat_client_msg
    ON chat_messages (conversation_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

CREATE INDEX idx_chat_conv_id
    ON chat_messages (conversation_id, id DESC);

CREATE INDEX idx_chat_unread
    ON chat_messages (conversation_id)
    WHERE read_at IS NULL;
