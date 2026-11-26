-- M11a · calendar and events (Database_Design §4.14): native events (R-17), their participants and RSVPs, per-occurrence
-- overrides of recurring events, and the reminders already sent.
--
-- Deviations from the design doc (also noted there):
--   * events.scope_type adds INVITEES (visible only to the people invited: parent meetings, see below); COURSE is allowed
--     by the CHECK but unsupported by the API and has no FK until the courses table exists (P3). PERSONAL events belong to
--     a user, not to a school (school_id is null).
--   * events carries every tenant FK as a composite (x, school_id) like the other school-owned tables.
--   * All-day events are calendar dates in the event's time zone: start_date/end_date (inclusive) are set exactly when
--     all_day is true, and start_at/end_at hold the instants of the start of the first day and of the day after the last
--     one (end exclusive), so every window query works on instants whatever the kind of event.
--   * PARENT_MEETING events have scope INVITEES and regarding_student_id on the event itself (not on the participant row):
--     the participants are the organiser and the guardians resolved at creation; guardians are re-checked against
--     student_guardians whenever the event is read.
--   * recurrence_rule is an RFC 5545 RRULE subset without EXDATE: cancelled occurrences are event_overrides rows with
--     status CANCELLED (the same thing); recurrence_ends_at is the end of the last occurrence of a finite series (query
--     pruning), null for an endless one. RSVP is not available on recurring events.
--   * event_participants adds invited_at and drops regarding_student_id (see above); event_overrides and event_reminders
--     are new.

create table events (
  id                   uuid primary key default uuidv7(),
  scope_type           text not null check (scope_type in ('SCHOOL', 'CAMPUS', 'GRADE_LEVEL', 'CLASS', 'COURSE', 'PERSONAL', 'INVITEES')),
  school_id            uuid references schools,
  campus_id            uuid,
  grade_level_id       uuid,
  class_id             uuid,
  course_id            uuid,                       -- P3: no FK until the courses table exists; the API does not use it
  owner_id             uuid references users,      -- PERSONAL
  event_type           text not null check (event_type in ('SCHOOL_EVENT', 'CLASS_EVENT', 'EXAM', 'PARENT_MEETING', 'OTHER')),
  title                text not null check (char_length(title) between 1 and 200),
  description          text check (description is null or char_length(description) <= 5000),
  location             text check (location is null or char_length(location) <= 200),
  start_at             timestamptz not null,
  end_at               timestamptz not null,
  timezone             text not null,
  all_day              boolean not null default false,
  start_date           date,
  end_date             date,
  recurrence_rule      text check (recurrence_rule is null or char_length(recurrence_rule) <= 300),
  recurrence_ends_at   timestamptz,
  rsvp_enabled         boolean not null default false,
  regarding_student_id uuid references student_profiles,
  status               text not null default 'SCHEDULED' check (status in ('SCHEDULED', 'CANCELLED')),
  cancelled_at         timestamptz,
  cancelled_by         uuid references users,
  cancellation_reason  text check (cancellation_reason is null or char_length(cancellation_reason) <= 500),
  created_by           uuid not null references users,
  deleted_at           timestamptz,
  foreign key (campus_id, school_id) references campuses (id, school_id),
  foreign key (grade_level_id, school_id) references grade_levels (id, school_id),
  foreign key (class_id, school_id) references classes (id, school_id),
  unique (id, school_id),
  check (end_at >= start_at),
  check (all_day = (start_date is not null) and all_day = (end_date is not null)),
  check (not all_day or end_date >= start_date),
  check ((status = 'CANCELLED') = (cancelled_at is not null)),
  check (recurrence_ends_at is null or recurrence_rule is not null),
  check (not rsvp_enabled or recurrence_rule is null),
  check ((scope_type = 'PERSONAL') = (owner_id is not null)),
  check ((scope_type = 'PERSONAL') = (school_id is null)),
  check (scope_type <> 'CAMPUS' or (campus_id is not null and grade_level_id is null and class_id is null)),
  check (scope_type <> 'GRADE_LEVEL' or (grade_level_id is not null and campus_id is null and class_id is null)),
  check (scope_type <> 'CLASS' or (class_id is not null and campus_id is null and grade_level_id is null)),
  check (scope_type <> 'SCHOOL' or (campus_id is null and grade_level_id is null and class_id is null)),
  check ((scope_type = 'INVITEES') = (event_type = 'PARENT_MEETING')),
  check ((event_type = 'PARENT_MEETING') = (regarding_student_id is not null)),
  check (event_type <> 'PARENT_MEETING' or (recurrence_rule is null and rsvp_enabled)),
  check (scope_type <> 'PERSONAL' or not rsvp_enabled),
  -- std
  created_at           timestamptz not null default now(),
  updated_at           timestamptz not null default now(),
  version              int not null default 0
);
-- one-off events by window, recurring series by their first start; both per school, plus personal events by owner
create index ix_events_school_window on events (school_id, start_at, id)
  where deleted_at is null and recurrence_rule is null and school_id is not null;
