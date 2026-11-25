-- M6a · classroom engagement (Database_Design §4.7, §4.8, §4.5): point categories, the points ledger and its balance
-- projection, badges and the teacher toolkit's activity templates.
--
-- Deviations from the design doc (also noted there):
--   * point_categories: template_code identifies a platform template (school_id and class_id null) and the school
--     copy made from it; school_id is required for class categories; HOME categories are school-level and POSITIVE
--     only; (id, school_id) is unique so that ledger rows reference a category of their own school.
--   * point_transactions: polarity (POSITIVE|NEEDS_WORK, copied from the category, and by a reversal from the
--     original) so that balances and reports need no join; the sign of points follows it (an AWARD is positive for
--     POSITIVE and negative for NEEDS_WORK, a REVERSAL the opposite); |points| <= 10; awarded_by is null exactly for
--     SYSTEM awards; one reversal per transaction and one row per (awarder, client_op_id, student, type) (unique
--     indexes); class, category and academic year reference their parent through the composite (…, school_id) key.
--   * point_balances: school_id also references the year through the composite key; balances are a projection
--     rebuilt from the ledger, so the per-category split of a student's totals is read from the ledger.
--   * badges: icon (a client icon key, besides icon_media_id), sort_order, created_by, the std columns and
--     unique (id, school_id). student_badges: unique (badge_id, student_id) (grants are idempotent) and the composite
--     (badge_id, school_id) key.
--   * classroom_activity_templates: class_id (a template for the teachers of one class), visibility also allows CLASS,
--     the std columns, composite (…, school_id) keys.
--   * Enumerations get CHECK constraints (DB §1).

