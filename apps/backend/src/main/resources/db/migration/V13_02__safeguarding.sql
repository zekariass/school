-- M13a · safeguarding incidents (Database_Design §4.17, docs/Safeguarding_Rules.md S4, S6).
--
-- Deviations from the design doc (also noted there):
--   * description is narrative_enc: an application-level AES-256-GCM envelope "v1.<key id>.<nonce>.<ciphertext>" (base64url
--     parts). The plain text never reaches the database. referred_to is encrypted as well (referred_to_enc): a free text
--     naming an authority or a person is as sensitive as the narrative. No column holds text in the clear.
--   * source_case_id (the case an incident was escalated from), referred_at and guardian_informed_at (manual, audited
--     fields: the system never notifies guardians or authorities), legal_hold (+ who and when) are new.
--     involved_user_ids is bounded to 20.
--   * safeguarding_incident_notes carries a kind (NOTE, STATUS, ASSIGNMENT, ...) and status_from/status_to so that "every
--     change is a note or a status row": the table is the append-only history (trigger; the retention job sets the
--     transaction-local setting school.retention_purge to 'on' to delete expired rows). note_enc is encrypted and may be
--     empty for pure status rows.
--   * safeguarding_incidents.school_id is nullable: an incident about a platform-scope target belongs to the platform leads.

create table safeguarding_incidents (
  id                      uuid primary key default uuidv7(),
  school_id               uuid references schools,
  raised_by               uuid not null references users,
  source_case_id          uuid references moderation_cases on delete set null,
  concerned_student_id    uuid references student_profiles,
  involved_user_ids       uuid[] not null default '{}' check (cardinality(involved_user_ids) <= 20),
  category                text not null check (category in ('CONDUCT', 'ONLINE_SAFETY', 'WELLBEING', 'TUTOR_CONDUCT', 'DATA_BREACH', 'OTHER')),
  severity                text not null check (severity in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
  status                  text not null default 'OPEN' check (status in ('OPEN', 'INVESTIGATING', 'ACTION_TAKEN', 'REFERRED', 'CLOSED')),
  assigned_to             uuid references users,
  narrative_enc           text not null,
  referred_to_enc         text,
  referred_at             timestamptz,
  guardian_informed_at    timestamptz,
  closed_at               timestamptz,
  legal_hold              boolean not null default false,
  legal_hold_by           uuid references users,
  legal_hold_at           timestamptz,
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  check ((status = 'CLOSED') = (closed_at is not null))
);
create index ix_incidents_school on safeguarding_incidents (school_id, status, severity, created_at desc, id desc);
create index ix_incidents_platform on safeguarding_incidents (status, severity, created_at desc, id desc) where school_id is null;
create index ix_incidents_raised_by on safeguarding_incidents (raised_by);
create index ix_incidents_assignee on safeguarding_incidents (assigned_to) where assigned_to is not null;
create index ix_incidents_student on safeguarding_incidents (concerned_student_id) where concerned_student_id is not null;
create index ix_incidents_case on safeguarding_incidents (source_case_id) where source_case_id is not null;
create index ix_incidents_retention on safeguarding_incidents (closed_at) where closed_at is not null and not legal_hold;

create table safeguarding_incident_notes (
  id                  uuid primary key default uuidv7(),
  incident_id         uuid not null references safeguarding_incidents,
  author_id           uuid not null references users,
  kind                text not null check (kind in ('NOTE', 'STATUS', 'ASSIGNMENT', 'REOPEN', 'CLOSE', 'GUARDIAN_INFORMED', 'REFERRAL',
                        'HOLD_SET', 'HOLD_LIFTED', 'ATTACHMENT')),
  status_from         text,
  status_to           text,
  note_enc            text,
  attachment_media_id uuid references media,
  created_at          timestamptz not null default now()
);
create index ix_incident_notes_incident on safeguarding_incident_notes (incident_id, created_at, id);
create index ix_incident_notes_author on safeguarding_incident_notes (author_id);
create index ix_incident_notes_media on safeguarding_incident_notes (attachment_media_id) where attachment_media_id is not null;

create function safeguarding_notes_append_only() returns trigger language plpgsql as $$
begin
  if coalesce(current_setting('school.retention_purge', true), '') = 'on' and tg_op = 'DELETE' then
    return old;
  end if;
  raise exception 'safeguarding_incident_notes is append-only';
end;
$$;
create trigger tr_safeguarding_notes_append_only before update or delete on safeguarding_incident_notes
  for each row execute function safeguarding_notes_append_only();

alter table moderation_cases add constraint fk_cases_incident foreign key (safeguarding_incident_id)
  references safeguarding_incidents (id) on delete set null;

-- The attachment purpose of incident notes: readable only by safeguarding leads (policy in the safeguarding module).
alter table media drop constraint media_purpose_check;
alter table media add constraint media_purpose_check
  check (purpose in ('AVATAR', 'POST', 'STORY', 'MESSAGE', 'PORTFOLIO', 'SUBMISSION', 'ASSIGNMENT', 'LIBRARY', 'COURSE',
                     'RECORDING', 'TRANSCRIPT', 'VERIFICATION_DOC', 'IMPORT_FILE', 'REPORT', 'BRANDING', 'SAFEGUARDING'));
