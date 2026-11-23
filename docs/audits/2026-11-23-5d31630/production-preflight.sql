\set ON_ERROR_STOP on
\pset pager off

-- Cần truyền -v target_schema=<schema>; thiếu biến sẽ dừng tại lệnh bên dưới.
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL search_path = :"target_schema";
SET LOCAL lock_timeout = '3s';
SET LOCAL statement_timeout = '2min';

SELECT current_database() AS database_name, current_schema() AS schema_name,
       current_setting('server_version') AS server_version,
       current_setting('transaction_read_only') AS transaction_read_only,
       current_setting('TimeZone') AS current_session_timezone,
       pg_is_in_recovery() AS in_recovery;

\echo 'Flyway V43-V46'
WITH expected(version) AS (VALUES ('43'), ('44'), ('45'), ('46'))
SELECT expected.version,
       count(h.version) FILTER (WHERE h.success) AS successful_entries,
       count(h.version) FILTER (WHERE NOT h.success) AS failed_entries
FROM expected
LEFT JOIN :"target_schema".flyway_schema_history h USING (version)
GROUP BY expected.version
ORDER BY expected.version;

\echo 'V43: trùng khóa danh tính theo đúng predicate của UNIQUE index'
WITH cccd AS (
    SELECT count(*) AS affected_rows
    FROM :"target_schema".driver_verification_applications
    WHERE locked_cccd_id IS NOT NULL
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')
    GROUP BY locked_cccd_id
    HAVING count(*) > 1
), gplx AS (
    SELECT count(*) AS affected_rows
    FROM :"target_schema".driver_verification_applications
    WHERE verified_gplx_number IS NOT NULL
      AND TRIM(verified_gplx_number) <> ''
      AND status IN ('APPROVED', 'SUBMITTED', 'UNDER_REVIEW', 'NEEDS_MORE_INFO')
    GROUP BY UPPER(TRIM(verified_gplx_number))
    HAVING count(*) > 1
)
SELECT 'CCCD' AS document_type, count(*) AS duplicate_groups, coalesce(sum(affected_rows), 0) AS affected_rows FROM cccd
UNION ALL
SELECT 'GPLX', count(*), coalesce(sum(affected_rows), 0) FROM gplx;

\echo 'V44: yêu cầu rút tiền và lệnh giữ tiền'
SELECT count(*) AS withdrawals,
       count(*) FILTER (WHERE w.status = 'PENDING') AS pending,
       count(*) FILTER (
           WHERE w.status = 'PENDING'
             AND (t.id IS NULL OR t.status IS DISTINCT FROM 'PENDING'
                  OR t.driver_id IS DISTINCT FROM w.driver_id OR t.amount IS DISTINCT FROM -w.amount
                  OR t.wallet_type NOT IN ('CASH', 'INCOME'))
       ) AS pending_without_matching_hold
FROM :"target_schema".withdrawal_requests w
LEFT JOIN :"target_schema".wallet_transactions t
       ON t.withdrawal_id = w.id AND t.transaction_type = 'WITHDRAW';

SELECT count(*) = 2 AS has_v44_columns
FROM pg_attribute
WHERE attrelid = 'withdrawal_requests'::regclass AND NOT attisdropped
  AND attname IN ('bank_name_snapshot', 'bank_account_number_snapshot')
\gset
\if :has_v44_columns
SELECT count(*) FILTER (
           WHERE status = 'PENDING' AND (bank_name_snapshot IS NULL OR bank_account_number_snapshot IS NULL)
       ) AS pending_without_snapshot,
       count(*) FILTER (
           WHERE num_nonnulls(bank_name_snapshot, bank_account_number_snapshot) = 1
              OR btrim(bank_name_snapshot) = '' OR btrim(bank_account_number_snapshot) = ''
       ) AS invalid_snapshots,
       count(*) FILTER (WHERE num_nonnulls(bank_name_snapshot, bank_account_number_snapshot) = 2) AS snapshotted
FROM :"target_schema".withdrawal_requests;
\else
\echo 'V44: chưa có đủ cột snapshot; toàn bộ PENDING cần được đối soát trước cutover.'
\endif

\echo 'V45: số ticket và tin nhắn'
SELECT (SELECT count(*) FROM :"target_schema".support_tickets) AS tickets,
       (SELECT count(*) FROM :"target_schema".support_ticket_images) AS attachments,
       count(*) AS messages,
       count(*) FILTER (WHERE image_url IS NOT NULL) AS messages_with_image
FROM :"target_schema".support_ticket_messages;

SELECT count(*) = 2 AS has_v45_columns
FROM pg_attribute
WHERE attrelid = 'support_ticket_messages'::regclass AND NOT attisdropped
  AND attname IN ('client_message_id', 'requested_status')
