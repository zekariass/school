-- M2a · media (Database_Design §4.9) and the media columns deferred by M1a/M1b:
-- organizations.logo_media_id, schools.logo_media_id, schools.cover_media_id, users.avatar_media_id.
--
-- Deviations from the design doc (also noted there): `context_type`/`context_id` name the object the media
-- belongs to (USER, SCHOOL, ORGANIZATION so far; later CLASS, POST, ...), which is what read authorization
-- derives from (R-30, no ACL table); `failure_reason` explains FAILED and QUARANTINED rows; storage_provider
-- defaults to 'S3' (any S3-compatible store: Cloudflare R2, SeaweedFS).

create table media (
  id                  uuid primary key default uuidv7(),
  owner_id            uuid not null references users,
  school_id           uuid references schools,
  purpose             text not null check (purpose in ('AVATAR', 'POST', 'STORY', 'MESSAGE', 'PORTFOLIO', 'SUBMISSION',
                        'ASSIGNMENT', 'LIBRARY', 'COURSE', 'RECORDING', 'TRANSCRIPT', 'VERIFICATION_DOC', 'IMPORT_FILE',
                        'REPORT', 'BRANDING')),
  context_type        text not null check (context_type in ('USER', 'SCHOOL', 'ORGANIZATION')),
  context_id          uuid not null,
  storage_provider    text not null default 'S3',
  bucket              text not null,
  object_key          text not null unique,
  original_filename   text,
  mime_type           text not null,
  media_kind          text not null check (media_kind in ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER')),
  size_bytes          bigint check (size_bytes is null or size_bytes >= 0),
  checksum_sha256     text check (checksum_sha256 is null or checksum_sha256 ~ '^[0-9a-f]{64}$'),
  width               int,
  height              int,
  duration_ms         int,
  page_count          int,
  status              text not null default 'PENDING_UPLOAD'
                        check (status in ('PENDING_UPLOAD', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'QUARANTINED',
                                          'DELETED')),
  failure_reason      text,
  moderation_status   text not null default 'NOT_REQUIRED'
                        check (moderation_status in ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')),
  retention_until     timestamptz,
  legal_hold          boolean not null default false,
  uploaded_at         timestamptz,
  deleted_at          timestamptz,
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create index ix_media_owner on media (owner_id, id desc);
create index ix_media_school on media (school_id) where school_id is not null;
create index ix_media_context on media (context_type, context_id);
-- the clean-up job of stale uploads and the processing sweep
create index ix_media_pending on media (created_at) where status = 'PENDING_UPLOAD';

create table media_variants (
  id              uuid primary key default uuidv7(),
  media_id        uuid not null references media on delete cascade,
  variant         text not null check (variant in ('THUMB', 'SMALL', 'MEDIUM', 'LARGE', 'HLS_MASTER', 'POSTER',
                    'PDF_PREVIEW')),
  object_key      text not null unique,
  mime_type       text not null,
  width           int,
  height          int,
  size_bytes      bigint,
  unique (media_id, variant)
);

alter table users add column avatar_media_id uuid references media on delete set null;
create index ix_users_avatar_media on users (avatar_media_id) where avatar_media_id is not null;

alter table organizations add column logo_media_id uuid references media on delete set null;
create index ix_organizations_logo_media on organizations (logo_media_id) where logo_media_id is not null;

alter table schools add column logo_media_id uuid references media on delete set null;
alter table schools add column cover_media_id uuid references media on delete set null;
create index ix_schools_logo_media on schools (logo_media_id) where logo_media_id is not null;
create index ix_schools_cover_media on schools (cover_media_id) where cover_media_id is not null;
