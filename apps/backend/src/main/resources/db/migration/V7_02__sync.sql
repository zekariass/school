-- M7a · offline sync ledger (Database_Design §4.6, R-27): one row per operation a device sent.
--
-- Deviations from the design doc (also noted there):
--   * device_id is a client-generated installation id (uuid), not a foreign key to push_devices: a device may sync
--     without ever registering for push, and one install keeps one id for its life. Uniqueness is per
--     (user_id, device_id, client_operation_id), so one user can never read or collide with another's operation ids.
--   * school_id and entity_id say what the operation touched (support, retention); school_id is null when the payload
--     did not name a school (unknown operation type).
--   * recorded_at: the effective time of the operation (client_created_at clamped into [received_at - max age,
--     received_at]); received_at is the server's clock.
--   * payload and result.state hold children's data: they are removed after school.sync.payload-retention (the row,
--     the status, the problem code and the counts stay, so a replay is still recognized); rows go after
--     school.sync.row-retention.
--   * status DUPLICATE is stored when the operation had already been applied through another path (the online
--     endpoint with the same client operation id); a replay of any stored row answers DUPLICATE (with the original
--     status in the response).

create table sync_operations (
  id                  uuid primary key default uuidv7(),
  user_id             uuid not null references users,
  device_id           uuid not null,
  client_operation_id uuid not null,
  school_id           uuid references schools,
  operation_type      text not null check (char_length(operation_type) between 1 and 40),
  entity_type         text not null check (char_length(entity_type) between 1 and 40),
  entity_id           uuid,
  base_version        int,
  payload             jsonb,
  payload_purged_at   timestamptz,
  status              text not null check (status in ('APPLIED', 'DUPLICATE', 'CONFLICT', 'REJECTED')),
  problem_code        text,
  result              jsonb,
  client_created_at   timestamptz not null,
  recorded_at         timestamptz not null,
  received_at         timestamptz not null default now(),
  unique (user_id, device_id, client_operation_id),
  check ((payload is null) = (payload_purged_at is not null))
);
create index ix_sync_operations_received on sync_operations (received_at);
create index ix_sync_operations_unpurged on sync_operations (received_at) where payload_purged_at is null;
create index ix_sync_operations_school on sync_operations (school_id) where school_id is not null;
