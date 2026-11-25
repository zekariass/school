-- M4a · roster import (Database_Design §4.15): mapping profiles, import jobs and their rows, external systems and
-- external entity mappings, plus the staging table for guardian contacts and the student name fold key.
--
-- Deviations from the design doc (also noted there):
--   * import_jobs is ONE row that walks a state machine (UPLOADED, PREVIEWED, VALIDATING, VALIDATED, COMMITTING,
--     COMPLETED, FAILED, CANCELLED) instead of a DRY_RUN job plus a COMMIT job that replays it: `mode` and
--     `dry_run_job_id` are dropped, `validated_hash` pins what the dry run saw (file fingerprint, mapping, options).
--   * import_type: STUDENTS, STAFF, CLASSES, SUBJECTS, GRADE_LEVELS, ENROLLMENTS. Guardian contacts are columns of
--     the STUDENTS file (staged in guardian_contact_candidates), academic years and periods are not imported.
--   * import_jobs adds options, detected, headers, content_fingerprint, validated_hash, progress counters,
--     unchanged_count, a lease (a crashed worker's job is picked up again), failure details and the purge stamps
--     rows_purged_at / file_purged_at. error_report_media_id is omitted: the error report is rendered on demand.
--   * import_job_rows adds school_id (composite FK, tenant isolation), warnings, row_hash, duplicate_candidates,
--     resolution, apply result columns. Row status keeps its meaning (VALID, INVALID, DUPLICATE, SKIPPED); what the
--     commit did is apply_result (CREATED, UPDATED, UNCHANGED, SKIPPED) with applied_at, so a row is applied at most
--     once however often the commit event is delivered. Raw and normalized data hold PII of minors and are purged by
--     the retention job (import_job_rows rows stay for the counts, their payloads become null).
--   * sync_runs is omitted: vendor connectors are not planned before a later phase.
--   * guardian_contact_candidates is new: contacts named in a roster file wait here until M5 turns them into
--     invitations. An imported contact never creates an account or a guardian link (R-29, spec section 125).
--   * student_profiles.name_fold: comparison key of a student's full name (Ethiopic homophones folded), maintained
--     by the people module and filled for existing rows by a startup runner; a trigram index serves duplicate
--     detection.

create table external_systems (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  system_type     text not null,                 -- CSV | <VENDOR_CODE> (future connectors)
  name            text not null,
  config          jsonb check (config is null or jsonb_typeof(config) = 'object'),
  status          text not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
  unique (id, school_id),
  unique (school_id, system_type, name),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);

create table external_entity_mappings (          -- stable external ids (section 125)
  id                  uuid primary key default uuidv7(),
  school_id           uuid not null,
  external_system_id  uuid not null,
  entity_type         text not null check (entity_type in ('STUDENT', 'STAFF', 'CLASS', 'SUBJECT', 'GUARDIAN_CONTACT',
                        'ACADEMIC_YEAR')),
  external_id         text not null,
  internal_id         uuid not null,
  last_synced_at      timestamptz,
  foreign key (external_system_id, school_id) references external_systems (id, school_id),
  unique (external_system_id, entity_type, external_id)
);
create index ix_external_mappings_internal on external_entity_mappings (school_id, entity_type, internal_id);

create table import_mapping_profiles (           -- reusable column mappings: field -> header label
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  name            text not null,
  import_type     text not null check (import_type in ('STUDENTS', 'STAFF', 'CLASSES', 'SUBJECTS', 'GRADE_LEVELS',
                    'ENROLLMENTS')),
  column_mapping  jsonb not null check (jsonb_typeof(column_mapping) = 'object'),
  created_by      uuid not null references users,
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_import_profiles_name on import_mapping_profiles (school_id, import_type, lower(name));
create index ix_import_profiles_creator on import_mapping_profiles (created_by);

create table import_jobs (
  id                  uuid primary key default uuidv7(),
  school_id           uuid not null references schools,
  import_type         text not null check (import_type in ('STUDENTS', 'STAFF', 'CLASSES', 'SUBJECTS', 'GRADE_LEVELS',
                        'ENROLLMENTS')),
  source_media_id     uuid not null references media,
  source_filename     text not null,
  content_fingerprint text not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),   -- SHA-256 of the uploaded file
  mapping_profile_id  uuid,
  column_mapping      jsonb not null default '{}' check (jsonb_typeof(column_mapping) = 'object'),  -- field -> column index
  options             jsonb not null default '{}' check (jsonb_typeof(options) = 'object'),
  detected            jsonb check (detected is null or jsonb_typeof(detected) = 'object'),  -- encoding, delimiter, sheets
  headers             jsonb check (headers is null or jsonb_typeof(headers) = 'array'),
  external_system_id  uuid,
  status              text not null default 'UPLOADED'
                        check (status in ('UPLOADED', 'PREVIEWED', 'VALIDATING', 'VALIDATED', 'COMMITTING', 'COMPLETED',
                                          'FAILED', 'CANCELLED')),
  total_rows          int,
  valid_rows          int,
  error_rows          int,
  duplicate_rows      int,
  created_count       int,
  updated_count       int,
  unchanged_count     int,
  skipped_count       int,
  progress_done       int not null default 0,
  progress_total      int not null default 0,
  validated_hash      text,
  failure_phase       text check (failure_phase is null or failure_phase in ('VALIDATE', 'COMMIT')),
  failure_row_number  int,
  failure_code        text,
  lease_owner         text,
  lease_until         timestamptz,
  initiated_by        uuid not null references users,
  started_at          timestamptz,
  completed_at        timestamptz,
  rows_purged_at      timestamptz,
  file_purged_at      timestamptz,
  foreign key (mapping_profile_id, school_id) references import_mapping_profiles (id, school_id),
  foreign key (external_system_id, school_id) references external_systems (id, school_id),
  unique (id, school_id),
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create index ix_import_jobs_school on import_jobs (school_id, id desc);
create index ix_import_jobs_media on import_jobs (source_media_id);
create index ix_import_jobs_initiator on import_jobs (initiated_by);
create index ix_import_jobs_profile on import_jobs (mapping_profile_id) where mapping_profile_id is not null;
create index ix_import_jobs_running on import_jobs (status) where status in ('VALIDATING', 'COMMITTING');
create index ix_import_jobs_retention on import_jobs (created_at) where rows_purged_at is null;

create table import_job_rows (
  id                   uuid primary key default uuidv7(),
  import_job_id        uuid not null,
  school_id            uuid not null,
  row_number           int not null,                -- the row as the file shows it (header included)
  raw_data             jsonb check (raw_data is null or jsonb_typeof(raw_data) = 'array'),       -- original cells, in column order
  normalized_data      jsonb check (normalized_data is null or jsonb_typeof(normalized_data) = 'object'),
  row_hash             text check (row_hash is null or row_hash ~ '^[0-9a-f]{64}$'),             -- SHA-256 of normalized_data
  status               text not null check (status in ('VALID', 'INVALID', 'DUPLICATE', 'SKIPPED')),
  planned_action       text check (planned_action is null or planned_action in ('CREATE', 'UPDATE', 'NONE')),
  errors               jsonb,                       -- [{column, code, args}]
  warnings             jsonb,                       -- [{column, code, args}]
  duplicate_candidates jsonb,                       -- [{studentId, displayName, dateOfBirth, similarity, ...}]
  resolution           text check (resolution is null or resolution in ('LINK', 'CREATE_NEW', 'SKIP')),
  resolved_entity_id   uuid,
  entity_type          text,
  entity_id            uuid,
  apply_result         text check (apply_result is null or apply_result in ('CREATED', 'UPDATED', 'UNCHANGED', 'SKIPPED')),
  applied_at           timestamptz,
  foreign key (import_job_id, school_id) references import_jobs (id, school_id) on delete cascade,
  unique (import_job_id, row_number)
);
create index ix_import_rows_status on import_job_rows (import_job_id, status, row_number);

create table guardian_contact_candidates (       -- staged by a student import; M5 turns them into invitations
  id                uuid primary key default uuidv7(),
  school_id         uuid not null references schools,
  student_id        uuid not null references student_profiles,
  import_job_id     uuid references import_jobs on delete set null,
  relationship_type text not null default 'OTHER'
                      check (relationship_type in ('MOTHER', 'FATHER', 'GRANDPARENT', 'SIBLING', 'AUNT_UNCLE',
                                                   'LEGAL_GUARDIAN', 'OTHER')),
  name              text,
  phone_e164        text,
  email             text,
  status            text not null default 'PENDING' check (status in ('PENDING', 'INVITED', 'DISCARDED')),
  created_at        timestamptz not null default now(),
  check (phone_e164 is not null or email is not null)
);
create unique index ux_guardian_candidates_live on guardian_contact_candidates
  (student_id, coalesce(phone_e164, ''), coalesce(lower(email), '')) where status in ('PENDING', 'INVITED');
create index ix_guardian_candidates_school on guardian_contact_candidates (school_id, phone_e164)
  where phone_e164 is not null;
create index ix_guardian_candidates_job on guardian_contact_candidates (import_job_id) where import_job_id is not null;

alter table student_profiles add column name_fold text;
create index ix_student_profiles_name_fold on student_profiles using gin (name_fold gin_trgm_ops);
