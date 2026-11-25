-- M3a · people and relationships (Database_Design §4.4): student, teacher profiles, school staff, school students.
--
-- Deviations from the design doc (also noted there):
--   * guardian_profiles, student_guardians and invitations are omitted: guardian linking and invitations arrive
--     with M5. Staff are provisioned directly (M3a) instead of through invitations.
--   * tutor_profiles is omitted (P4).
--   * student_profiles.avatar_config stays a jsonb document (shape owned by the clients).
--   * school_students gets unique (id, school_id) like school_staff, for composite FKs later.
--   * Enumerations get CHECK constraints (DB §1).

create table student_profiles (
  user_id                 uuid primary key references users,
  avatar_config           jsonb check (avatar_config is null or jsonb_typeof(avatar_config) = 'object'),
  learning_language_code  text references languages,
  created_at              timestamptz not null default now()
);
create index ix_student_profiles_language on student_profiles (learning_language_code);

create table teacher_profiles (                  -- school-world identity; separate from tutor (§40)
  user_id                 uuid primary key references users,
  bio                     text,
  years_experience        smallint check (years_experience is null or years_experience >= 0),
  created_at              timestamptz not null default now()
);

-- Employment / staff membership in a school (R-02)
create table school_staff (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  user_id         uuid not null references users,
  campus_id       uuid,
  staff_type      text not null check (staff_type in ('TEACHER', 'ADMINISTRATOR', 'SUPPORT_STAFF')),
  job_title       text,
  employee_number text,
  external_ref    text,                          -- SIS id (§125)
  status          text not null default 'ACTIVE' check (status in ('INVITED', 'ACTIVE', 'SUSPENDED', 'FORMER')),
  started_on      date,
  ended_on        date,
  foreign key (campus_id, school_id) references campuses (id, school_id),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_school_staff_active on school_staff (school_id, user_id) where status in ('INVITED', 'ACTIVE');
create index ix_school_staff_user on school_staff (user_id);
create index ix_school_staff_campus on school_staff (campus_id) where campus_id is not null;
create index ix_school_staff_school on school_staff (school_id, id);

-- Student enrollment in a school (R-01)
create table school_students (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  student_id      uuid not null references student_profiles,
  campus_id       uuid,
  student_number  text,
  external_ref    text,
  admission_on    date,
  status          text not null default 'ACTIVE'
                    check (status in ('PENDING', 'ACTIVE', 'TRANSFERRED', 'GRADUATED', 'WITHDRAWN')),
  left_on         date,
  left_reason     text,
  foreign key (campus_id, school_id) references campuses (id, school_id),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_school_student_active on school_students (school_id, student_id)
  where status in ('PENDING', 'ACTIVE');
create unique index ux_school_student_number on school_students (school_id, student_number)
  where student_number is not null;
create index ix_school_students_student on school_students (student_id);
create index ix_school_students_campus on school_students (campus_id) where campus_id is not null;
create index ix_school_students_school on school_students (school_id, id);
