-- M10a · school communications centre: announcements (Database_Design §4.12) and the two notification extensions it needs
-- (§4.13).
--
-- Deviations from the design doc (also noted there):
--   * announcements: announcement_type NORMAL|IMPORTANT|URGENT|SAFETY replaces priority + category (SAFETY was a category;
--     one column answers "may it bypass quiet hours" and "needs the urgent permission"); channels is IN_APP|PUSH|EMAIL|SMS
--     (VOICE is not built); translations is a map language -> {title, body} (decision D5: written by people, never machine
--     translated); revision counts the published versions; schedule_error_code says why a scheduled publish went back to
--     DRAFT; authored_as records the basis the author was authorized on (MANAGER or TEACHER) when the draft was last
--     validated; delivery_completed_at is set once every recipient's in-app notification exists; deleted_at (drafts only).
--     event_id is not here: events arrive with M11.
--   * announcement_audiences: school_id is not stored (the announcement carries it); one row per USER audience user.
--   * announcement_recipients: the snapshot also carries the recipient's role and the delivery state per channel
--     (notified_at / notification_id = the in-app inbox row; email_* and sms_* for the optional channels). Read and
--     acknowledgement are NOT here: they live in announcement_acknowledgements and also exist for readers who are not in
--     the snapshot (a guardian linked later). Push status is not copied: it is the notification_deliveries of
--     notification_id.
--   * announcement_acknowledgements: school_id and acknowledged_revision (the version that was acknowledged).
--   * announcement_revisions: row N holds version N as it was before the edit that replaced it; edited_by/edited_at are
--     who and when replaced it; material and change_note are what the editor said about that edit; translations kept too.
--   * announcement_notices (new): one row per "tell the recipients again" request (UPDATED after a material edit, REMINDER
--     for the unacknowledged, WITHDRAWN); it makes chunked delivery resumable and keeps reminders rate-limited.
--   * communication_preferences and sms_opt_outs (new): per-user opt-out of the email and SMS channels, and numbers that
--     opted out with the SMS provider; checked before every email or SMS, urgent or not.
--   * notification_types.defer_in_quiet_hours and urgent_overrides_preferences, notification_deliveries.not_before (see the
--     notes in §4.13).

create table announcements (
  id                       uuid primary key default uuidv7(),
  school_id                uuid not null references schools,
  author_id                uuid not null references users,
  title                    text not null check (char_length(title) between 1 and 200),
  body                     text not null check (char_length(body) between 1 and 5000),
  body_language_code       text references languages,
  translations             jsonb not null default '{}' check (jsonb_typeof(translations) = 'object'),
  announcement_type        text not null default 'NORMAL'
                             check (announcement_type in ('NORMAL', 'IMPORTANT', 'URGENT', 'SAFETY')),
  requires_acknowledgement boolean not null default false
                             check (not requires_acknowledgement or announcement_type <> 'NORMAL'),
  channels                 text[] not null default '{IN_APP,PUSH}'
                             check (channels <@ array['IN_APP', 'PUSH', 'EMAIL', 'SMS'] and 'IN_APP' = any(channels)),
  status                   text not null default 'DRAFT' check (status in ('DRAFT', 'SCHEDULED', 'PUBLISHED', 'WITHDRAWN')),
  scheduled_for            timestamptz,
  schedule_error_code      text,
  authored_as              text not null default 'MANAGER' check (authored_as in ('MANAGER', 'TEACHER')),
  revision                 int not null default 1,
  published_at             timestamptz,
  recipient_count          int,
  delivery_completed_at    timestamptz,
  withdrawn_at             timestamptz,
  withdrawn_by             uuid references users,
  withdrawal_reason        text check (withdrawal_reason is null or char_length(withdrawal_reason) <= 500),
  deleted_at               timestamptz,
  unique (id, school_id),
  check ((status = 'SCHEDULED') = (scheduled_for is not null and status = 'SCHEDULED')),
  check (status not in ('PUBLISHED', 'WITHDRAWN') or published_at is not null),
  check ((status = 'WITHDRAWN') = (withdrawn_at is not null)),
  -- std
  created_at               timestamptz not null default now(),
  updated_at               timestamptz not null default now(),
  version                  int not null default 0
);
create index ix_announcements_school_published on announcements (school_id, published_at desc, id desc)
  where status in ('PUBLISHED', 'WITHDRAWN') and deleted_at is null;
