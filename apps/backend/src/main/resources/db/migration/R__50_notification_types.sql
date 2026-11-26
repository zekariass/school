-- Notification types (repeatable). Code-owned (R-26: types are data, not an enum): ON CONFLICT DO UPDATE.
-- A new type needs its texts in messages.properties and messages_am.properties
-- (notification.<code in lower case>.title and .body; NotificationTypesSeedTest checks them).
-- Message arguments of each type are documented next to its texts in messages.properties.
-- Channels: IN_APP is the inbox row itself; PUSH is the mobile/web push. Email and SMS of announcements are recorded on
-- announcement_recipients by the communications module, not through these types.

-- defer_in_quiet_hours: a push inside the recipient's quiet hours waits for their end instead of being dropped (M10a).
-- urgent_overrides_preferences: an URGENT notification of the type is pushed even when the recipient switched the type's
-- push off (the floor of urgent and safety announcements; in-app is always on). Quiet hours are bypassed by URGENT either way.
insert into notification_types (code, category, default_channels, can_be_urgent, user_can_disable, description,
                                defer_in_quiet_hours, urgent_overrides_preferences) values
  ('ATTENDANCE_MARKED',       'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student was marked absent or late', false, false),
  ('NEW_MESSAGE',             'MESSAGING', '{IN_APP,PUSH}', false, true,  'A new direct or group message (generic text: never the message, the child or the sender''s name)', true, false),
  ('POINT_AWARDED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student received points', false, false),
  ('NEW_POST',                'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A new class post', false, false),
  ('NEW_STORY',               'CLASSROOM', '{IN_APP}',      false, true,  'A new class story', false, false),
  ('POST_APPROVED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A post awaiting approval was approved', false, false),
  ('ANNOUNCEMENT_PUBLISHED',  'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'A school announcement was published', true, true),
  ('ACK_REQUESTED',           'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'An acknowledgement is requested', true, true),
  ('PORTFOLIO_REVIEWED',      'LEARNING',  '{IN_APP,PUSH}', false, true,  'A portfolio item was reviewed', false, false),
  ('IMPORT_COMPLETED',        'SCHOOL',    '{IN_APP}',      false, true,  'A bulk import finished', false, false),
  ('CONSENT_REQUESTED',       'SAFETY',    '{IN_APP,PUSH}', true,  false, 'A guardian consent is requested', false, false),
  ('GUARDIAN_LINK_APPROVED',  'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian link request was approved', false, false),
  ('UPCOMING_EVENT',          'SCHOOL',    '{IN_APP,PUSH}', false, true,  'An event starts soon', false, false),
  ('ROLE_GRANTED',            'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'An administrative role was given to the user', false, false),
  ('STAFF_ADDED',             'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'The user was added to a school as staff', false, false),
  ('TEST_NOTIFICATION',       'ACCOUNT',   '{IN_APP,PUSH}', true,  true,  'Sent by the "send me a test" button of the platform console', false, false),
  ('GUARDIAN_LINK_REQUESTED', 'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian asked to be linked to a student (to the school''s verifiers)', false, false),
  ('GUARDIAN_LINK_REJECTED',  'ACCOUNT',   '{IN_APP,PUSH}', false, true,  'A guardian link request was not approved', false, false),
  ('BADGE_AWARDED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A student earned a badge', false, false),
  ('ABSENCE_REPORTED',        'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A guardian reported an absence of a student (to the student''s teachers)', false, false),
  ('POST_REJECTED',           'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A contribution was not approved (to the contributor)', false, false),
  ('CONTRIBUTION_PENDING',    'CLASSROOM', '{IN_APP,PUSH}', false, true,  'A family or student contribution waits for approval (to the class''s lead teachers)', false, false),
  ('POST_PUBLISH_BLOCKED',    'CLASSROOM', '{IN_APP,PUSH}', false, false, 'A scheduled post was not published because a tagged student lacks media consent (to the author)', false, false),
  ('POST_MEDIA_WITHHELD',     'SAFETY',    '{IN_APP,PUSH}', false, false, 'The photos of a published post are hidden because media consent was withdrawn (to the author)', false, false),
  ('ANNOUNCEMENT_UPDATED',    'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'An announcement was changed in a way that matters', true, true),
  ('ANNOUNCEMENT_REMINDER',   'SCHOOL',    '{IN_APP,PUSH}', true,  true,  'Reminder to acknowledge an announcement', true, true),
  ('ANNOUNCEMENT_WITHDRAWN',  'SCHOOL',    '{IN_APP}',      false, true,  'An announcement was withdrawn by its sender', false, false),
  ('ANNOUNCEMENT_PUBLISH_BLOCKED', 'SCHOOL', '{IN_APP,PUSH}', false, false, 'A scheduled announcement was not published because its author may no longer send it (to the author)', false, false),
  ('NEW_MESSAGE_QUIET',       'MESSAGING', '{IN_APP}',      false, true,  'A new message that arrived outside the recipient''s office hours (in-app only, no push)', false, false),
  ('MESSAGE_OBSERVER_ADDED',  'SAFETY',    '{IN_APP,PUSH}', false, false, 'A guardian can now read (not write in) a conversation of their child with an adult', false, false),
  ('MESSAGE_REPORTED',        'SAFETY',    '{IN_APP,PUSH}', false, false, 'A message was reported (to the conversation''s moderators and the school''s reviewers; never the text)', false, false),
  ('GROUP_APPROVAL_REQUESTED','MESSAGING', '{IN_APP,PUSH}', false, true,  'A student group waits for a teacher''s approval (to the class''s lead and co-teachers)', false, false),
  ('MESSAGE_SCHEDULE_BLOCKED','MESSAGING', '{IN_APP,PUSH}', false, false, 'A scheduled message was not sent because the sender may no longer send it (to the sender)', false, false),
  ('EVENT_INVITATION',        'SCHOOL',    '{IN_APP,PUSH}', false, true,  'An invitation to a parent meeting, or a new event when the school asks for it', true, false),
  ('EVENT_UPDATED',           'SCHOOL',    '{IN_APP,PUSH}', false, true,  'An event was moved to another time or place', true, false),
  ('EVENT_CANCELLED',         'SCHOOL',    '{IN_APP,PUSH}', false, true,  'An event or one of its occurrences was cancelled', true, false),
  ('PORTFOLIO_SUBMITTED',     'LEARNING',  '{IN_APP,PUSH}', false, true,  'A portfolio item waits for review (to the class''s reviewing teachers; generic text, never the title or the child)', false, false),
  ('PORTFOLIO_NEW_ITEM',      'LEARNING',  '{IN_APP,PUSH}', false, true,  'A guardian-visible portfolio item of the child was approved (to the guardians, once per item; generic text)', false, false),
  ('PORTFOLIO_COMMENT',       'LEARNING',  '{IN_APP}',      false, true,  'Somebody commented on the student''s portfolio item (to the student; never the comment)', false, false)
on conflict (code) do update
  set category = excluded.category,
      default_channels = excluded.default_channels,
      can_be_urgent = excluded.can_be_urgent,
      user_can_disable = excluded.user_can_disable,
      description = excluded.description,
      defer_in_quiet_hours = excluded.defer_in_quiet_hours,
      urgent_overrides_preferences = excluded.urgent_overrides_preferences;
