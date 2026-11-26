-- Code-owned roles, permissions and the role -> permission mapping (repeatable, idempotent).
-- ON CONFLICT DO UPDATE keeps the database in step with the code on every deploy, and the
-- mapping of system roles is replaced exactly: a permission removed here is removed from
-- the role. Permission codes must equal common.security.Permissions (RolesAndPermissionsSeedTest).
-- Amharic names are drafts pending native-speaker review.

insert into permissions (code, module, description) values
  ('platform.organizations.manage', 'tenancy',       'Create and update organizations in the covered countries'),
  ('platform.schools.manage',       'tenancy',       'Create schools and change their status in the covered scope'),
  ('platform.subscriptions.manage', 'subscriptions', 'Assign subscription plans to schools'),
  ('platform.features.manage',      'tenancy',       'Enable or disable features at a scope'),
  ('platform.settings.manage',      'tenancy',       'Change behaviour settings at platform, country, organization or school level'),
  ('platform.roles.manage',         'authorization', 'Grant and revoke administrative roles at or below the covered scope'),
  ('school.read',                   'tenancy',       'See a school, its campuses and its configuration'),
  ('school.manage',                 'tenancy',       'Manage a school''s campuses and profile'),
  ('school.settings.manage',        'tenancy',       'Change a school''s own settings'),
  ('audit.read',                    'audit',         'Read the audit log'),
  ('school.academic.manage',        'academic',      'Manage a school''s academic years, periods, grade levels and subjects'),
  ('school.staff.manage',           'people',        'Add, edit and deactivate a school''s staff'),
  ('school.students.manage',        'people',        'Enroll, edit and withdraw a school''s students; manage their sign-in'),
  ('class.manage',                  'academic',      'Create and manage every class of the covered school or campus, rosters included'),
  ('school.import.manage',          'imports',       'Upload, check and commit roster imports (students, staff, classes, subjects, grade levels) of a school'),
  ('school.guardians.manage',       'guardians',     'Invite, verify and revoke guardians of a school''s students; edit their permissions and custody restrictions; record paper consent'),
  ('school.policies.manage',        'consent',       'Write a school''s own policy texts (account participation, media publication)'),
  ('school.points.manage',          'engagement',    'Manage a school''s point categories and badges, award and reverse points school-wide, share activity templates'),
  ('school.points.read',            'engagement',    'Read a school''s points reports (CSV export included) and any student''s points'),
  ('school.attendance.manage',      'attendance',    'Take or correct any class''s roll, edit a locked roll with a reason, decide on absence reports and read their notes'),
  ('school.attendance.read',        'attendance',    'Read a school''s attendance dashboard and summary reports (CSV export included) and any student''s attendance'),
  ('school.feed.manage',            'feed',          'Post and pin school-wide posts and stories, approve or reject contributions of any class, moderate any post, story or comment of the school'),
  ('school.announcements.manage',   'communications', 'Compose, publish, edit and withdraw announcements to the whole school, a campus, a grade, any class or chosen people; read every announcement''s delivery report and remind the unacknowledged'),
  ('school.announcements.urgent',   'communications', 'Send URGENT and SAFETY announcements (they bypass quiet hours and cannot be muted)'),
  ('school.messaging.moderate',     'messaging',     'Moderate the school''s group conversations: create school and staff groups, approve student groups, add and remove members, lock, archive and hide messages'),
  ('school.messaging.review',       'messaging',     'Review admin-visible and reported conversations through the audited read endpoint (mandatory reason), list student blocks of adults'),
  ('school.calendar.manage',        'calendar',      'Create, change, cancel and delete school-wide, campus and grade events and any class event of the school'),
  ('school.portfolios.review',      'portfolio',     'Review, comment on, archive and delete student portfolio items of the school (administrator oversight; class teachers review through their class)')
on conflict (code) do update
  set module = excluded.module,
      description = excluded.description;

