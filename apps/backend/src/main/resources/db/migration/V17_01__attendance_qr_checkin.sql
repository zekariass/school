-- M7e-a · attendance QR check-in (Database_Design §4.34): credentials (a static QR), rotating QR sessions, the append-only
-- ledger of every check-in attempt, and the links from rolls and staff records to the check-in that produced them.
--
-- Deviations from the design doc (also noted there, "As built (M7e-a)"):
--   * attendance_credentials: no `hint` (the last four characters of a 128-bit token would give away 24 bits of it, and the
--     help desk can work from the id and the dates) and no `id_card_id` (the card table is M35, which adds the column with
--     the table it references). `key_id` names the pepper key the hash was made with (rotation overlap); `expires_at` is
--     required; `revoked_by` and `suspected_copy_at` (the SUSPECTED_COPY alert) are new. The student is tied to the school by
--     the service (school_students has no (student_id, school_id) key to point at), the staff member by the composite key.
--   * attendance_qr_sessions: a STUDENT session carries the class, the school-local date and the slot of its roll instead of an
--     attendance_session_id: the roll row is created by the first accepted check-in, so opening a code never leaves an empty
--     roll behind (which the dashboard would count as "taken"). STAFF sessions carry the campus (null: the whole school) and
--     the school-local day. `closed_at`/`closed_by` and the late threshold `late_after_min` (null: nobody is LATE) are kept.
--   * attendance_checkins: also keeps the class, date and slot a student check-in was for, the mark it produced
--     (marked_status, minutes_late), and `scanner_user_id` is required (the student's own user for a rotating code). Unknown
--     credentials are not rows (there is no subject to name): they are audited without the token. The table is append-only:
--     a trigger refuses UPDATE and DELETE. A teacher's removal of an entry is its own append-only row in
--     attendance_checkin_removals.
--   * attendance_records.source also allows QR_ROTATING and QR_STATIC, and records point to their check-in (checkin_id);
--     staff_attendance_records gets the same checkin_id.

alter table attendance_records drop constraint attendance_records_source_check;
alter table attendance_records add constraint attendance_records_source_check
  check (source in ('ONLINE', 'OFFLINE_SYNC', 'IMPORT', 'QR_ROTATING', 'QR_STATIC'));

alter table attendance_sessions add constraint uq_attendance_sessions_id_school unique (id, school_id);

create table attendance_credentials (            -- a student's or staff member's static QR (on an ID card)
  id                uuid primary key default uuidv7(),
  school_id         uuid not null references schools,
  student_id        uuid references student_profiles,
  staff_id          uuid,
  token_hash        text not null unique,        -- HMAC-SHA256 of the token with a server pepper key; the token is never stored
  key_id            text not null,               -- the pepper key of token_hash (rotation overlap)
  issued_by         uuid not null references users,
  issued_at         timestamptz not null,
  expires_at        timestamptz not null,
  revoked_at        timestamptz,
  revoked_by        uuid references users,
  revoked_reason    text check (revoked_reason in ('LOST', 'REPLACED', 'LEFT_SCHOOL', 'SUSPECTED_COPY', 'OTHER')),
  last_used_at      timestamptz,
  suspected_copy_at timestamptz,                 -- two scanners read it within a minute: an alert for the school
  check (num_nonnulls(student_id, staff_id) = 1),
  check ((revoked_at is null) = (revoked_reason is null)),
  check (revoked_at is null or revoked_by is not null),
  check (expires_at > issued_at),
  foreign key (staff_id, school_id) references school_staff (id, school_id),
  unique (id, school_id)
);
create unique index ux_attendance_credential_active_student on attendance_credentials (student_id)
  where student_id is not null and revoked_at is null;
create unique index ux_attendance_credential_active_staff on attendance_credentials (staff_id)
  where staff_id is not null and revoked_at is null;
create index ix_attendance_credentials_school on attendance_credentials (school_id, issued_at desc, id);
create index ix_attendance_credentials_student on attendance_credentials (student_id, issued_at desc) where student_id is not null;
create index ix_attendance_credentials_staff on attendance_credentials (staff_id, issued_at desc) where staff_id is not null;
create index ix_attendance_credentials_suspected on attendance_credentials (school_id, suspected_copy_at desc)
  where suspected_copy_at is not null and revoked_at is null;
create index ix_attendance_credentials_issued_by on attendance_credentials (issued_by);
create index ix_attendance_credentials_revoked_by on attendance_credentials (revoked_by) where revoked_by is not null;

create table attendance_qr_sessions (            -- a rotating code shown by a teacher (a roll) or a manager (a staff day)
  id                uuid primary key default uuidv7(),
  school_id         uuid not null references schools,
  subject_kind      text not null check (subject_kind in ('STUDENT', 'STAFF')),
  class_id          uuid,                        -- STUDENT: the class whose roll the code belongs to
  attendance_date   date not null,               -- the school-local day (class or campus zone, else school zone)
  slot              text check (slot ~ '^(DAY|AM|PM|P[1-8])$'), -- STUDENT: the roll's slot
  subject_id        uuid,                        -- STUDENT, periods only: the subject the roll is created with
  campus_id         uuid,                        -- STAFF: the campus whose staff may scan (null: the whole school)
  opened_by         uuid not null references users,
  opens_at          timestamptz not null,
  closes_at         timestamptz not null,
  rotation_s        smallint not null check (rotation_s between 10 and 120),
  late_after_min    smallint check (late_after_min is null or late_after_min between 0 and 600),
  status            text not null default 'OPEN' check (status in ('OPEN', 'CLOSED')),
  closed_at         timestamptz,
  closed_by         uuid references users,
  check (closes_at > opens_at),
  check ((subject_kind = 'STUDENT') = (class_id is not null and slot is not null)),
  check (subject_kind = 'STAFF' or campus_id is null),
  check (subject_kind = 'STUDENT' or subject_id is null),
  check ((status = 'CLOSED') = (closed_at is not null)),
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (campus_id, school_id) references campuses (id, school_id),
  foreign key (subject_id, school_id) references subjects (id, school_id),
  unique (id, school_id)
);
-- The code shown is HMAC(server key, qr_session_id | floor(time / rotation_s)); no secret per session is stored.
create index ix_attendance_qr_sessions_class on attendance_qr_sessions (class_id, attendance_date) where class_id is not null;
create index ix_attendance_qr_sessions_school on attendance_qr_sessions (school_id, opens_at desc, id);
create index ix_attendance_qr_sessions_opened_by on attendance_qr_sessions (opened_by);
create index ix_attendance_qr_sessions_subject on attendance_qr_sessions (subject_id) where subject_id is not null;
create index ix_attendance_qr_sessions_campus on attendance_qr_sessions (campus_id) where campus_id is not null;
create index ix_attendance_qr_sessions_closed_by on attendance_qr_sessions (closed_by) where closed_by is not null;

create table attendance_checkins (               -- every attempt that names a person, accepted or not (append-only)
  id                    uuid primary key default uuidv7(),
  school_id             uuid not null references schools,
  subject_type          text not null check (subject_type in ('STUDENT', 'STAFF')),
  student_id            uuid references student_profiles,
  staff_id              uuid,
  class_id              uuid,                    -- STUDENT: the class the check-in was for
  attendance_date       date,                    -- the school-local day it was for
  slot                  text,
  attendance_session_id uuid,                    -- the roll, once it exists (always for an ACCEPTED student check-in)
  method                text not null check (method in ('QR_ROTATING', 'QR_STATIC')),
  scanner_user_id       uuid not null references users, -- the teacher or manager who scanned a card; the student's or staff member's own user for a rotating code
  credential_id         uuid,
  qr_session_id         uuid,
  result                text not null check (result in ('ACCEPTED', 'REJECTED_EXPIRED', 'REJECTED_NOT_ENROLLED', 'REJECTED_DUPLICATE',
                                                        'REJECTED_REVOKED', 'REJECTED_WINDOW', 'REJECTED_CONFLICT')),
  marked_status         text check (marked_status in ('PRESENT', 'LATE')),
  minutes_late          smallint check (minutes_late is null or (marked_status = 'LATE' and minutes_late between 1 and 600)),
  checked_in_at         timestamptz not null,    -- server time
  client_ts             timestamptz,             -- the device's time of an offline scan (never trusted)
  client_op_id          uuid,
  check (num_nonnulls(student_id, staff_id) = 1),
  check ((subject_type = 'STUDENT') = (student_id is not null)),
  check ((method = 'QR_ROTATING') = (qr_session_id is not null)),
  check (method <> 'QR_STATIC' or credential_id is not null),
  check ((result = 'ACCEPTED') = (marked_status is not null)),
  foreign key (staff_id, school_id) references school_staff (id, school_id),
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (attendance_session_id, school_id) references attendance_sessions (id, school_id),
  foreign key (credential_id, school_id) references attendance_credentials (id, school_id),
  foreign key (qr_session_id, school_id) references attendance_qr_sessions (id, school_id),
  unique (id, school_id),
  unique (school_id, client_op_id)
);
-- One accepted check-in per person per rotating session (also when a teacher removed it: it stays in the ledger).
create unique index ux_attendance_checkin_accepted_once on attendance_checkins (qr_session_id, (coalesce(student_id, staff_id)))
  where result = 'ACCEPTED' and qr_session_id is not null;
create index ix_attendance_checkins_session on attendance_checkins (qr_session_id, checked_in_at) where qr_session_id is not null;
create index ix_attendance_checkins_credential on attendance_checkins (credential_id, checked_in_at desc) where credential_id is not null;
create index ix_attendance_checkins_student on attendance_checkins (student_id, checked_in_at desc) where student_id is not null;
create index ix_attendance_checkins_staff on attendance_checkins (staff_id, checked_in_at desc) where staff_id is not null;
create index ix_attendance_checkins_school on attendance_checkins (school_id, checked_in_at desc, id);
create index ix_attendance_checkins_scanner on attendance_checkins (scanner_user_id, checked_in_at desc);
create index ix_attendance_checkins_class on attendance_checkins (class_id) where class_id is not null;
create index ix_attendance_checkins_roll on attendance_checkins (attendance_session_id) where attendance_session_id is not null;

create function attendance_checkins_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'attendance_checkins is append-only';
end;
$$;
create trigger tr_attendance_checkins_append_only before update or delete on attendance_checkins
  for each row execute function attendance_checkins_append_only();

create table attendance_checkin_removals (       -- a teacher took one accepted rotating check-in off the roll (append-only)
  id                uuid primary key default uuidv7(),
  school_id         uuid not null references schools,
  checkin_id        uuid not null,
  removed_by        uuid not null references users,
  removed_at        timestamptz not null,
  foreign key (checkin_id, school_id) references attendance_checkins (id, school_id),
  unique (checkin_id)
);
create index ix_attendance_checkin_removals_school on attendance_checkin_removals (school_id, removed_at desc);
create index ix_attendance_checkin_removals_by on attendance_checkin_removals (removed_by);

create function attendance_checkin_removals_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'attendance_checkin_removals is append-only';
end;
$$;
create trigger tr_attendance_checkin_removals_append_only before update or delete on attendance_checkin_removals
  for each row execute function attendance_checkin_removals_append_only();

alter table attendance_records add column checkin_id uuid references attendance_checkins;
create index ix_attendance_records_checkin on attendance_records (checkin_id) where checkin_id is not null;

alter table staff_attendance_records add column checkin_id uuid references attendance_checkins;
create index ix_staff_attendance_records_checkin on staff_attendance_records (checkin_id) where checkin_id is not null;
