-- M1a · tenancy (Database_Design §4.2: organizations, schools, school_languages, campuses)
-- plus holidays (§4.1), which reference schools and so are created here.

create table organizations (
  id              uuid primary key default uuidv7(),
  country_code    char(2) not null references countries,
  name            text not null,
  legal_name      text,
  slug            citext not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) <= 63),
  address_id      uuid references addresses,
  -- logo_media_id uuid references media: added with the media tables in M2.
  status          text not null default 'ACTIVE' check (status in ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_organizations_country on organizations (country_code);
create index ix_organizations_address on organizations (address_id);

create table schools (
  id                      uuid primary key default uuidv7(),
  country_code            char(2) not null references countries,
  organization_id         uuid references organizations,       -- null = independent school (§5)
  name                    text not null,
  short_name              text,
  slug                    citext not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) <= 63),
  school_type             text check (school_type in ('PRE_PRIMARY', 'PRIMARY', 'SECONDARY', 'K12', 'OTHER')),
  ownership_type          text check (ownership_type in ('PUBLIC', 'PRIVATE', 'FAITH', 'NGO', 'INTERNATIONAL')),
  -- education_system_id uuid references education_systems: added with the curriculum tables in P2 (M16).
  default_language_code   text not null references languages,
  timezone                text not null,
  default_calendar_code   text not null references calendar_systems,
  currency_code           char(3) not null references currencies,
  address_id              uuid references addresses,
  phone_e164              text check (phone_e164 is null or phone_e164 ~ '^\+[1-9][0-9]{6,14}$'),
  email                   citext,
  website                 text,
  -- logo_media_id, cover_media_id uuid references media: added with the media tables in M2.
  brand_color             text check (brand_color is null or brand_color ~ '^#[0-9a-fA-F]{6}$'),
  status                  text not null default 'ONBOARDING'
                            check (status in ('ONBOARDING', 'ACTIVE', 'SUSPENDED', 'ARCHIVED')),
  unique (id, country_code),
  -- std
  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),
  version                 int not null default 0
);
create index ix_schools_country on schools (country_code, id desc);
create index ix_schools_organization on schools (organization_id, id desc);
create index ix_schools_status on schools (status, id desc);
create index ix_schools_language on schools (default_language_code);
create index ix_schools_address on schools (address_id);

create table school_languages (           -- §110 school-level localization
  school_id       uuid not null references schools,
  language_code   text not null references languages,
  is_default      boolean not null default false,
  primary key (school_id, language_code)
);
create unique index ux_school_default_language on school_languages (school_id) where is_default;
create index ix_school_languages_language on school_languages (language_code);

create table campuses (
  id              uuid primary key default uuidv7(),
  school_id       uuid not null references schools,
  name            text not null,
  code            text,
  address_id      uuid references addresses,
  timezone        text,                          -- null = school timezone
  status          text not null default 'ACTIVE' check (status in ('ACTIVE', 'ARCHIVED')),
  unique (id, school_id),
  unique (school_id, code),
  -- std
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  version         int not null default 0
);
create index ix_campuses_address on campuses (address_id);

-- Concrete dated occurrences (movable feasts are entered per year)
create table holidays (
  id                  uuid primary key default uuidv7(),
  country_code        char(2) references countries,
  admin_area_1_id     uuid references administrative_areas, -- regional holiday
  school_id           uuid references schools,               -- school break
  name_i18n           jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  holiday_type        text not null check (holiday_type in ('NATIONAL', 'RELIGIOUS', 'REGIONAL', 'SCHOOL_BREAK')),
  starts_on           date not null,
  ends_on             date not null,
  check (country_code is not null or school_id is not null),
  check (ends_on >= starts_on),
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create index ix_holidays_country on holidays (country_code, starts_on);
create index ix_holidays_admin_area on holidays (admin_area_1_id);
create index ix_holidays_school on holidays (school_id, starts_on);
