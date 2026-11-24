-- M1a · reference & localization (Database_Design §4.1).
-- Reference tables keyed by ISO / standard codes have no id/version columns (§1); tables
-- marked "std" carry created_at, updated_at and version (JPA @Version).
-- Seed rows live in the repeatable migration R__10_reference_data.sql.

create table currencies (
  code            char(3) primary key,           -- ISO 4217: ETB, KES, USD
  name            text not null,
  symbol          text,
  minor_units     smallint not null default 2 check (minor_units between 0 and 4)
);

create table languages (
  code            text primary key,              -- BCP 47: am, en, om, ti, so, sw
  name            text not null,                 -- English name
  native_name     text not null,                 -- አማርኛ, Afaan Oromoo
  script          text,                          -- Ethi, Latn
  direction       text not null default 'LTR' check (direction in ('LTR', 'RTL')),
  is_enabled      boolean not null default true
);

create table calendar_systems (
  code            text primary key,              -- GREGORIAN|ETHIOPIAN (extensible)
  name            text not null
);

create table countries (
  code                    char(2) primary key,   -- ISO 3166-1: ET, KE
  name_i18n               jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  default_currency_code   char(3) not null references currencies,
  default_language_code   text not null references languages,
  default_timezone        text not null,         -- Africa/Addis_Ababa
  default_calendar_code   text not null references calendar_systems,
  phone_country_code      text not null check (phone_country_code ~ '^\+[0-9]{1,4}$'),
  address_format          jsonb not null check (jsonb_typeof(address_format) = 'object'),
  name_format             jsonb check (name_format is null or jsonb_typeof(name_format) = 'object'),
  status                  text not null default 'DRAFT'
                            check (status in ('DRAFT', 'ENABLED', 'DISABLED')),
  launched_at             timestamptz,
  -- std
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  version                 int not null default 0
);
create index ix_countries_status on countries (status);

create table country_languages (
  country_code    char(2) not null references countries,
  language_code   text not null references languages,
  is_default      boolean not null default false,
  is_official     boolean not null default false,
  sort_order      smallint not null default 0,
  primary key (country_code, language_code)
);
create unique index ux_country_default_language on country_languages (country_code) where is_default;
create index ix_country_languages_language on country_languages (language_code);

create table country_calendars (
  country_code    char(2) not null references countries,
  calendar_code   text not null references calendar_systems,
  is_default      boolean not null default false,
  primary key (country_code, calendar_code)
);
create unique index ux_country_default_calendar on country_calendars (country_code) where is_default;
create index ix_country_calendars_calendar on country_calendars (calendar_code);

-- Region > Zone > Woreda (ET), County > Sub-county (KE): generic levels (§68)
create table administrative_areas (
  id              uuid primary key default uuidv7(),
  country_code    char(2) not null references countries,
  parent_id       uuid references administrative_areas,
  level           smallint not null check (level between 1 and 5),
  code            text,
  name_i18n       jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  unique (country_code, level, code)
);
create index ix_admin_areas_parent on administrative_areas (parent_id);
create index ix_admin_areas_country_level on administrative_areas (country_code, level);

create table addresses (
  id                      uuid primary key default uuidv7(),
  country_code            char(2) not null references countries,
  admin_area_1_id         uuid references administrative_areas,  -- structured (preferred)
  admin_area_2_id         uuid references administrative_areas,
  admin_area_3_id         uuid references administrative_areas,
  administrative_level_1  text,                  -- free text fallback (§68)
  administrative_level_2  text,
  administrative_level_3  text,
  locality                text,
  postal_code             text,
  address_line_1          text,
  address_line_2          text,
  latitude                numeric(9,6) check (latitude between -90 and 90),
  longitude               numeric(9,6) check (longitude between -180 and 180),
  -- std
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  version                 int not null default 0
);
create index ix_addresses_country on addresses (country_code);
create index ix_addresses_admin_area_1 on addresses (admin_area_1_id);
create index ix_addresses_admin_area_2 on addresses (admin_area_2_id);
create index ix_addresses_admin_area_3 on addresses (admin_area_3_id);
