-- M5a · consent (Database_Design §4.18, R-09): policy documents, their versions and consent decisions.
-- Phase 1 needs ACCOUNT_PARTICIPATION and MEDIA_PUBLICATION for children, TERMS and PRIVACY for adults.
--
-- Deviations from the design doc (also noted there):
--   * policy_documents: one country default per (type, country) and one school document per (type, school)
--     (partial unique indexes); exactly one of country_code / school_id is set.
--   * policy_versions: created_at; versions are immutable once published (enforced in code: no update path for a
--     published row).
--   * consent_records.granted_by_user_id is nullable, with granted_by_name for paper consent from a guardian without
--     an account (one of the two is required); student_guardian_id names the guardian link a decision belongs to
--     (null for an adult's own TERMS/PRIVACY and for named guardians without an account).
--   * consent_records is append-only: the code never updates or deletes rows, and a trigger refuses UPDATE (DELETE is
--     left for future data-subject erasure). The latest row per (subject, type, school, guardian) is the decision.
--   * evidence_media_id is kept for scanned paper forms but the API does not accept it yet (no CONSENT_EVIDENCE media
--     purpose in M5a).

create table policy_documents (
  id              uuid primary key default uuidv7(),
  policy_type     text not null check (policy_type in ('TERMS', 'PRIVACY', 'ACCOUNT_PARTICIPATION', 'MEDIA_PUBLICATION',
                                                       'RECORDING', 'TRANSCRIPTION', 'AI_PROCESSING',
                                                       'RESEARCH_ANALYTICS')),
  country_code    char(2) references countries,
  school_id       uuid references schools,       -- school-specific wording
  title           text not null,
  check ((country_code is null) <> (school_id is null)),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_policy_documents_country on policy_documents (policy_type, country_code) where school_id is null;
create unique index ux_policy_documents_school on policy_documents (policy_type, school_id) where school_id is not null;
create index ix_policy_documents_country on policy_documents (country_code);
create index ix_policy_documents_school on policy_documents (school_id);

create table policy_versions (
  id                 uuid primary key default uuidv7(),
  policy_document_id uuid not null references policy_documents,
  version_label      text not null check (length(version_label) between 1 and 40),
  language_code      text not null references languages,
  content            text not null,
  effective_from     timestamptz not null,
  requires_reconsent boolean not null default false,
  published_at       timestamptz,
  created_at         timestamptz not null default now(),
  constraint ux_policy_versions_label unique (policy_document_id, version_label, language_code)
);
create index ix_policy_versions_effective on policy_versions (policy_document_id, effective_from desc)
  where published_at is not null;
create index ix_policy_versions_language on policy_versions (language_code);

create table consent_records (                   -- append-only; latest row per (subject, type, school, guardian) wins
  id                  uuid primary key default uuidv7(),
  policy_version_id   uuid not null references policy_versions,
  consent_type        text not null check (consent_type in ('TERMS', 'PRIVACY', 'ACCOUNT_PARTICIPATION',
                                                            'MEDIA_PUBLICATION', 'RECORDING', 'TRANSCRIPTION',
                                                            'AI_PROCESSING', 'RESEARCH_ANALYTICS')),
  subject_user_id     uuid not null references users,    -- the child (or the adult themself)
  granted_by_user_id  uuid references users,             -- guardian or self; null for a named guardian on paper
  granted_by_name     text,                              -- paper consent from a guardian without an account
  student_guardian_id uuid references student_guardians, -- the guardian link the decision belongs to
  school_id           uuid references schools,
  scope_ref_type      text check (scope_ref_type is null
                                  or scope_ref_type in ('TUTORING_RELATIONSHIP', 'COURSE', 'VIDEO_ROOM')),
  scope_ref_id        uuid,
  decision            text not null check (decision in ('GRANTED', 'DENIED', 'WITHDRAWN')),
  method              text not null check (method in ('IN_APP', 'PAPER_RECORDED_BY_SCHOOL', 'VERBAL_RECORDED_BY_SCHOOL')),
  evidence_media_id   uuid references media,
  recorded_by         uuid references users,             -- staff who entered a paper or verbal consent
  expires_at          timestamptz,
  ip                  inet,
  created_at          timestamptz not null default now(),
  check (granted_by_user_id is not null or granted_by_name is not null),
  check (method = 'IN_APP' or recorded_by is not null),
  check ((scope_ref_type is null) = (scope_ref_id is null))
);
create index ix_consent_lookup on consent_records (subject_user_id, consent_type, created_at desc);
create index ix_consent_school on consent_records (school_id, consent_type) where school_id is not null;
create index ix_consent_version on consent_records (policy_version_id);
create index ix_consent_granted_by on consent_records (granted_by_user_id) where granted_by_user_id is not null;
create index ix_consent_link on consent_records (student_guardian_id) where student_guardian_id is not null;
create index ix_consent_recorded_by on consent_records (recorded_by) where recorded_by is not null;
create index ix_consent_evidence on consent_records (evidence_media_id) where evidence_media_id is not null;

create function consent_records_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'consent_records is append-only';
end;
$$;
create trigger tr_consent_records_append_only before update on consent_records
  for each row execute function consent_records_append_only();
