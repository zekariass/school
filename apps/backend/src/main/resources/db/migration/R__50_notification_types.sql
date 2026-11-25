-- Notification types (repeatable). Code-owned (R-26: types are data, not an enum): ON CONFLICT DO UPDATE.
-- A new type needs its texts in messages.properties and messages_am.properties
-- (notification.<code in lower case>.title and .body; NotificationTypesSeedTest checks them).
-- Message arguments of each type are documented next to its texts in messages.properties.
-- Channels: IN_APP is the inbox row itself; PUSH is the mobile/web push. EMAIL and SMS are not delivered yet.

insert into notification_types (code, category, default_channels, can_be_urgent, user_can_disable, description) values
  ('ATTENDANCE_MARKED',       'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student was marked absent or late'),
  ('NEW_MESSAGE',             'MESSAGING', '{IN_APP,PUSH}', false, true,  'A new direct or group message'),
  ('POINT_AWARDED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student received points'),
  ('NEW_POST',                'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A new class post'),
  ('NEW_STORY',               'CLASSROOM', '{IN_APP}',      false, true,  'A new class story'),
  ('POST_APPROVED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A post awaiting approval was approved'),
  ('ANNOUNCEMENT_PUBLISHED',  'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'A school announcement was published'),
  ('ACK_REQUESTED',           'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'An acknowledgement is requested'),
  ('PORTFOLIO_REVIEWED',      'LEARNING',  '{IN_APP,PUSH}', false, true,  'A portfolio item was reviewed'),
  ('IMPORT_COMPLETED',        'SCHOOL',    '{IN_APP}',      false, true,  'A bulk import finished'),
  ('CONSENT_REQUESTED',       'SAFETY',    '{IN_APP,PUSH}', true,  false, 'A guardian consent is requested'),
  ('GUARDIAN_LINK_APPROVED',  'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian link request was approved'),
  ('UPCOMING_EVENT',          'SCHOOL',    '{IN_APP,PUSH}', false, true,  'An event starts soon'),
  ('ROLE_GRANTED',            'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'An administrative role was given to the user'),
  ('STAFF_ADDED',             'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'The user was added to a school as staff'),
  ('TEST_NOTIFICATION',       'ACCOUNT',   '{IN_APP,PUSH}', true,  true,  'Sent by the "send me a test" button of the platform console'),
  ('GUARDIAN_LINK_REQUESTED', 'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian asked to be linked to a student (to the school''s verifiers)'),
  ('GUARDIAN_LINK_REJECTED',  'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian link request was not approved'),
  ('BADGE_AWARDED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student earned a badge')
on conflict (code) do update
  set category = excluded.category,
      default_channels = excluded.default_channels,
      can_be_urgent = excluded.can_be_urgent,
      user_can_disable = excluded.user_can_disable,
      description = excluded.description;
