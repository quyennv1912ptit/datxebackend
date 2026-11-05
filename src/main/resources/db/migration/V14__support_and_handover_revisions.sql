CREATE TABLE support_tickets (
    id BIGSERIAL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    owner_role VARCHAR(16) NOT NULL CHECK (owner_role IN ('CUSTOMER', 'DRIVER')),
    client_request_id UUID NOT NULL,
    ride_id BIGINT REFERENCES rides(id),
    category VARCHAR(32) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED')),
    response TEXT,
    handled_by BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(owner_id, owner_role, client_request_id)
);
CREATE INDEX idx_support_owner ON support_tickets(owner_role, owner_id, id DESC);
CREATE INDEX idx_support_queue ON support_tickets(status, id DESC);
CREATE TABLE support_ticket_images (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL REFERENCES support_tickets(id),
    file_url TEXT NOT NULL,
    public_id TEXT NOT NULL
);
ALTER TABLE vehicle_handover_reports ADD COLUMN revision INTEGER NOT NULL DEFAULT 1;
ALTER TABLE vehicle_handover_reports ADD COLUMN correction_reason TEXT;
ALTER TABLE vehicle_handover_reports ADD COLUMN evidence_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE vehicle_handover_reports ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE handover_revisions (
    id BIGSERIAL PRIMARY KEY,
    report_id BIGINT NOT NULL REFERENCES vehicle_handover_reports(id),
    revision INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    correction_reason TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(report_id, revision)
);