create index ix_announcements_author on announcements (author_id, created_at desc, id desc) where deleted_at is null;
create index ix_announcements_school_status on announcements (school_id, status, created_at desc, id desc)
  where deleted_at is null;
create index ix_announcements_due on announcements (scheduled_for, id) where status = 'SCHEDULED' and deleted_at is null;
create index ix_announcements_undelivered on announcements (published_at, id)
  where status = 'PUBLISHED' and delivery_completed_at is null;
create index ix_announcements_withdrawn_by on announcements (withdrawn_by) where withdrawn_by is not null;
create index ix_announcements_language on announcements (body_language_code) where body_language_code is not null;

create table announcement_audiences (            -- explicit scope (§125); one row per audience, USER rows one per person
  id                  uuid primary key default uuidv7(),
  announcement_id     uuid not null references announcements on delete cascade,
  audience_type       text not null check (audience_type in ('SCHOOL', 'CAMPUS', 'GRADE_LEVEL', 'CLASS', 'USER')),
  campus_id           uuid references campuses,
  grade_level_id      uuid references grade_levels,
  class_id            uuid references classes,
  user_id             uuid references users,
  recipient_roles     text[] not null default '{GUARDIAN}'
                        check (recipient_roles <@ array['GUARDIAN', 'STUDENT', 'STAFF'] and cardinality(recipient_roles) > 0),
  check ((audience_type = 'SCHOOL' and campus_id is null and grade_level_id is null and class_id is null and user_id is null)
      or (audience_type = 'CAMPUS' and campus_id is not null and grade_level_id is null and class_id is null and user_id is null)
      or (audience_type = 'GRADE_LEVEL' and grade_level_id is not null and campus_id is null and class_id is null and user_id is null)
      or (audience_type = 'CLASS' and class_id is not null and campus_id is null and grade_level_id is null and user_id is null)
      or (audience_type = 'USER' and user_id is not null and campus_id is null and grade_level_id is null and class_id is null))
);
create index ix_announcement_audiences_announcement on announcement_audiences (announcement_id);
create index ix_announcement_audiences_class on announcement_audiences (class_id) where class_id is not null;
create index ix_announcement_audiences_grade on announcement_audiences (grade_level_id) where grade_level_id is not null;
create index ix_announcement_audiences_campus on announcement_audiences (campus_id) where campus_id is not null;
create index ix_announcement_audiences_user on announcement_audiences (user_id) where user_id is not null;

