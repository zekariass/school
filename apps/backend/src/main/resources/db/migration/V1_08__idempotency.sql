-- M1b · Idempotency-Key handling for online POSTs (Database_Design §4.6).
-- response_status is null while the first request is still running ("in progress").

create table idempotency_keys (
  key             text not null check (length(key) between 1 and 128),
  user_id         uuid not null references users,
  request_hash    text not null,                 -- SHA-256 of method, path and body
  response_status smallint,
  response_body   jsonb,
  created_at      timestamptz not null default now(),
  expires_at      timestamptz not null,
  primary key (user_id, key)
);
create index ix_idempotency_keys_expires on idempotency_keys (expires_at);