create index ix_events_school_series on events (school_id, start_at, id)
  where deleted_at is null and recurrence_rule is not null and school_id is not null;
create index ix_events_owner on events (owner_id, start_at, id) where deleted_at is null and owner_id is not null;
create index ix_events_class on events (class_id) where class_id is not null;
create index ix_events_campus on events (campus_id) where campus_id is not null;
create index ix_events_grade on events (grade_level_id) where grade_level_id is not null;
create index ix_events_student on events (regarding_student_id) where regarding_student_id is not null;
create index ix_events_creator on events (created_by);
create index ix_events_reminder on events (start_at) where deleted_at is null and status = 'SCHEDULED';

create table event_participants (                -- invitations and RSVPs
  event_id             uuid not null references events on delete cascade,
  user_id              uuid not null references users,
  role                 text not null default 'ATTENDEE' check (role in ('ORGANIZER', 'ATTENDEE')),
  rsvp_status          text check (rsvp_status is null or rsvp_status in ('PENDING', 'YES', 'NO', 'MAYBE')),
  responded_at         timestamptz,
  invited_at           timestamptz not null default now(),
  primary key (event_id, user_id),
  check (rsvp_status is distinct from 'PENDING' or responded_at is null)
);
create index ix_event_participants_user on event_participants (user_id, event_id);

-- One row per changed occurrence of a recurring event, keyed by the instant the occurrence has in the series (its
-- original start; the start of the first day in the event's zone for all-day events). CANCELLED is the EXDATE of the
-- series; MODIFIED carries the fields that differ (null = unchanged).
create table event_overrides (
  event_id             uuid not null references events on delete cascade,
  occurrence_start     timestamptz not null,
  status               text not null check (status in ('CANCELLED', 'MODIFIED')),
  title                text check (title is null or char_length(title) between 1 and 200),
  description          text check (description is null or char_length(description) <= 5000),
  location             text check (location is null or char_length(location) <= 200),
  start_at             timestamptz,
  end_at               timestamptz,
  start_date           date,
  end_date             date,
  reason               text check (reason is null or char_length(reason) <= 500),
  updated_by           uuid not null references users,
  updated_at           timestamptz not null default now(),
  primary key (event_id, occurrence_start),
  check (status = 'MODIFIED' or (title is null and description is null and location is null and start_at is null
                                  and end_at is null and start_date is null and end_date is null))
);

-- Reminders already sent: one per person per occurrence (the job's idempotency)
create table event_reminders (
  event_id             uuid not null references events on delete cascade,
  occurrence_start     timestamptz not null,
  user_id              uuid not null references users,
  sent_at              timestamptz not null default now(),
  primary key (event_id, occurrence_start, user_id)
);
create index ix_event_reminders_sent on event_reminders (sent_at);
