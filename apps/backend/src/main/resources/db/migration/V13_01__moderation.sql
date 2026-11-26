-- M13a · reports, moderation cases and their history (Database_Design §4.17, docs/Safeguarding_Rules.md S1, S2, S3, S5, S6).
--
-- Deviations from the design doc (also noted there):
--   * moderation_cases.school_id is kept for platform-queue cases too (it is the tenant of the target: retention, alerts,
--     the lead lookup need it); the QUEUE is a new column (SCHOOL | PLATFORM). A case about a school administrator,
--     moderator or safeguarding lead is routed to PLATFORM although it has a school.
--   * moderation_cases adds subject_user_id (the person whose content or conduct is reported), report_count and
--     reporter_count (counters kept by the intake), lead_alerted_at (the single alert to the safeguarding leads),
--     resolved_at (retention clock), legal_hold (+ who and when). decision_reason is NOT stored on the case: the reason
--     is the row of the decision in moderation_actions (append-only).
--   * content_reports always belongs to a case (case_id is NOT NULL: a report and its case are created together);
--     status is OPEN | CLOSED (LINKED would never be observable). reporter_role records "a student", "a guardian", ... so
--     school moderators can be shown the role without the identity. source_key makes event consumption idempotent
--     ('message_report:<id>', 'content_report:<id>').
--   * details are bounded to 1,000 characters and are shown only to those who may see the reporter's identity.
--   * moderation_actions is append-only (trigger). The retention job sets the transaction-local setting
--     school.retention_purge to 'on' to remove expired rows; nothing else may update or delete a row.
--   * account_warnings (WARN_USER) and school_suspensions (SUSPEND_USER at school level) are new.

create table moderation_cases (
  id                       uuid primary key default uuidv7(),
  school_id                uuid references schools,
  queue                    text not null check (queue in ('SCHOOL', 'PLATFORM')),
  target_type              text not null check (target_type in ('POST', 'POST_COMMENT', 'STORY', 'MESSAGE', 'USER',
                             'PORTFOLIO_ITEM', 'TUTOR_PROFILE', 'COURSE', 'TUTOR_REVIEW', 'LIBRARY_RESOURCE', 'MEDIA', 'AI_OUTPUT')),
  target_id                uuid not null,
  subject_user_id          uuid references users,
  source                   text not null default 'USER_REPORT' check (source in ('USER_REPORT', 'AUTOMATED', 'PRE_PUBLISH_REVIEW')),
  priority                 text not null default 'NORMAL' check (priority in ('LOW', 'NORMAL', 'HIGH', 'CRITICAL')),
  status                   text not null default 'OPEN' check (status in ('OPEN', 'ASSIGNED', 'RESOLVED', 'ESCALATED')),
  assigned_to              uuid references users,
  decision                 text check (decision in ('NO_ACTION', 'HIDE', 'REMOVE', 'WARN_USER', 'SUSPEND_USER', 'ESCALATE')),
  decided_by               uuid references users,
  decided_at               timestamptz,
  report_count             integer not null default 0,
  reporter_count           integer not null default 0,
  lead_alerted_at          timestamptz,
  safeguarding_incident_id uuid,
  legal_hold               boolean not null default false,
  legal_hold_by            uuid references users,
  legal_hold_at            timestamptz,
  resolved_at              timestamptz,
  created_at               timestamptz not null default now(),
  updated_at               timestamptz not null default now(),
  check (queue = 'PLATFORM' or school_id is not null),
  check (status <> 'ASSIGNED' or assigned_to is not null),
  check (status not in ('RESOLVED', 'ESCALATED') or decision is not null)
);
-- one open case per target
create unique index ux_open_case_per_target on moderation_cases (target_type, target_id) where status in ('OPEN', 'ASSIGNED');
create index ix_cases_school_queue on moderation_cases (school_id, status, priority, created_at desc, id desc) where queue = 'SCHOOL';
create index ix_cases_platform_queue on moderation_cases (status, priority, created_at desc, id desc) where queue = 'PLATFORM';
create index ix_cases_target on moderation_cases (target_type, target_id);
create index ix_cases_subject on moderation_cases (subject_user_id) where subject_user_id is not null;
create index ix_cases_assignee on moderation_cases (assigned_to) where assigned_to is not null;
create index ix_cases_retention on moderation_cases (resolved_at) where resolved_at is not null and not legal_hold;
create index ix_cases_incident on moderation_cases (safeguarding_incident_id) where safeguarding_incident_id is not null;

create table content_reports (
  id              uuid primary key default uuidv7(),
  case_id         uuid not null references moderation_cases,
  reporter_id     uuid not null references users,
  reporter_role   text not null check (reporter_role in ('STUDENT', 'GUARDIAN', 'TEACHER', 'STAFF', 'OTHER')),
  school_id       uuid references schools,
  target_type     text not null,
  target_id       uuid not null,
  reason_code     text not null check (reason_code in ('SPAM', 'HARASSMENT', 'INAPPROPRIATE', 'SAFETY_CONCERN', 'COPYRIGHT',
                    'PRIVACY', 'INACCURATE', 'OTHER')),
  details         text check (details is null or char_length(details) <= 1000),
  status          text not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
  source_key      text,
  created_at      timestamptz not null default now()
);
create unique index ux_content_report_source on content_reports (source_key) where source_key is not null;
-- a reporter cannot report the same target twice while a report of theirs is open
create unique index ux_content_report_open_once on content_reports (reporter_id, target_type, target_id) where status = 'OPEN';
create index ix_content_reports_case on content_reports (case_id, created_at);
create index ix_content_reports_reporter_day on content_reports (reporter_id, created_at desc);