\gset
\if :has_v45_columns
SELECT count(client_message_id) AS messages_with_client_key,
       count(*) FILTER (WHERE client_message_id IS NULL) AS messages_without_client_key
FROM :"target_schema".support_ticket_messages;

WITH duplicates AS (
    SELECT count(*) AS affected_rows
    FROM :"target_schema".support_ticket_messages
    WHERE client_message_id IS NOT NULL
    GROUP BY ticket_id, sender_id, sender_role, client_message_id
    HAVING count(*) > 1
)
SELECT count(*) AS duplicate_message_keys, coalesce(sum(affected_rows), 0) AS affected_rows
FROM duplicates;
\else
\echo 'V45: chưa có đủ cột idempotency; các tin cũ không có khóa client.'
\endif

\echo 'V46: chuyến đi, giá và lịch sử sửa bảng giá'
SELECT (SELECT count(*) FROM :"target_schema".rides) AS rides,
       (SELECT count(*) FROM :"target_schema".pricing_rules) AS pricing_rules;

SELECT count(*) = 6 AS has_v46_columns,
       to_regclass(format('%I.pricing_rule_changes', :'target_schema')) IS NOT NULL AS has_v46_audit
FROM pg_attribute
WHERE attrelid = 'rides'::regclass AND NOT attisdropped
  AND attname IN ('pricing_rule_id', 'pricing_base_fare', 'pricing_distance_fare',
                  'pricing_waiting_fare', 'pricing_night_surcharge', 'pricing_return_fee')
\gset
\if :has_v46_columns
WITH snapshots AS (
    SELECT num_nonnulls(r.pricing_rule_id, r.pricing_base_fare, r.pricing_distance_fare,
                        r.pricing_waiting_fare, r.pricing_night_surcharge, r.pricing_return_fee) AS fields,
           r.pricing_rule_id IS NOT NULL AND p.id IS NULL AS missing_rule
    FROM :"target_schema".rides r
    LEFT JOIN :"target_schema".pricing_rules p ON p.id = r.pricing_rule_id
)
SELECT count(*) FILTER (WHERE fields = 0) AS rides_without_snapshot,
       count(*) FILTER (WHERE fields = 6) AS rides_with_snapshot,
       count(*) FILTER (WHERE fields BETWEEN 1 AND 5) AS partial_snapshots,
       count(*) FILTER (WHERE missing_rule) AS orphan_pricing_rules
FROM snapshots;
\else
\echo 'V46: chưa có đủ cột snapshot giá; không suy đoán bảng giá của chuyến cũ.'
\endif
\if :has_v46_audit
SELECT count(*) AS pricing_rule_change_records FROM :"target_schema".pricing_rule_changes;
\endif

\echo 'Đối chiếu cấu trúc V43-V46'
WITH expected(migration, table_name, index_name) AS (
    VALUES ('V43', 'driver_verification_applications', 'uq_driver_verification_apps_locked_cccd'),
           ('V43', 'driver_verification_applications', 'uq_driver_verification_apps_verified_gplx'),
           ('V45', 'support_ticket_messages', 'uq_support_message_client_id'),
           ('V45', 'support_ticket_messages', 'idx_support_message_chronology'),
           ('V45', 'support_ticket_images', 'idx_support_ticket_image_order'),
           ('V46', 'pricing_rule_changes', 'pricing_rule_changes_rule_time')
)
SELECT migration, table_name, index_name, coalesce(idx.indisvalid AND idx.indisready, false) AS usable
FROM expected
LEFT JOIN pg_index idx
       ON idx.indexrelid = to_regclass(format('%I.%I', :'target_schema', expected.index_name))
      AND idx.indrelid = to_regclass(format('%I.%I', :'target_schema', expected.table_name))
ORDER BY migration, index_name;

WITH expected(migration, table_name, constraint_name) AS (
    VALUES ('V44', 'withdrawal_requests', 'ck_withdrawal_bank_snapshot'),
           ('V46', 'rides', 'rides_pricing_snapshot_complete'),
           ('V46', 'rides', 'rides_pricing_rule_id_fkey'),
           ('V46', 'pricing_rule_changes', 'pricing_rule_changes_pricing_rule_id_fkey')
)
SELECT migration, table_name, constraint_name, coalesce(c.convalidated, false) AS validated
FROM expected
LEFT JOIN pg_constraint c
       ON c.conrelid = to_regclass(format('%I.%I', :'target_schema', expected.table_name))
      AND c.conname = expected.constraint_name
ORDER BY migration, constraint_name;