insert into roles (code, name_i18n, scope_level, is_system, description) values
  ('PLATFORM_ADMIN',     '{"en": "Platform administrator", "am": "የመድረክ አስተዳዳሪ"}',            'PLATFORM',     true, 'Operates the whole platform'),
  ('COUNTRY_ADMIN',      '{"en": "Country administrator", "am": "የአገር አስተዳዳሪ"}',               'COUNTRY',      true, 'Operates organizations and schools of one country'),
  ('ORGANIZATION_ADMIN', '{"en": "Organization administrator", "am": "የድርጅት አስተዳዳሪ"}',         'ORGANIZATION', true, 'Operates the schools of one organization'),
  ('SCHOOL_ADMIN',       '{"en": "School administrator", "am": "የትምህርት ቤት አስተዳዳሪ"}',          'SCHOOL',       true, 'Runs one school'),
  ('CAMPUS_ADMIN',       '{"en": "Campus administrator", "am": "የቅርንጫፍ አስተዳዳሪ"}',             'CAMPUS',       true, 'Runs one campus of a school'),
  ('SCHOOL_STAFF',       '{"en": "School staff", "am": "የትምህርት ቤት ሠራተኛ"}',                    'SCHOOL',       true, 'Non-teaching staff with permissions granted per role (R-34)'),
  ('PLATFORM_MODERATOR', '{"en": "Platform moderator", "am": "የመድረክ ይዘት ተቆጣጣሪ"}',              'PLATFORM',     true, 'Reviews reported content (M13)'),
  ('SUPPORT_AGENT',      '{"en": "Support agent", "am": "የድጋፍ ሠራተኛ"}',                          'PLATFORM',     true, 'Helps schools and families (read-only for now)'),
  ('SAFEGUARDING_LEAD',  '{"en": "Safeguarding lead", "am": "የሕፃናት ደህንነት ኃላፊ"}',                'SCHOOL',       true, 'Handles safeguarding incidents of one school (M13)')
on conflict (code) do update
  set name_i18n = excluded.name_i18n,
      scope_level = excluded.scope_level,
      is_system = excluded.is_system,
      description = excluded.description;

