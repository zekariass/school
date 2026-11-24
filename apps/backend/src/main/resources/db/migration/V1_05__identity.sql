-- M1b · identity (Database_Design §4.3): users, password credentials, federated identities,
-- one-time codes and refresh-token sessions.
-- Not created here: student_login_codes (needs student_profiles and classes, M3).
-- users.avatar_media_id is added with the media tables in M2.

create table users (
  id                      uuid primary key default uuidv7(),
  account_type            text not null default 'STANDARD' check (account_type in ('STANDARD', 'MANAGED')),
  email                   citext unique,
  email_verified_at       timestamptz,
  phone_e164              text unique check (phone_e164 is null or phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
  phone_verified_at       timestamptz,
  username                citext unique,
  given_name              text not null,
  middle_name             text,
  family_name             text,
  display_name            text not null,
  date_of_birth           date,
  gender                  text,
  -- avatar_media_id uuid references media: added with the media tables in M2.
  home_country_code       char(2) references countries,
  preferred_language_code text references languages,
  locale                  text,
  timezone                text,
  calendar_preference     text references calendar_systems,
  status                  text not null default 'ACTIVE'
                            check (status in ('PENDING', 'ACTIVE', 'LOCKED', 'SUSPENDED', 'DEACTIVATED')),
  last_login_at           timestamptz,
  deleted_at              timestamptz,
  check (account_type = 'MANAGED' or email is not null or phone_e164 is not null),
  -- std
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  version                 int not null default 0
);
create index ix_users_name_trgm on users using gin (display_name gin_trgm_ops);
create index ix_users_home_country on users (home_country_code);
create index ix_users_language on users (preferred_language_code);
create index ix_users_calendar on users (calendar_preference);

create table password_credentials (
  user_id         uuid primary key references users,
  password_hash   text not null,                 -- argon2id (Spring Security encoded form)
  must_change     boolean not null default false,
  changed_at      timestamptz not null default now(),
  failed_attempts smallint not null default 0 check (failed_attempts >= 0),
  locked_until    timestamptz
);

-- Federated sign-in (Google, Apple) is deferred until client ids exist; the table is ready.
create table federated_identities (
  id               uuid primary key default uuidv7(),
  user_id          uuid not null references users,
  provider         text not null check (provider in ('GOOGLE', 'APPLE', 'MICROSOFT')),
  provider_subject text not null,
  email            citext,
  created_at       timestamptz not null default now(),
  unique (provider, provider_subject)
);
create index ix_federated_identities_user on federated_identities (user_id);

-- code_hash = HMAC-SHA256(pepper, id || ':' || code); the code itself is never stored.
create table otp_challenges (
  id              uuid primary key default uuidv7(),
  user_id         uuid references users,
  channel         text not null check (channel in ('SMS', 'EMAIL', 'VOICE')),
  destination     text not null,                 -- E.164 phone or lower-cased email
  purpose         text not null
                    check (purpose in ('LOGIN', 'VERIFY_PHONE', 'VERIFY_EMAIL', 'RESET_PASSWORD', 'ACCEPT_INVITE')),
  code_hash       text not null,
  attempts        smallint not null default 0 check (attempts >= 0),
  expires_at      timestamptz not null,
  consumed_at     timestamptz,
  request_ip      inet,
  created_at      timestamptz not null default now()
);
create index ix_otp_challenges_live on otp_challenges (destination, purpose, created_at desc)
  where consumed_at is null;
create index ix_otp_challenges_user on otp_challenges (user_id);
create index ix_otp_challenges_created on otp_challenges (created_at);

-- One row per refresh token. Rotation revokes the presented row (ROTATED) and inserts the next
-- one in the same token_family_id; the access token's "sid" claim is the family id.
create table auth_sessions (
  id                  uuid primary key default uuidv7(),
  user_id             uuid not null references users,
  refresh_token_hash  text not null unique,      -- SHA-256 (hex) of the refresh token
  token_family_id     uuid not null,
  auth_method         text not null check (auth_method in ('PASSWORD', 'OTP', 'FEDERATED', 'LOGIN_CODE')),
  user_agent          text,
  ip                  inet,
  created_at          timestamptz not null default now(),
  last_seen_at        timestamptz,
  expires_at          timestamptz not null,      -- absolute expiry of the family (first sign-in + 30 days)
  revoked_at          timestamptz,
  revoked_reason      text check (revoked_reason is null
                        or revoked_reason in ('ROTATED', 'LOGOUT', 'LOGOUT_ALL', 'REUSE_DETECTED', 'EXPIRED',
                                              'USER_INACTIVE', 'PASSWORD_CHANGED')),
  check ((revoked_at is null) = (revoked_reason is null))
);
create index ix_auth_sessions_family on auth_sessions (token_family_id);
create index ix_auth_sessions_user_active on auth_sessions (user_id) where revoked_at is null;
create index ix_auth_sessions_expires on auth_sessions (expires_at);
