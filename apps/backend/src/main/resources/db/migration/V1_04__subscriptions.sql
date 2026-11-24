-- M1a · subscriptions (Database_Design §4.20): plans, prices, entitlements and the
-- manual school assignment of P1. Plans and entitlements are seeded by R__30_subscription_plans.sql.

create table subscription_plans (
  id              uuid primary key default uuidv7(),
  code            text not null unique,          -- SCHOOL_FREE|SCHOOL_BASIC|SCHOOL_STANDARD|SCHOOL_PREMIUM|ENTERPRISE|TUTOR_PRO
  audience        text not null check (audience in ('SCHOOL', 'ORGANIZATION', 'TUTOR')),
  name_i18n       jsonb not null check (jsonb_typeof(name_i18n) = 'object'),
  billing_period  text check (billing_period in ('MONTHLY', 'TERMLY', 'YEARLY')),
  is_active       boolean not null default true
);

create table plan_prices (
  plan_id         uuid not null references subscription_plans,
  country_code    char(2) not null references countries,
  currency_code   char(3) not null references currencies,
  amount_minor    bigint not null check (amount_minor >= 0),
  primary key (plan_id, country_code)
);
create index ix_plan_prices_country on plan_prices (country_code);
create index ix_plan_prices_currency on plan_prices (currency_code);

create table plan_entitlements (                 -- §105 limits
  plan_id         uuid not null references subscription_plans,
  feature_key     text not null references feature_definitions,
  enabled         boolean not null default true,
  limit_value     bigint check (limit_value is null or limit_value >= 0), -- max students, storage bytes, AI credits; null = unlimited
  primary key (plan_id, feature_key)
);
create index ix_plan_entitlements_feature on plan_entitlements (feature_key);

create table subscriptions (
  id                  uuid primary key default uuidv7(),
  plan_id             uuid not null references subscription_plans,
  subscriber_type     text not null check (subscriber_type in ('SCHOOL', 'ORGANIZATION', 'TUTOR')),
  school_id           uuid references schools,
  organization_id     uuid references organizations,
  -- tutor_id uuid references tutor_profiles: added with tutor profiles in P4 (M25), together
  -- with the TUTOR arm of the subscriber check below.
  status              text not null check (status in ('TRIALING', 'ACTIVE', 'PAST_DUE', 'CANCELLED', 'EXPIRED')),
  current_period_start timestamptz,
  current_period_end  timestamptz,
  trial_ends_at       timestamptz,
  cancel_at           timestamptz,
  assigned_by         uuid,                      -- users(id): FK added in M1b; manual assignment in P1
  external_ref        text,
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0,
  check ( (subscriber_type = 'SCHOOL'       and school_id is not null and organization_id is null)
       or (subscriber_type = 'ORGANIZATION' and organization_id is not null and school_id is null)
       or (subscriber_type = 'TUTOR'        and school_id is null and organization_id is null) ),
  check (current_period_start is null or current_period_end is null or current_period_end > current_period_start)
);
-- At most one live subscription per school / organization: assigning a new plan ends the old one first.
create unique index ux_subscriptions_live_school on subscriptions (school_id)
  where subscriber_type = 'SCHOOL' and status in ('TRIALING', 'ACTIVE');
create unique index ux_subscriptions_live_organization on subscriptions (organization_id)
  where subscriber_type = 'ORGANIZATION' and status in ('TRIALING', 'ACTIVE');
create index ix_subscriptions_school on subscriptions (school_id, created_at desc) where school_id is not null;
create index ix_subscriptions_organization on subscriptions (organization_id) where organization_id is not null;
create index ix_subscriptions_plan on subscriptions (plan_id);