create temporary table declared_role_permissions (role_code text, permission_code text) on commit drop;
insert into declared_role_permissions (role_code, permission_code) values
  -- Platform administrators hold every permission.
  ('PLATFORM_ADMIN', 'platform.organizations.manage'),
  ('PLATFORM_ADMIN', 'platform.schools.manage'),
  ('PLATFORM_ADMIN', 'platform.subscriptions.manage'),
  ('PLATFORM_ADMIN', 'platform.features.manage'),
  ('PLATFORM_ADMIN', 'platform.settings.manage'),
  ('PLATFORM_ADMIN', 'platform.roles.manage'),
  ('PLATFORM_ADMIN', 'school.read'),
  ('PLATFORM_ADMIN', 'school.manage'),
  ('PLATFORM_ADMIN', 'school.settings.manage'),
  ('PLATFORM_ADMIN', 'audit.read'),
  ('PLATFORM_ADMIN', 'school.academic.manage'),
  ('PLATFORM_ADMIN', 'school.staff.manage'),
  ('PLATFORM_ADMIN', 'school.students.manage'),
  ('PLATFORM_ADMIN', 'class.manage'),
  ('PLATFORM_ADMIN', 'school.import.manage'),
  ('PLATFORM_ADMIN', 'school.guardians.manage'),
  ('PLATFORM_ADMIN', 'school.policies.manage'),
  ('PLATFORM_ADMIN', 'school.points.manage'),
  ('PLATFORM_ADMIN', 'school.points.read'),
  ('PLATFORM_ADMIN', 'school.attendance.manage'),
  ('PLATFORM_ADMIN', 'school.attendance.read'),
  ('PLATFORM_ADMIN', 'school.feed.manage'),
  ('PLATFORM_ADMIN', 'school.announcements.manage'),
  ('PLATFORM_ADMIN', 'school.announcements.urgent'),
  ('PLATFORM_ADMIN', 'school.messaging.moderate'),
  ('PLATFORM_ADMIN', 'school.messaging.review'),
  ('PLATFORM_ADMIN', 'school.calendar.manage'),
  ('PLATFORM_ADMIN', 'school.portfolios.review'),
  -- Country: organizations, schools and settings within the country; no plans or feature flags.
  ('COUNTRY_ADMIN', 'platform.organizations.manage'),
  ('COUNTRY_ADMIN', 'platform.schools.manage'),
  ('COUNTRY_ADMIN', 'platform.settings.manage'),
  ('COUNTRY_ADMIN', 'platform.roles.manage'),
  ('COUNTRY_ADMIN', 'school.read'),
  ('COUNTRY_ADMIN', 'school.manage'),
  ('COUNTRY_ADMIN', 'school.settings.manage'),
  ('COUNTRY_ADMIN', 'school.academic.manage'),
  ('COUNTRY_ADMIN', 'school.staff.manage'),
  ('COUNTRY_ADMIN', 'school.students.manage'),
  ('COUNTRY_ADMIN', 'class.manage'),
  ('COUNTRY_ADMIN', 'school.import.manage'),
  ('COUNTRY_ADMIN', 'school.guardians.manage'),
  ('COUNTRY_ADMIN', 'school.policies.manage'),
  ('COUNTRY_ADMIN', 'school.points.manage'),
  ('COUNTRY_ADMIN', 'school.points.read'),
  ('COUNTRY_ADMIN', 'school.attendance.manage'),
  ('COUNTRY_ADMIN', 'school.attendance.read'),
  ('COUNTRY_ADMIN', 'school.feed.manage'),
  ('COUNTRY_ADMIN', 'school.announcements.manage'),
  ('COUNTRY_ADMIN', 'school.announcements.urgent'),
  ('COUNTRY_ADMIN', 'school.messaging.moderate'),
  ('COUNTRY_ADMIN', 'school.messaging.review'),
  ('COUNTRY_ADMIN', 'school.calendar.manage'),
  ('COUNTRY_ADMIN', 'school.portfolios.review'),
  -- Organization: its schools (create, status, campuses, school settings) and their admins.
  ('ORGANIZATION_ADMIN', 'platform.schools.manage'),
  ('ORGANIZATION_ADMIN', 'platform.roles.manage'),
  ('ORGANIZATION_ADMIN', 'school.read'),
  ('ORGANIZATION_ADMIN', 'school.manage'),
  ('ORGANIZATION_ADMIN', 'school.settings.manage'),
  ('ORGANIZATION_ADMIN', 'school.academic.manage'),
  ('ORGANIZATION_ADMIN', 'school.staff.manage'),
  ('ORGANIZATION_ADMIN', 'school.students.manage'),
  ('ORGANIZATION_ADMIN', 'class.manage'),
  ('ORGANIZATION_ADMIN', 'school.import.manage'),
  ('ORGANIZATION_ADMIN', 'school.guardians.manage'),
  ('ORGANIZATION_ADMIN', 'school.policies.manage'),
  ('ORGANIZATION_ADMIN', 'school.points.manage'),
  ('ORGANIZATION_ADMIN', 'school.points.read'),
  ('ORGANIZATION_ADMIN', 'school.attendance.manage'),
  ('ORGANIZATION_ADMIN', 'school.attendance.read'),
  ('ORGANIZATION_ADMIN', 'school.feed.manage'),
  ('ORGANIZATION_ADMIN', 'school.announcements.manage'),
  ('ORGANIZATION_ADMIN', 'school.announcements.urgent'),
  ('ORGANIZATION_ADMIN', 'school.messaging.moderate'),
  ('ORGANIZATION_ADMIN', 'school.messaging.review'),
  ('ORGANIZATION_ADMIN', 'school.calendar.manage'),
  ('ORGANIZATION_ADMIN', 'school.portfolios.review'),
  -- School: its own school, campuses and school-level settings.
  ('SCHOOL_ADMIN', 'school.read'),
  ('SCHOOL_ADMIN', 'school.manage'),
  ('SCHOOL_ADMIN', 'school.settings.manage'),
  ('SCHOOL_ADMIN', 'school.academic.manage'),
  ('SCHOOL_ADMIN', 'school.staff.manage'),
  ('SCHOOL_ADMIN', 'school.students.manage'),
  ('SCHOOL_ADMIN', 'class.manage'),
  ('SCHOOL_ADMIN', 'school.import.manage'),
  ('SCHOOL_ADMIN', 'school.guardians.manage'),
  ('SCHOOL_ADMIN', 'school.policies.manage'),
  ('SCHOOL_ADMIN', 'school.points.manage'),
  ('SCHOOL_ADMIN', 'school.points.read'),
  ('SCHOOL_ADMIN', 'school.attendance.manage'),
  ('SCHOOL_ADMIN', 'school.attendance.read'),
  ('SCHOOL_ADMIN', 'school.feed.manage'),
  ('SCHOOL_ADMIN', 'school.announcements.manage'),
  ('SCHOOL_ADMIN', 'school.announcements.urgent'),
  ('SCHOOL_ADMIN', 'school.messaging.moderate'),
  ('SCHOOL_ADMIN', 'school.messaging.review'),
  ('SCHOOL_ADMIN', 'school.calendar.manage'),
  ('SCHOOL_ADMIN', 'school.portfolios.review'),
  -- The four school-operations permissions (M3a) are held by every administrator who may grant SCHOOL_ADMIN
  -- (platform, country, organization): granting needs every permission of the role (no escalation), so withholding
  -- them would only move the data access to a school administrator they appoint.
  -- Campus: reads its school and manages the classes (rosters, teachers, login codes) of its own campus.
  -- Staff, student and academic-year management stay school-wide, because those rows are not campus-bound.
  ('CAMPUS_ADMIN', 'school.read'),
  ('CAMPUS_ADMIN', 'class.manage'),
  -- Read-mostly roles until their milestones add more.
  ('SCHOOL_STAFF', 'school.read'),
  ('SUPPORT_AGENT', 'school.read'),
  ('SAFEGUARDING_LEAD', 'school.read'),
  ('SAFEGUARDING_LEAD', 'school.messaging.review');

insert into role_permissions (role_code, permission_code)
select role_code, permission_code from declared_role_permissions
on conflict do nothing;

-- System roles hold exactly the declared permissions.
delete from role_permissions rp
using roles r
where r.code = rp.role_code
  and r.is_system
  and not exists (select 1 from declared_role_permissions d
                  where d.role_code = rp.role_code and d.permission_code = rp.permission_code);
