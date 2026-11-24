-- Subscription plans, prices and entitlements (repeatable, idempotent).
--
-- Plans and prices are commercial data an operator may edit: ON CONFLICT DO NOTHING.
-- Entitlements of the seeded plans are owned by the code: ON CONFLICT DO UPDATE.
-- Runs after R__20_definitions.sql (feature_definitions) by file name order.

insert into subscription_plans (code, audience, name_i18n, billing_period) values
  ('SCHOOL_FREE',     'SCHOOL', '{"en": "Free",       "am": "ነፃ"}',      null),
  ('SCHOOL_BASIC',    'SCHOOL', '{"en": "Basic",      "am": "መሠረታዊ"}',   'YEARLY'),
  ('SCHOOL_STANDARD', 'SCHOOL', '{"en": "Standard",   "am": "መደበኛ"}',    'YEARLY'),
  ('SCHOOL_PREMIUM',  'SCHOOL', '{"en": "Premium",    "am": "ፕሪሚየም"}',   'YEARLY'),
  ('ENTERPRISE',      'SCHOOL', '{"en": "Enterprise", "am": "ኢንተርፕራይዝ"}', null)
on conflict (code) do nothing;

-- Placeholder prices for Ethiopia: SCHOOL_FREE is genuinely free; the paid amounts are
-- 0 until the commercial team sets them (amount_minor is in ETB cents).
insert into plan_prices (plan_id, country_code, currency_code, amount_minor)
select p.id, 'ET', 'ETB', 0
from subscription_plans p
where p.code in ('SCHOOL_FREE', 'SCHOOL_BASIC', 'SCHOOL_STANDARD', 'SCHOOL_PREMIUM', 'ENTERPRISE')
on conflict (plan_id, country_code) do nothing;

-- Entitlement matrix: one row per plan x feature definition. A feature missing from a
-- plan's list below is disabled for that plan, so new feature definitions default to
-- "not entitled" until a plan lists them. limit_value null = unlimited.
insert into plan_entitlements (plan_id, feature_key, enabled, limit_value)
select p.id, f.key, f.key = any (m.features), l.limit_value
from (values
  ('SCHOOL_FREE', array['attendance', 'points', 'feed', 'messaging', 'announcements', 'calendar',
                        'max_students', 'storage_bytes']),
  ('SCHOOL_BASIC', array['attendance', 'points', 'feed', 'messaging', 'announcements', 'calendar',
                         'portfolio', 'library', 'assignments', 'max_students', 'storage_bytes']),
  ('SCHOOL_STANDARD', array['attendance', 'points', 'feed', 'messaging', 'announcements', 'calendar',
                            'portfolio', 'library', 'assignments', 'courses', 'max_students', 'storage_bytes']),
  ('SCHOOL_PREMIUM', array['attendance', 'points', 'feed', 'messaging', 'announcements', 'calendar',
                           'portfolio', 'library', 'assignments', 'courses', 'marketplace', 'tutoring',
                           'payments', 'ai_copilot', 'max_students', 'storage_bytes']),
  ('ENTERPRISE', array['attendance', 'points', 'feed', 'messaging', 'announcements', 'calendar',
                       'portfolio', 'library', 'assignments', 'courses', 'marketplace', 'tutoring',
                       'payments', 'ai_copilot', 'ai_student_tutor', 'max_students', 'storage_bytes'])
) as m(plan_code, features)
join subscription_plans p on p.code = m.plan_code
cross join feature_definitions f
left join (values
  ('SCHOOL_FREE',     'max_students',  100::bigint),
  ('SCHOOL_FREE',     'storage_bytes', 1073741824),       -- 1 GiB
  ('SCHOOL_BASIC',    'max_students',  500),
  ('SCHOOL_BASIC',    'storage_bytes', 10737418240),      -- 10 GiB
  ('SCHOOL_STANDARD', 'max_students',  2000),
  ('SCHOOL_STANDARD', 'storage_bytes', 53687091200),      -- 50 GiB
  ('SCHOOL_PREMIUM',  'max_students',  10000),
  ('SCHOOL_PREMIUM',  'storage_bytes', 268435456000),     -- 250 GiB
  ('SCHOOL_PREMIUM',  'ai_copilot',    10000)             -- monthly AI credits
  -- ENTERPRISE: unlimited (null)
) as l(plan_code, feature_key, limit_value) on l.plan_code = m.plan_code and l.feature_key = f.key
on conflict (plan_id, feature_key) do update
  set enabled = excluded.enabled,
      limit_value = excluded.limit_value;