create table point_categories (                  -- §20
  id              uuid primary key default uuidv7(),
  school_id       uuid references schools,       -- null = platform template
  class_id        uuid,                          -- class-specific custom skill
  template_code   text check (template_code is null or template_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  name_i18n       jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  icon            text check (icon is null or char_length(icon) <= 64),
  color           text check (color is null or color ~ '^#[0-9a-fA-F]{6}$'),
  default_points  smallint not null default 1 check (default_points between 1 and 10),
  polarity        text not null default 'POSITIVE' check (polarity in ('POSITIVE', 'NEEDS_WORK')),
  context         text not null default 'CLASS' check (context in ('CLASS', 'HOME')),
  is_active       boolean not null default true,
  sort_order      smallint not null default 0,
  created_by      uuid references users,
  foreign key (class_id, school_id) references classes (id, school_id),
  check (class_id is null or school_id is not null),
  check (context = 'CLASS' or (polarity = 'POSITIVE' and class_id is null)),
  unique (id, school_id),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create unique index ux_point_categories_template on point_categories (template_code)
  where school_id is null and template_code is not null;
create unique index ux_point_categories_school_template on point_categories (school_id, template_code)
  where school_id is not null and class_id is null and template_code is not null;
create index ix_point_categories_school on point_categories (school_id, context, sort_order) where school_id is not null;
create index ix_point_categories_class on point_categories (class_id) where class_id is not null;
create index ix_point_categories_creator on point_categories (created_by) where created_by is not null;

-- Append-only ledger (§21). Never updated; corrections are new rows.
create table point_transactions (
  id                      uuid primary key default uuidv7(),
  school_id               uuid not null references schools,
  student_id              uuid not null references student_profiles,
  context                 text not null check (context in ('CLASS', 'SCHOOL', 'HOME')),
  class_id                uuid,                  -- required when context = CLASS
  academic_year_id        uuid not null,
  category_id             uuid not null,
  polarity                text not null check (polarity in ('POSITIVE', 'NEEDS_WORK')),
  points                  smallint not null check (points <> 0 and abs(points) <= 10),
  transaction_type        text not null default 'AWARD' check (transaction_type in ('AWARD', 'REVERSAL', 'CORRECTION')),
  reverses_transaction_id uuid references point_transactions,
  reason                  text check (reason is null or char_length(reason) <= 500),
  awarded_by              uuid references users, -- null = system award
  awarder_role            text not null check (awarder_role in ('TEACHER', 'GUARDIAN', 'SCHOOL_ADMIN', 'SYSTEM')),
  award_batch_id          uuid,                  -- whole-class / multi-select award
  client_op_id            uuid,
  created_at              timestamptz not null default now(),
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (category_id, school_id) references point_categories (id, school_id),
  foreign key (academic_year_id, school_id) references academic_years (id, school_id),
  check ((context = 'CLASS') = (class_id is not null)),
  check ((context = 'HOME') = (awarder_role = 'GUARDIAN')),            -- D2: guardian points are always HOME
  check ((transaction_type = 'AWARD') = (reverses_transaction_id is null)),
  check ((awarder_role = 'SYSTEM') = (awarded_by is null)),
  check (transaction_type <> 'AWARD' or ((polarity = 'POSITIVE') = (points > 0))),
  check (transaction_type <> 'REVERSAL' or ((polarity = 'POSITIVE') = (points < 0)))
);
create index ix_points_student on point_transactions (student_id, id desc);
create index ix_points_student_year on point_transactions (student_id, academic_year_id, context, class_id);
create index ix_points_class on point_transactions (class_id, id desc) where class_id is not null;
create index ix_points_school on point_transactions (school_id, created_at desc);
create index ix_points_category on point_transactions (category_id);
create index ix_points_year on point_transactions (academic_year_id);
create index ix_points_awarded_by on point_transactions (awarded_by) where awarded_by is not null;
create index ix_points_batch on point_transactions (award_batch_id) where award_batch_id is not null;
create unique index ux_points_reversal_once on point_transactions (reverses_transaction_id)
  where reverses_transaction_id is not null and transaction_type = 'REVERSAL';
create unique index ux_points_client_op on point_transactions
  (coalesce(awarded_by, '00000000-0000-0000-0000-000000000000'), client_op_id, student_id, transaction_type)
  where client_op_id is not null;

-- Rebuildable projection for fast totals; never the source of truth. Keyed by the zero uuid for class when the
-- context has none. needs_work_total is negative (or zero); the total of a student is positive + needs_work.
create table point_balances (
  student_id          uuid not null references student_profiles,
  school_id           uuid not null references schools,
  academic_year_id    uuid not null,
  context             text not null check (context in ('CLASS', 'SCHOOL', 'HOME')),
  class_id            uuid not null default '00000000-0000-0000-0000-000000000000',
  positive_total      int not null default 0,
  needs_work_total    int not null default 0,
  last_transaction_at timestamptz,
  primary key (student_id, academic_year_id, context, class_id),
  foreign key (academic_year_id, school_id) references academic_years (id, school_id)
);
create index ix_point_balances_class on point_balances (class_id, academic_year_id) where context = 'CLASS';
create index ix_point_balances_school on point_balances (school_id, academic_year_id, context);

create table badges (                            -- definitions (R-19)
  id               uuid primary key default uuidv7(),
  school_id        uuid references schools,      -- null = platform template
  code             text not null check (code ~ '^[a-z][a-z0-9_]{1,40}$'),
  name_i18n        jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  description_i18n jsonb check (description_i18n is null or jsonb_typeof(description_i18n) = 'object'),
  icon             text check (icon is null or char_length(icon) <= 64),
  icon_media_id    uuid references media,
  criteria_type    text not null check (criteria_type in ('POINTS_TOTAL', 'CATEGORY_TOTAL', 'ATTENDANCE_STREAK', 'MANUAL')),
  criteria         jsonb check (criteria is null or jsonb_typeof(criteria) = 'object'),
  is_active        boolean not null default true,
  sort_order       smallint not null default 0,
  created_by       uuid references users,
  unique nulls not distinct (school_id, code),
  unique (id, school_id),
  -- std
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  version          int not null default 0
);
create index ix_badges_icon_media on badges (icon_media_id) where icon_media_id is not null;
create index ix_badges_creator on badges (created_by) where created_by is not null;

create table student_badges (                    -- awards ("achievements")
  id              uuid primary key default uuidv7(),
  badge_id        uuid not null,
  student_id      uuid not null references student_profiles,
  school_id       uuid not null references schools,
  awarded_at      timestamptz not null default now(),
  awarded_by      uuid references users,         -- null = rule engine
  source          jsonb,                         -- triggering transaction ids, streak dates
  revoked_at      timestamptz,
  revoked_by      uuid references users,
  foreign key (badge_id, school_id) references badges (id, school_id),
  check (revoked_by is null or revoked_at is not null),
  unique (badge_id, student_id)
);
create index ix_student_badges_student on student_badges (student_id, awarded_at desc);
create index ix_student_badges_school on student_badges (school_id);
create index ix_student_badges_awarded_by on student_badges (awarded_by) where awarded_by is not null;
create index ix_student_badges_revoked_by on student_badges (revoked_by) where revoked_by is not null;

-- Timer, stopwatch, random picker and group maker are client-side; saved groups are student_groups (V3_03).
create table classroom_activity_templates (      -- §126 routines, checklists, activity instructions
  id              uuid primary key default uuidv7(),
  school_id       uuid references schools,
  class_id        uuid,
  owner_id        uuid not null references users,
  template_type   text not null check (template_type in ('ROUTINE', 'CHECKLIST', 'ACTIVITY', 'LESSON_PREP')),
  title           text not null check (char_length(title) between 1 and 200),
  instructions    text check (instructions is null or char_length(instructions) <= 5000),
  items           jsonb check (items is null or jsonb_typeof(items) = 'array'),
  visibility      text not null default 'PRIVATE' check (visibility in ('PRIVATE', 'CLASS', 'SCHOOL')),
  subject_id      uuid,
  grade_level_id  uuid,
  foreign key (class_id, school_id) references classes (id, school_id),
  foreign key (subject_id, school_id) references subjects (id, school_id),
  foreign key (grade_level_id, school_id) references grade_levels (id, school_id),
  check ((visibility = 'CLASS') = (class_id is not null)),
  check (class_id is null or school_id is not null),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_activity_templates_school on classroom_activity_templates (school_id, visibility, id desc);
create index ix_activity_templates_owner on classroom_activity_templates (owner_id, id desc);
create index ix_activity_templates_class on classroom_activity_templates (class_id) where class_id is not null;
create index ix_activity_templates_subject on classroom_activity_templates (subject_id) where subject_id is not null;
create index ix_activity_templates_grade on classroom_activity_templates (grade_level_id) where grade_level_id is not null;