create table moderation_actions (                -- append-only history of a case
  id              uuid primary key default uuidv7(),
  case_id         uuid not null references moderation_cases,
  actor_id        uuid not null references users,
  action          text not null check (action in ('ASSIGN', 'REASSIGN', 'REOPEN', 'NO_ACTION', 'HIDE', 'REMOVE', 'WARN_USER',
                    'SUSPEND_USER', 'ESCALATE', 'RESTORE', 'LIFT_SUSPENSION', 'UNLOCK_USER', 'REFER_UP', 'SET_PRIORITY',
                    'HOLD_SET', 'HOLD_LIFTED')),
  level           text check (level in ('SCHOOL', 'PLATFORM')),
  notes           text check (notes is null or char_length(notes) <= 500),
  created_at      timestamptz not null default now()
);
create index ix_actions_case on moderation_actions (case_id, created_at, id);
create index ix_actions_actor on moderation_actions (actor_id);

create function moderation_actions_append_only() returns trigger language plpgsql as $$
begin
  if coalesce(current_setting('school.retention_purge', true), '') = 'on' and tg_op = 'DELETE' then
    return old;
  end if;
  raise exception 'moderation_actions is append-only';
end;
$$;
create trigger tr_moderation_actions_append_only before update or delete on moderation_actions
  for each row execute function moderation_actions_append_only();

create table account_warnings (
  id              uuid primary key default uuidv7(),
  user_id         uuid not null references users,
  school_id       uuid references schools,
  case_id         uuid not null references moderation_cases,
  issued_by       uuid not null references users,
  created_at      timestamptz not null default now()
);
create index ix_warnings_user on account_warnings (user_id, created_at desc);
create index ix_warnings_case on account_warnings (case_id);
create index ix_warnings_issuer on account_warnings (issued_by);

create table school_suspensions (
  id               uuid primary key default uuidv7(),
  user_id          uuid not null references users,
  school_id        uuid not null references schools,
  case_id          uuid not null references moderation_cases,
  applied_by       uuid not null references users,
  applied_at       timestamptz not null default now(),
  affected_links   uuid[] not null default '{}',   -- guardian links this suspension restricted (and only those are lifted)
  lifted_at        timestamptz,
  lifted_by        uuid references users,
  check ((lifted_at is null) = (lifted_by is null))
);
create unique index ux_school_suspension_active on school_suspensions (user_id, school_id) where lifted_at is null;
create index ix_suspensions_case on school_suspensions (case_id);
create index ix_suspensions_school on school_suspensions (school_id);
create index ix_suspensions_applied_by on school_suspensions (applied_by);
create index ix_suspensions_lifted_by on school_suspensions (lifted_by) where lifted_by is not null;

-- Safety information shown by the reporting screens, per country (decision 10 of the rule book). The Ethiopian text is a
-- PLACEHOLDER and is flagged unreviewed until the owner supplies the final wording.
create table emergency_information (
  country_code    char(2) primary key references countries,
  text_i18n       jsonb not null,
  reviewed        boolean not null default false,
  updated_at      timestamptz not null default now()
);

-- Evidence hold and moderation state on the content moderation may take down (the content tables belong to feed and
-- messaging; these two nullable columns are the only change). moderation_case_id: some case concerns this content (its
-- photos are never discarded and a story's photo is kept past expiry while the hold lasts). moderation_hidden_at: a case
-- decision hid or removed it, so the owning module's own "unhide" refuses until the case restores it.
alter table posts add column moderation_case_id uuid, add column moderation_hidden_at timestamptz;
alter table post_comments add column moderation_case_id uuid, add column moderation_hidden_at timestamptz;
alter table stories add column moderation_case_id uuid, add column moderation_hidden_at timestamptz;
alter table messages add column moderation_case_id uuid, add column moderation_hidden_at timestamptz;
create index ix_posts_moderation_case on posts (moderation_case_id) where moderation_case_id is not null;
create index ix_post_comments_moderation_case on post_comments (moderation_case_id) where moderation_case_id is not null;
create index ix_stories_moderation_case on stories (moderation_case_id) where moderation_case_id is not null;
create index ix_messages_moderation_case on messages (moderation_case_id) where moderation_case_id is not null;

-- A student can be suspended at school level (reversible); every "ACTIVE" check across the modules then excludes them.
alter table school_students drop constraint school_students_status_check;
alter table school_students add constraint school_students_status_check
  check (status in ('PENDING', 'ACTIVE', 'SUSPENDED', 'TRANSFERRED', 'GRADUATED', 'WITHDRAWN'));
-- ... and cannot be enrolled a second time while suspended; the same for a suspended staff member.
drop index ux_school_student_active;
create unique index ux_school_student_active on school_students (school_id, student_id)
  where status in ('PENDING', 'ACTIVE', 'SUSPENDED');
drop index ux_school_staff_active;
create unique index ux_school_staff_active on school_staff (school_id, user_id)
  where status in ('INVITED', 'ACTIVE', 'SUSPENDED');
