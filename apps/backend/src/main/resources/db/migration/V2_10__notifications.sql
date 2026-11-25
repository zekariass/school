-- M2b · notifications and push devices (Database_Design §4.13).
--
-- Deviations from the design doc (also noted there):
--   * push_devices.session_family_id replaces auth_session_id. auth_sessions has one row per refresh token and
--     a new row on every refresh, so the family id (the `sid` claim) is what identifies "this sign-in" for as
--     long as it lives; devices are revoked when their family is revoked.
--   * notification_deliveries.last_attempt_at drives the retry back-off; two partial unique indexes make fan-out
--     idempotent (the outbox delivers at least once).
--   * notifications stays a plain table for now. The design calls for monthly partitions; add them (with the
--     retention job) when volume warrants: the primary key would then become (id, created_at).
--   * notification_types.description is kept but the seed leaves it as English developer notes; user-facing
--     texts live in messages*.properties (notification.<type>.title / .body).

create table notification_types (
  code                text primary key,
  category            text not null check (category in ('CLASSROOM', 'MESSAGING', 'SCHOOL', 'LEARNING', 'MARKETPLACE',
                        'ACCOUNT', 'SAFETY')),
  default_channels    text[] not null,
  can_be_urgent       boolean not null default false,
  user_can_disable    boolean not null default true,
  description         text
);

create table notifications (
  id                  uuid primary key default uuidv7(),
  user_id             uuid not null references users,
  type_code           text not null references notification_types,
  school_id           uuid references schools,
  title               text not null,
  body                text,
  data                jsonb,
  source_type         text,
  source_id           uuid,
  priority            text not null default 'NORMAL' check (priority in ('NORMAL', 'URGENT')),
  read_at             timestamptz,
  archived_at         timestamptz,
  created_at          timestamptz not null default now()
);
create index ix_notifications_inbox on notifications (user_id, created_at desc, id desc) where archived_at is null;
create index ix_notifications_unread on notifications (user_id) where archived_at is null and read_at is null;
create index ix_notifications_source on notifications (user_id, type_code, source_type, source_id)
  where source_id is not null;

create table push_devices (
  id                  uuid primary key default uuidv7(),
  user_id             uuid not null references users,
  session_family_id   uuid,
  platform            text not null check (platform in ('IOS', 'ANDROID', 'WEB')),
  provider            text not null check (provider in ('FCM', 'APNS', 'EXPO', 'WEBPUSH')),
  push_token          text not null unique,
  app_version         text,
  device_model        text,
  locale              text,
  created_at          timestamptz not null default now(),
  last_seen_at        timestamptz,
  revoked_at          timestamptz
);
create index ix_push_devices_user on push_devices (user_id) where revoked_at is null;
create index ix_push_devices_family on push_devices (session_family_id) where revoked_at is null;

create table notification_deliveries (
  id                  uuid primary key default uuidv7(),
  notification_id     uuid not null references notifications on delete cascade,
  channel             text not null check (channel in ('PUSH', 'EMAIL', 'SMS', 'VOICE')),
  push_device_id      uuid references push_devices,
  destination         text,
  provider            text,
  provider_message_id text,
  status              text not null default 'QUEUED' check (status in ('QUEUED', 'SENT', 'DELIVERED', 'FAILED', 'SUPPRESSED')),
  suppressed_reason   text check (suppressed_reason is null
                        or suppressed_reason in ('QUIET_HOURS', 'PREFERENCE', 'NO_DEVICE', 'BLOCKED')),
  attempt_count       smallint not null default 0,
  last_error          text,
  queued_at           timestamptz not null default now(),
  last_attempt_at     timestamptz,
  sent_at             timestamptz,
  delivered_at        timestamptz
);
create unique index ux_deliveries_device on notification_deliveries (notification_id, push_device_id)
  where push_device_id is not null;
create unique index ux_deliveries_no_device on notification_deliveries (notification_id, channel)
  where push_device_id is null;
create index ix_deliveries_retry on notification_deliveries (status, last_attempt_at)
  where status in ('QUEUED', 'FAILED');

create table notification_preferences (
  user_id             uuid not null references users,
  type_code           text not null references notification_types,
  channel             text not null check (channel in ('IN_APP', 'PUSH', 'EMAIL', 'SMS')),
  enabled             boolean not null,
  primary key (user_id, type_code, channel)
);

create table user_notification_settings (
  user_id             uuid primary key references users,
  quiet_hours_start   time,
  quiet_hours_end     time,
  digest_mode         text not null default 'OFF' check (digest_mode in ('OFF', 'DAILY')),
  updated_at          timestamptz not null default now(),
  check ((quiet_hours_start is null) = (quiet_hours_end is null))
);
