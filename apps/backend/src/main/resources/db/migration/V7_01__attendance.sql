-- M7a · attendance (Database_Design §4.6): absence reports, roll-call sessions and per-student records.
--
-- Deviations from the design doc (also noted there):
--   * attendance_sessions: school_id is part of the composite (class_id, school_id) and (subject_id, school_id) keys;
--     (id, school_id, class_id, attendance_date) is unique so that records can carry the class and the date of their
--     session (denormalized, immutable) and still be tied to it by a composite foreign key. academic_period_id is left
--     out (nothing reads it yet); the std columns (created_at, updated_at, version) are added; status LOCKED exists, but
--     the lock is derived (end of the attendance date in the school's time zone plus attendance.lock_after_hours), so
--     rows stay SUBMITTED. client_op_id is the sync operation that created the session.
--   * attendance_records: school_id, class_id and attendance_date are copied from the session (composite FK) so that
--     reports and a student's history need no join to find a date; minutes_late only with status LATE; note at most 200
--     characters; device_id is the sync device that made the mark (null online), used to let a device's own later edit
--     supersede its earlier one; version is the optimistic-concurrency counter; a record is never deleted.
--   * absence_reports: unique (id, school_id); CANCELLED (the guardian withdrew it); decided_by/decided_at/decision_note
--     replace acknowledged_by/acknowledged_at (they also record a decline); reason_code is required and checked. The
--     note is health related and is read only by the reporter, the student's teachers and school attendance managers.
--   * Enumerations get CHECK constraints (DB §1).

create table absence_reports (                   -- a guardian tells the school ahead of time
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  student_id      uuid not null references student_profiles,
  reported_by     uuid not null references users,
  from_date       date not null,                 -- school-local calendar dates, inclusive
  to_date         date not null,
  reason_code     text not null check (reason_code in ('ILLNESS', 'FAMILY', 'APPOINTMENT', 'OTHER')),
  note            text check (note is null or char_length(note) <= 500),
  status          text not null default 'SUBMITTED' check (status in ('SUBMITTED', 'ACKNOWLEDGED', 'DECLINED', 'CANCELLED')),
  decided_by      uuid references users,
  decided_at      timestamptz,
  decision_note   text check (decision_note is null or char_length(decision_note) <= 200),
  check (to_date >= from_date and to_date - from_date <= 60),
  check ((status in ('ACKNOWLEDGED', 'DECLINED')) = (decided_by is not null and decided_at is not null)),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_absence_reports_student on absence_reports (student_id, from_date desc);
create index ix_absence_reports_school on absence_reports (school_id, status, from_date);
create index ix_absence_reports_reporter on absence_reports (reported_by);
create index ix_absence_reports_decider on absence_reports (decided_by) where decided_by is not null;

create table attendance_sessions (               -- one roll call
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  class_id        uuid not null,
  attendance_date date not null,                 -- school-local calendar date
  slot            text not null default 'DAY' check (slot ~ '^(DAY|AM|PM|P[1-8])$'),
  subject_id      uuid,
  taken_by        uuid not null references users,
  taken_at        timestamptz not null,
  status          text not null default 'SUBMITTED' check (status in ('DRAFT', 'SUBMITTED', 'LOCKED')),
  client_op_id    uuid,
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (subject_id, school_id) references subjects (id, school_id),
  unique (class_id, attendance_date, slot),
  unique (id, school_id, class_id, attendance_date),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_attendance_sessions_school_date on attendance_sessions (school_id, attendance_date);
create index ix_attendance_sessions_taken_by on attendance_sessions (taken_by);
create index ix_attendance_sessions_subject on attendance_sessions (subject_id) where subject_id is not null;

create table attendance_records (
  id                    uuid primary key default uuidv7(),
  attendance_session_id uuid not null,
  school_id             uuid not null,
  class_id              uuid not null,
  attendance_date       date not null,
  student_id            uuid not null references student_profiles,
  status                text not null check (status in ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED', 'LEFT_EARLY')),
  minutes_late          smallint check (minutes_late is null or (status = 'LATE' and minutes_late between 1 and 600)),
  note                  text check (note is null or char_length(note) <= 200),
  absence_report_id     uuid,
  recorded_by           uuid not null references users,
  recorded_at           timestamptz not null,    -- device time of the mark, clamped (never trusted for ordering)
  source                text not null default 'ONLINE' check (source in ('ONLINE', 'OFFLINE_SYNC', 'IMPORT')),
  device_id             uuid,
  client_op_id          uuid,
  foreign key (attendance_session_id, school_id, class_id, attendance_date)
    references attendance_sessions (id, school_id, class_id, attendance_date) on delete cascade,
  foreign key (absence_report_id, school_id) references absence_reports (id, school_id),
  unique (attendance_session_id, student_id),
  -- std
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  version               int not null default 0
);
create index ix_attendance_student_date on attendance_records (student_id, attendance_date desc, id);
create index ix_attendance_class_date on attendance_records (class_id, attendance_date);
create index ix_attendance_records_school_date on attendance_records (school_id, attendance_date);
create index ix_attendance_records_recorded_by on attendance_records (recorded_by);
create index ix_attendance_records_report on attendance_records (absence_report_id) where absence_report_id is not null;
