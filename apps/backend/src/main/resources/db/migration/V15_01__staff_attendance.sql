-- M7d-a · staff attendance (Database_Design §4.34, manual marking only; QR check-in is M7e).
--
-- Deviations from the design doc (also noted there):
--   * Only staff_attendance_records and staff_attendance_corrections are created. attendance_credentials,
--     attendance_qr_sessions and attendance_checkins belong to M7e, so staff_attendance_records has no checkin_id yet
--     (M7e adds it together with the table it references). method is checked MANUAL|QR_ROTATING|QR_STATIC; M7d only
--     writes MANUAL. check_in_at and check_out_at exist for M7e and stay null for manual marks.
--   * staff_id is tied to school_staff by the composite key (staff_id, school_id) (DB §1); the same for campus_id.
--     Corrections carry school_id and reference their record by (record_id, school_id).
--   * status and method are CHECKed; minutes_late only with LATE (1 to 600); note at most 200 characters.
--   * Corrections also keep the minutes before and after (old_minutes_late, new_minutes_late) so that a correction of
--     the lateness alone is traceable; reason is required (1 to 200 characters). The table is append-only: a trigger
--     refuses UPDATE and DELETE. The record row itself holds the current value and is updated by a correction.
--   * The std columns (created_at, updated_at, version) are added to records; version is the optimistic-concurrency
--     counter a correction must quote.
--   * Staff records carry no leave, pay or HR data (D14); EXCUSED is a status a manager sets, nothing more.

create table staff_attendance_records (          -- teachers and other staff, one row per day
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  campus_id       uuid,
  staff_id        uuid not null,
  attendance_date date not null,                 -- the school-local date (campus zone, else school zone)
  status          text not null check (status in ('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'EXCUSED')),
  check_in_at     timestamptz,
  check_out_at    timestamptz,
  minutes_late    smallint check (minutes_late is null or (status = 'LATE' and minutes_late between 1 and 600)),
  method          text not null default 'MANUAL' check (method in ('MANUAL', 'QR_ROTATING', 'QR_STATIC')),
  recorded_by     uuid not null references users,
  note            text check (note is null or char_length(note) <= 200),
  foreign key (staff_id, school_id) references school_staff (id, school_id),
  foreign key (campus_id, school_id) references campuses (id, school_id),
  unique (staff_id, attendance_date),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_staff_attendance_school_date on staff_attendance_records (school_id, attendance_date, id);
create index ix_staff_attendance_staff_date on staff_attendance_records (staff_id, attendance_date desc);
create index ix_staff_attendance_campus on staff_attendance_records (campus_id) where campus_id is not null;
create index ix_staff_attendance_recorded_by on staff_attendance_records (recorded_by);

create table staff_attendance_corrections (      -- append-only
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  record_id       uuid not null,
  old_status      text not null check (old_status in ('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'EXCUSED')),
  new_status      text not null check (new_status in ('PRESENT', 'ABSENT', 'LATE', 'HALF_DAY', 'EXCUSED')),
  old_minutes_late smallint,
  new_minutes_late smallint,
  reason          text not null check (char_length(reason) between 1 and 200),
  corrected_by    uuid not null references users,
  corrected_at    timestamptz not null default now(),
  foreign key (record_id, school_id) references staff_attendance_records (id, school_id)
);
create index ix_staff_attendance_corrections_record on staff_attendance_corrections (record_id, corrected_at, id);
create index ix_staff_attendance_corrections_by on staff_attendance_corrections (corrected_by);

create function staff_attendance_corrections_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'staff_attendance_corrections is append-only';
end;
$$;
create trigger tr_staff_attendance_corrections_append_only before update or delete on staff_attendance_corrections
  for each row execute function staff_attendance_corrections_append_only();