SELECT EXISTS (
    SELECT 1 FROM pg_trigger
    WHERE tgrelid = 'rides'::regclass AND tgname = 'rides_immutable_pricing_snapshot'
      AND tgenabled IN ('O', 'A')
) AS fare_snapshot_trigger_enabled;

\echo 'F-051: kiểu dữ liệu và default hiện tại của các cột timestamp'
WITH expected(table_name, column_name) AS (
    VALUES ('withdrawal_requests', 'reviewed_at'), ('withdrawal_requests', 'created_at'),
           ('ride_wallet_settlements', 'created_at'),
           ('wallet_transactions', 'created_at'), ('wallet_transactions', 'updated_at'),
           ('drivers', 'last_location_update'), ('customers', 'last_login_at')
)
SELECT expected.table_name, expected.column_name, cols.data_type,
       cols.column_default IS NOT NULL AS has_default,
       coalesce(cols.column_default ILIKE '%AT TIME ZONE ''UTC''%', false) AS default_normalizes_to_utc
FROM expected
LEFT JOIN information_schema.columns cols
       ON cols.table_schema = :'target_schema'
      AND cols.table_name = expected.table_name AND cols.column_name = expected.column_name
ORDER BY expected.table_name, expected.column_name;

SELECT 'customers.last_login_at' AS field, count(last_login_at) AS populated_rows FROM :"target_schema".customers
UNION ALL SELECT 'drivers.last_location_update', count(last_location_update) FROM :"target_schema".drivers
UNION ALL SELECT 'ride_wallet_settlements.created_at', count(created_at) FROM :"target_schema".ride_wallet_settlements
UNION ALL SELECT 'wallet_transactions.created_at', count(created_at) FROM :"target_schema".wallet_transactions
UNION ALL SELECT 'wallet_transactions.updated_at', count(updated_at) FROM :"target_schema".wallet_transactions
UNION ALL SELECT 'withdrawal_requests.created_at', count(created_at) FROM :"target_schema".withdrawal_requests
UNION ALL SELECT 'withdrawal_requests.reviewed_at', count(reviewed_at) FROM :"target_schema".withdrawal_requests;

\echo 'F-045: tham chiếu ảnh lưu trữ thấy được trong PostgreSQL'
WITH vehicle_refs AS (
    SELECT i.cloudinary_public_id AS public_id, i.secure_url AS url, v.archived_at IS NOT NULL AS archived
    FROM :"target_schema".vehicle_images i
    JOIN :"target_schema".vehicles v ON v.id = i.vehicle_id
), archived_refs AS (
    SELECT public_id, url FROM vehicle_refs WHERE archived
), archived_assets AS (
    SELECT public_id FROM archived_refs GROUP BY public_id
), found_refs AS (
    SELECT public_id, 'vehicle' AS source FROM vehicle_refs WHERE NOT archived
    UNION ALL SELECT avatar_public_id, 'avatar' FROM :"target_schema".customers WHERE avatar_public_id IS NOT NULL
    UNION ALL SELECT avatar_public_id, 'avatar' FROM :"target_schema".drivers WHERE avatar_public_id IS NOT NULL
    UNION ALL SELECT public_id, 'support' FROM :"target_schema".support_ticket_images
    UNION ALL SELECT cloudinary_public_id, 'chat' FROM :"target_schema".chat_messages WHERE cloudinary_public_id IS NOT NULL
    UNION ALL SELECT cloudinary_public_id, 'handover' FROM :"target_schema".handover_photos WHERE cloudinary_public_id IS NOT NULL
    UNION ALL
    SELECT a.public_id, 'support_url' FROM archived_refs a
    JOIN :"target_schema".support_ticket_messages m ON a.url = m.image_url
), grouped_refs AS (
    SELECT a.public_id, count(f.public_id) AS found,
           coalesce(bool_or(f.source = 'vehicle'), false) AS vehicle,
           coalesce(bool_or(f.source = 'avatar'), false) AS avatar,
           coalesce(bool_or(f.source IN ('support', 'support_url')), false) AS support,
           coalesce(bool_or(f.source IN ('chat', 'handover')), false) AS other_media
    FROM archived_assets a
    LEFT JOIN found_refs f USING (public_id)
    GROUP BY a.public_id
)
SELECT (SELECT count(*) FROM archived_refs) AS archived_image_rows,
       count(*) AS distinct_archived_public_ids,
       count(*) FILTER (WHERE vehicle) AS also_on_active_vehicle,
       count(*) FILTER (WHERE avatar) AS also_on_avatar,
       count(*) FILTER (WHERE support) AS also_in_support,
       count(*) FILTER (WHERE other_media) AS also_in_chat_or_handover,
       count(*) FILTER (WHERE found = 0) AS no_match_in_checked_locations
FROM grouped_refs;

ROLLBACK;
