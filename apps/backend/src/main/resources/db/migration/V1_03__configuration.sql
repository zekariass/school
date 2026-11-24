-- M1a · behaviour settings and feature flags (Database_Design §4.2, R-33) and
-- system_settings (§4.1).
-- Every updated_by is a plain uuid for now: the FK to users is added in M1b (identity).

create table system_settings (
  key             text primary key,
  value           jsonb not null,
  description     text,
  updated_by      uuid,                          -- users(id): FK added in M1b
  updated_at      timestamptz not null default now()
);

-- Behavior settings: typed definition + scoped values (R-33)
create table setting_definitions (
  key             text primary key,              -- e.g. feed.parent_contributions.enabled
  module          text not null,
  value_schema    jsonb not null,                -- JSON Schema subset, see tenancy ValueSchemaValidator
  default_value   jsonb not null,                -- JSON null is a valid default (stored as 'null'::jsonb)
  allowed_scopes  text[] not null
                    check (cardinality(allowed_scopes) > 0
                           and allowed_scopes <@ array['PLATFORM', 'COUNTRY', 'ORGANIZATION', 'SCHOOL']),
  description     text
);

create table settings (
  id              uuid primary key default uuidv7(),
  key             text not null references setting_definitions,
  scope_type      text not null check (scope_type in ('PLATFORM', 'COUNTRY', 'ORGANIZATION', 'SCHOOL')),
  country_code    char(2) references countries,
  organization_id uuid references organizations,
  school_id       uuid references schools,
  value           jsonb not null,
  updated_by      uuid,                          -- users(id): FK added in M1b
  updated_at      timestamptz not null default now(),
  check ( (scope_type = 'PLATFORM'     and num_nonnulls(country_code, organization_id, school_id) = 0)
       or (scope_type = 'COUNTRY'      and country_code is not null and num_nonnulls(organization_id, school_id) = 0)
       or (scope_type = 'ORGANIZATION' and organization_id is not null and num_nonnulls(country_code, school_id) = 0)
       or (scope_type = 'SCHOOL'       and school_id is not null and num_nonnulls(country_code, organization_id) = 0) )
);
create unique index ux_settings_scope on settings
  (key, scope_type, coalesce(country_code, ''),
   coalesce(organization_id, '00000000-0000-0000-0000-000000000000'),
   coalesce(school_id, '00000000-0000-0000-0000-000000000000'));
create index ix_settings_country on settings (country_code) where country_code is not null;
create index ix_settings_organization on settings (organization_id) where organization_id is not null;
create index ix_settings_school on settings (school_id) where school_id is not null;

create table feature_definitions (
  key             text primary key,              -- attendance, points, feed, messaging, library, courses, ...
  description     text,
  default_enabled boolean not null default false
);

create table feature_overrides (              -- §80; same scope pattern as settings
  id              uuid primary key default uuidv7(),
  feature_key     text not null references feature_definitions,
  scope_type      text not null check (scope_type in ('PLATFORM', 'COUNTRY', 'ORGANIZATION', 'SCHOOL')),
  country_code    char(2) references countries,
  organization_id uuid references organizations,
  school_id       uuid references schools,
  enabled         boolean not null,
  config          jsonb check (config is null or jsonb_typeof(config) = 'object'),
  updated_by      uuid,                          -- users(id): FK added in M1b
  updated_at      timestamptz not null default now(),
  check ( (scope_type = 'PLATFORM'     and num_nonnulls(country_code, organization_id, school_id) = 0)
       or (scope_type = 'COUNTRY'      and country_code is not null and num_nonnulls(organization_id, school_id) = 0)
       or (scope_type = 'ORGANIZATION' and organization_id is not null and num_nonnulls(country_code, school_id) = 0)
       or (scope_type = 'SCHOOL'       and school_id is not null and num_nonnulls(country_code, organization_id) = 0) )
);
create unique index ux_feature_overrides_scope on feature_overrides
  (feature_key, scope_type, coalesce(country_code, ''),
   coalesce(organization_id, '00000000-0000-0000-0000-000000000000'),
   coalesce(school_id, '00000000-0000-0000-0000-000000000000'));
create index ix_feature_overrides_country on feature_overrides (country_code) where country_code is not null;
create index ix_feature_overrides_organization on feature_overrides (organization_id) where organization_id is not null;
create index ix_feature_overrides_school on feature_overrides (school_id) where school_id is not null;
