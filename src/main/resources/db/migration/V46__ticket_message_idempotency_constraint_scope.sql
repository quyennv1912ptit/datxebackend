-- V43 created the (ticket_id, client_request_id) unique constraint behind a guard that
-- looked up pg_constraint by name only. Constraint names are not schema-qualified in that
-- catalog, so on a database where migrations run in multiple schemas (test isolation,
-- release replay) the guard can see a constraint in a *different* schema and skip the
-- one in the current schema. SupportService then fails with "there is no unique or
-- exclusion constraint matching the ON CONFLICT specification".
-- Scope the guard to the table resolved through the current search_path so the
-- constraint always exists where the writer expects it. Safe no-op on a fresh schema.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        WHERE c.conrelid = 'support_ticket_messages'::regclass
          AND c.conname = 'support_ticket_messages_ticket_client_request_id_key'
    ) THEN
        ALTER TABLE support_ticket_messages
            ADD CONSTRAINT support_ticket_messages_ticket_client_request_id_key
            UNIQUE (ticket_id, client_request_id);
    END IF;
END $$;