-- Snapshot taken at publish, so later roster changes don't rewrite who was told
create table announcement_recipients (
  id                  uuid primary key default uuidv7(),
  announcement_id     uuid not null,
  school_id           uuid not null,
  user_id             uuid not null references users,
  recipient_role      text not null check (recipient_role in ('GUARDIAN', 'STUDENT', 'STAFF')),
  via_student_id      uuid references student_profiles,  -- guardian reached through this child (the oldest link)
  resolved_at         timestamptz not null default now(),
  notified_at         timestamptz,                       -- the in-app notification exists
  notification_id     uuid,                              -- its inbox row (no foreign key: the inbox may be purged)
  channels_not_before timestamptz,                       -- email/SMS of a non-urgent announcement wait for quiet hours
  channels_claimed_at timestamptz,                       -- a sender took the row (stuck rows are recovered after 15 minutes)
  email_status        text check (email_status is null or email_status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
  email_reason        text,
  email_sent_at       timestamptz,
  sms_status          text check (sms_status is null or sms_status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
  sms_reason          text,
  sms_at              timestamptz,                       -- when the message was reserved/sent (daily cap)
  sms_adapter         text,
  sms_provider_ref    text,
  foreign key (announcement_id, school_id) references announcements (id, school_id),
  unique (announcement_id, user_id)
);
create index ix_announcement_recipients_undelivered on announcement_recipients (announcement_id, id)
  where notified_at is null;
create index ix_announcement_recipients_user on announcement_recipients (user_id, announcement_id);
create index ix_announcement_recipients_via on announcement_recipients (via_student_id) where via_student_id is not null;
create index ix_announcement_recipients_email on announcement_recipients (announcement_id, id)
  where email_status in ('PENDING', 'SENDING');
create index ix_announcement_recipients_sms on announcement_recipients (announcement_id, id)
  where sms_status in ('PENDING', 'SENDING');
create index ix_announcement_recipients_sms_day on announcement_recipients (school_id, sms_at)
  where sms_status in ('PENDING', 'SENDING', 'SENT');
create index ix_announcement_recipients_school on announcement_recipients (school_id);

create table announcement_acknowledgements (     -- read ≠ delivered (§125); also for readers outside the snapshot
  announcement_id         uuid not null references announcements on delete cascade,
  user_id                 uuid not null references users,
  school_id               uuid not null references schools,
  first_read_at           timestamptz,
  acknowledged_at         timestamptz,
  acknowledged_revision   int,
  primary key (announcement_id, user_id),
  check ((acknowledged_at is null) = (acknowledged_revision is null))
);
create index ix_announcement_acks_user on announcement_acknowledgements (user_id, announcement_id);
create index ix_announcement_acks_school on announcement_acknowledgements (school_id);

create table announcement_revisions (            -- edit audit history: version N before the edit that replaced it
  id                  uuid primary key default uuidv7(),
  announcement_id     uuid not null references announcements on delete cascade,
  revision            int not null,
  title               text not null,
  body                text not null,
  translations        jsonb not null default '{}',
  material            boolean not null default false,
  change_note         text check (change_note is null or char_length(change_note) <= 300),
  edited_by           uuid not null references users,
  edited_at           timestamptz not null default now(),
  unique (announcement_id, revision)
);
create index ix_announcement_revisions_editor on announcement_revisions (edited_by);

create table announcement_notices (              -- "tell the recipients again": update, reminder, withdrawal
  id                  uuid primary key default uuidv7(),
  announcement_id     uuid not null,
  school_id           uuid not null,
  kind                text not null check (kind in ('UPDATED', 'REMINDER', 'WITHDRAWN')),
  revision            int,
  requested_by        uuid not null references users,
  requested_at        timestamptz not null default now(),
  targeted            int not null default 0,        -- recipients when it was requested
  completed_at        timestamptz,
  foreign key (announcement_id, school_id) references announcements (id, school_id)
);
create index ix_announcement_notices_announcement on announcement_notices (announcement_id, kind, requested_at desc);
create index ix_announcement_notices_open on announcement_notices (requested_at, id) where completed_at is null;
create index ix_announcement_notices_requested_by on announcement_notices (requested_by);

create table communication_preferences (         -- opt-outs of the optional channels (push and in-app: notification preferences)
  user_id             uuid primary key references users,
  email_opt_out       boolean not null default false,
  sms_opt_out         boolean not null default false,
  updated_at          timestamptz not null default now()
);

create table sms_opt_outs (                      -- numbers that must not receive SMS (the provider's STOP, or the user's choice)
  phone_e164          text primary key check (phone_e164 ~ '^\+[0-9]{6,15}$'),
  source              text not null check (source in ('USER', 'PROVIDER')),
  created_at          timestamptz not null default now()
);

-- Notification extensions: types are data (R-26)
alter table notification_types
  add column defer_in_quiet_hours boolean not null default false,
  add column urgent_overrides_preferences boolean not null default false;

-- A push held back until the end of the recipient's quiet hours is QUEUED with not_before set.
alter table notification_deliveries add column not_before timestamptz;
create index ix_deliveries_deferred on notification_deliveries (not_before) where status = 'QUEUED' and not_before is not null;
