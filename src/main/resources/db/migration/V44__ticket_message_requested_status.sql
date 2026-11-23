-- M13/M15: record the status an admin originally requested with a ticket message so a
-- retry is compared with that intent, not with the ticket's *current* status (which a
-- later message may have advanced). NULL means a legacy row with no recorded intent;
-- those rows keep the original permissive replay behaviour.
ALTER TABLE support_ticket_messages
    ADD COLUMN IF NOT EXISTS requested_status VARCHAR(32);
