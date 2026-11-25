-- M3a · classes and memberships (Database_Design §4.5) and student login codes (§4.3, R-31).
--
-- Deviations from the design doc (also noted there):
--   * Enumerations get CHECK constraints (DB §1). A membership row has left_at exactly when it is no longer
--     PENDING/ACTIVE.
--   * student_login_codes.class_id references classes through the composite (class_id, school_id) so a code
--     cannot point at another tenant's class; its school_id must match the student's enrollment (service).
--   * auth_sessions.auth_method already allows LOGIN_CODE (V1_05).

create table classes (
  id               uuid primary key default uuidv7(),
  school_id        uuid not null references schools,
  campus_id        uuid,
  academic_year_id uuid not null,
  grade_level_id   uuid,                          -- null for mixed-grade clubs
  name             text not null,                 -- "8A"
  class_type       text not null default 'HOMEROOM' check (class_type in ('HOMEROOM', 'SUBJECT', 'CLUB', 'OTHER')),
  icon             text,
  color            text check (color is null or color ~ '^#[0-9a-fA-F]{6}$'),
  status           text not null default 'ACTIVE' check (status in ('ACTIVE', 'ARCHIVED')),
  archived_at      timestamptz,
  external_ref     text,
  created_by       uuid references users,
  foreign key (campus_id, school_id) references campuses (id, school_id),
  foreign key (academic_year_id, school_id) references academic_years (id, school_id),
  foreign key (grade_level_id, school_id) references grade_levels (id, school_id),
  check ((status = 'ARCHIVED') = (archived_at is not null)),
  unique (id, school_id),
  -- std
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  version          int not null default 0
);
create index ix_classes_school on classes (school_id, name, id);
create index ix_classes_campus on classes (campus_id) where campus_id is not null;
create index ix_classes_year on classes (academic_year_id);
create index ix_classes_grade on classes (grade_level_id) where grade_level_id is not null;
create index ix_classes_creator on classes (created_by) where created_by is not null;

create table class_subjects (
  class_id        uuid not null,
  subject_id      uuid not null,
  school_id       uuid not null,
  primary key (class_id, subject_id),
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (subject_id, school_id) references subjects (id, school_id)
);
create index ix_class_subjects_subject on class_subjects (subject_id);

create table class_teachers (                    -- §10 + R-07
  id              uuid primary key default uuidv7(),
  class_id        uuid not null,
  school_id       uuid not null,
  teacher_id      uuid not null references teacher_profiles,
  role            text not null check (role in ('LEAD', 'CO_TEACHER', 'SUBJECT_TEACHER', 'ASSISTANT')),
  subject_id      uuid,                          -- required when role = SUBJECT_TEACHER
  status          text not null default 'ACTIVE' check (status in ('PENDING', 'ACTIVE', 'FORMER', 'REMOVED')),
  joined_at       timestamptz not null default now(),
  left_at         timestamptz,
  added_by        uuid references users,
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (subject_id, school_id) references subjects (id, school_id),
  check (role <> 'SUBJECT_TEACHER' or subject_id is not null),
  check ((status in ('PENDING', 'ACTIVE')) = (left_at is null)),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_class_teacher_active on class_teachers
  (class_id, teacher_id, coalesce(subject_id, '00000000-0000-0000-0000-000000000000')) where status in ('PENDING', 'ACTIVE');
create index ix_class_teachers_teacher on class_teachers (teacher_id) where status = 'ACTIVE';
create index ix_class_teachers_class on class_teachers (class_id);
create index ix_class_teachers_subject on class_teachers (subject_id) where subject_id is not null;

create table class_students (                    -- §10, §12
  id              uuid primary key default uuidv7(),
  class_id        uuid not null,
  school_id       uuid not null,
  student_id      uuid not null references student_profiles,
  status          text not null default 'ACTIVE' check (status in ('PENDING', 'ACTIVE', 'FORMER', 'REMOVED')),
  joined_at       timestamptz not null default now(),
  left_at         timestamptz,
  left_reason     text check (left_reason is null
                    or left_reason in ('TRANSFERRED_CLASS', 'LEFT_SCHOOL', 'PROMOTED', 'REMOVED')),
  added_by        uuid references users,
  foreign key (class_id, school_id) references classes (id, school_id),
  check ((status in ('PENDING', 'ACTIVE')) = (left_at is null)),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_class_student_active on class_students (class_id, student_id) where status in ('PENDING', 'ACTIVE');
create index ix_class_students_student on class_students (student_id) where status = 'ACTIVE';
create index ix_class_students_class on class_students (class_id, status);

-- Saved groups from the group maker (§126)
create table student_groups (
  id              uuid primary key default uuidv7(),
  class_id        uuid not null,
  school_id       uuid not null,
  name            text not null,
  created_by      uuid not null references users,
  foreign key (class_id, school_id) references classes (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_student_groups_class on student_groups (class_id, id);
create index ix_student_groups_creator on student_groups (created_by);

create table student_group_members (
  group_id        uuid references student_groups on delete cascade,
  student_id      uuid references student_profiles,
  primary key (group_id, student_id)
);
create index ix_student_group_members_student on student_group_members (student_id);

create table student_login_codes (               -- ClassDojo-style QR / text code (R-31)
  id              uuid primary key default uuidv7(),
  student_id      uuid not null references student_profiles,
  school_id       uuid not null references schools,
  class_id        uuid,                          -- class sheet the code was printed for
  code_type       text not null check (code_type in ('QR', 'TEXT')),
  code_hash       text not null unique,          -- HMAC-SHA256(pepper, code); the code itself is never stored
  issued_by       uuid not null references users,
  expires_at      timestamptz,
  revoked_at      timestamptz,
  last_used_at    timestamptz,
  created_at      timestamptz not null default now(),
  foreign key (class_id, school_id) references classes (id, school_id)
);
create index ix_login_codes_student on student_login_codes (student_id) where revoked_at is null;
create index ix_login_codes_class on student_login_codes (class_id) where class_id is not null;
create index ix_login_codes_school on student_login_codes (school_id);
create index ix_login_codes_issuer on student_login_codes (issued_by);
