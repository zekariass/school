-- M1b · authorization (Database_Design §4.3): roles, permissions and scoped role assignments,
-- plus the users(id) foreign keys deferred by M1a (updated_by / assigned_by columns).
-- Roles, permissions and their mapping are code-owned: see R__40_roles_permissions.sql.

create table roles (
  code            text primary key,
  -- PLATFORM_ADMIN, COUNTRY_ADMIN, ORGANIZATION_ADMIN, SCHOOL_ADMIN, CAMPUS_ADMIN,
  -- SCHOOL_STAFF, PLATFORM_MODERATOR, SUPPORT_AGENT, SAFEGUARDING_LEAD (R-34)
  name_i18n       jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  scope_level     text not null check (scope_level in ('PLATFORM', 'COUNTRY', 'ORGANIZATION', 'SCHOOL', 'CAMPUS')),
  is_system       boolean not null default true,
  description     text
);

create table permissions (
  code            text primary key,              -- school.manage, audit.read, platform.schools.manage ...
  module          text not null,
  description     text
);

create table role_permissions (
  role_code       text references roles,
  permission_code text references permissions,
  primary key (role_code, permission_code)
);
create index ix_role_permissions_permission on role_permissions (permission_code);

-- Administrative/operational roles only. Teacher, student, guardian and tutor capabilities come
-- from profiles + memberships + relationships, not from this table (R-02).
create table role_assignments (
  id              uuid primary key default uuidv7(),
  user_id         uuid not null references users,
  role_code       text not null references roles,
  scope_type      text not null check (scope_type in ('PLATFORM', 'COUNTRY', 'ORGANIZATION', 'SCHOOL', 'CAMPUS')),
  country_code    char(2) references countries,
  organization_id uuid references organizations,
  school_id       uuid references schools,
  campus_id       uuid,
  status          text not null default 'ACTIVE' check (status in ('ACTIVE', 'REVOKED')),
  granted_by      uuid references users,
  granted_at      timestamptz not null default now(),
  revoked_at      timestamptz,
  foreign key (campus_id, school_id) references campuses (id, school_id),
  -- FKs set exactly as required by scope_type (same pattern as settings); a campus carries its school.
  check ( (scope_type = 'PLATFORM'     and num_nonnulls(country_code, organization_id, school_id, campus_id) = 0)
       or (scope_type = 'COUNTRY'      and country_code is not null and num_nonnulls(organization_id, school_id, campus_id) = 0)
       or (scope_type = 'ORGANIZATION' and organization_id is not null and num_nonnulls(country_code, school_id, campus_id) = 0)
       or (scope_type = 'SCHOOL'       and school_id is not null and num_nonnulls(country_code, organization_id, campus_id) = 0)
       or (scope_type = 'CAMPUS'       and campus_id is not null and school_id is not null
                                       and num_nonnulls(country_code, organization_id) = 0) ),
  check ((status = 'REVOKED') = (revoked_at is not null))
);
create unique index ux_role_assignment_active on role_assignments
  (user_id, role_code, scope_type, coalesce(country_code, ''),
   coalesce(organization_id, '00000000-0000-0000-0000-000000000000'),
   coalesce(school_id, '00000000-0000-0000-0000-000000000000'),
   coalesce(campus_id, '00000000-0000-0000-0000-000000000000'))
  where status = 'ACTIVE';
create index ix_role_assignments_user on role_assignments (user_id) where status = 'ACTIVE';
create index ix_role_assignments_role on role_assignments (role_code) where status = 'ACTIVE';
create index ix_role_assignments_country on role_assignments (country_code) where country_code is not null;
create index ix_role_assignments_organization on role_assignments (organization_id) where organization_id is not null;
create index ix_role_assignments_school on role_assignments (school_id) where school_id is not null;
create index ix_role_assignments_campus on role_assignments (campus_id, school_id) where campus_id is not null;
create index ix_role_assignments_granted_by on role_assignments (granted_by) where granted_by is not null;

-- Deferred from M1a: actor columns now reference users. users is new, so any value written
-- before this migration cannot point at a user; clear such values so the FKs can be created.
update system_settings set updated_by = null where updated_by is not null;
update settings set updated_by = null where updated_by is not null;
update feature_overrides set updated_by = null where updated_by is not null;
update subscriptions set assigned_by = null where assigned_by is not null;

alter table system_settings add constraint fk_system_settings_updated_by foreign key (updated_by) references users;
alter table settings add constraint fk_settings_updated_by foreign key (updated_by) references users;
alter table feature_overrides add constraint fk_feature_overrides_updated_by foreign key (updated_by) references users;
alter table subscriptions add constraint fk_subscriptions_assigned_by foreign key (assigned_by) references users;

create index ix_system_settings_updated_by on system_settings (updated_by) where updated_by is not null;
create index ix_settings_updated_by on settings (updated_by) where updated_by is not null;
create index ix_feature_overrides_updated_by on feature_overrides (updated_by) where updated_by is not null;
create index ix_subscriptions_assigned_by on subscriptions (assigned_by) where assigned_by is not null;
