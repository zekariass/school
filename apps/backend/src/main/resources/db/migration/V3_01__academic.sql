-- M3a · academic structure, part 1 (Database_Design §4.5): academic years and periods, grade levels, subjects.
-- Classes and memberships are in V3_03; people in V3_02.
--
-- Deviations from the design doc (also noted there):
--   * grade_levels.academic_level_id and subjects.curriculum_subject_id are omitted: the curriculum tables arrive
--     in P2 (M16), which adds the columns and their FKs.
--   * Enumerations get CHECK constraints (DB §1). Overlapping periods of a year are refused by an exclusion
--     constraint (btree_gist) as well as by the service, so a race cannot create one.

create table academic_years (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  name            text not null,                 -- "2018 E.C. / 2025-26"
  starts_on       date not null,
  ends_on         date not null,
  status          text not null default 'PLANNED' check (status in ('PLANNED', 'ACTIVE', 'CLOSED')),
  is_current      boolean not null default false,
  check (ends_on > starts_on),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_academic_year_current on academic_years (school_id) where is_current;
create index ix_academic_years_school on academic_years (school_id, starts_on desc);

create table academic_periods (
  id               uuid primary key default uuidv7(),
  academic_year_id uuid not null,
  school_id        uuid not null,
  name             text not null,                -- Term 1, Semester 2
  period_type      text not null check (period_type in ('TERM', 'SEMESTER', 'QUARTER', 'CUSTOM')),
  sequence         smallint not null check (sequence > 0),
  starts_on        date not null,
  ends_on          date not null,
  check (ends_on >= starts_on),
  foreign key (academic_year_id, school_id) references academic_years (id, school_id),
  constraint uq_academic_periods_sequence unique (academic_year_id, sequence),
  unique (id, school_id),
  constraint ex_academic_periods_no_overlap exclude using gist (academic_year_id with =, daterange(starts_on, ends_on, '[]') with &&),
  -- std
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  version          int not null default 0
);
create index ix_academic_periods_year on academic_periods (academic_year_id, sequence);

create table grade_levels (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  name            text not null,                 -- "Grade 8", "KG 2"
  code            text,
  sequence        smallint not null,
  is_active       boolean not null default true,
  unique (id, school_id),
  constraint uq_grade_levels_sequence unique (school_id, sequence),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);

create table subjects (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  name            text not null,
  code            text,
  color           text check (color is null or color ~ '^#[0-9a-fA-F]{6}$'),
  is_active       boolean not null default true,
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_subjects_school on subjects (school_id, name);
create unique index ux_subjects_code on subjects (school_id, lower(code)) where code is not null;
