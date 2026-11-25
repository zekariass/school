-- Platform templates of the engagement module (repeatable, idempotent): the default point categories and badges.
-- A school gets its own editable copy of the templates the first time it uses points (EngagementSeeding), so a
-- change here reaches new schools only. Names are original (not another product's); Amharic names are drafts that
-- need native-speaker review. Icons are client icon keys. Negative ("needs work") categories carry a positive
-- default_points: the sign comes from the polarity.

insert into point_categories (template_code, name_i18n, icon, color, default_points, polarity, context, sort_order)
select t.template_code, t.name_i18n::jsonb, t.icon, t.color, t.default_points, t.polarity, t.context, t.sort_order
from (values
  ('helping',        '{"en": "Lending a hand", "am": "እጅ መስጠት"}',                'hand-helping',   '#2E9E6B', 1, 'POSITIVE',  'CLASS', 10),
  ('participation',  '{"en": "Joining in", "am": "በንቃት መሳተፍ"}',                  'hand-raised',    '#2F7DD1', 1, 'POSITIVE',  'CLASS', 20),
  ('teamwork',       '{"en": "Team spirit", "am": "የቡድን መንፈስ"}',                 'users',          '#8B5CF6', 1, 'POSITIVE',  'CLASS', 30),
  ('persistence',    '{"en": "Never giving up", "am": "ተስፋ አለመቁረጥ"}',            'mountain',       '#E07A1F', 2, 'POSITIVE',  'CLASS', 40),
  ('on_task',        '{"en": "Staying focused", "am": "ትኩረት ማድረግ"}',             'target',         '#0E9AA7', 1, 'POSITIVE',  'CLASS', 50),
  ('kindness',       '{"en": "Kind words", "am": "ደግ ቃላት"}',                     'heart',          '#E0457B', 1, 'POSITIVE',  'CLASS', 60),
  ('reading',        '{"en": "Great reading", "am": "ጥሩ ንባብ"}',                  'book-open',      '#B7791F', 1, 'POSITIVE',  'CLASS', 70),
  ('great_thinking', '{"en": "Great thinking", "am": "ጥሩ አስተሳሰብ"}',              'lightbulb',      '#D4A017', 2, 'POSITIVE',  'CLASS', 80),
  ('disruptive',     '{"en": "Disturbing others", "am": "ሌሎችን ማወክ"}',            'volume-high',    '#C2410C', 1, 'NEEDS_WORK', 'CLASS', 110),
  ('unprepared',     '{"en": "Missing materials", "am": "ዝግጅት ማጣት"}',            'backpack',       '#B45309', 1, 'NEEDS_WORK', 'CLASS', 120),
  ('off_task',       '{"en": "Wandering off task", "am": "ከሥራ መራቅ"}',             'compass',        '#A16207', 1, 'NEEDS_WORK', 'CLASS', 130),
  ('unkind_words',   '{"en": "Unkind words", "am": "ክፉ ቃላት"}',                   'message-x',      '#9F1239', 1, 'NEEDS_WORK', 'CLASS', 140),
  ('home_homework',  '{"en": "Homework done", "am": "የቤት ሥራ ተሠርቷል"}',            'pencil',         '#2F7DD1', 1, 'POSITIVE',  'HOME', 210),
  ('home_reading',   '{"en": "Reading at home", "am": "ቤት ውስጥ ማንበብ"}',          'book-open',      '#B7791F', 1, 'POSITIVE',  'HOME', 220),
  ('home_helping',   '{"en": "Helping at home", "am": "ቤት ውስጥ መርዳት"}',           'house',          '#2E9E6B', 1, 'POSITIVE',  'HOME', 230),
  ('home_manners',   '{"en": "Good manners", "am": "መልካም ሥነ ምግባር"}',            'smile',          '#E0457B', 1, 'POSITIVE',  'HOME', 240)
) as t(template_code, name_i18n, icon, color, default_points, polarity, context, sort_order)
on conflict (template_code) where school_id is null and template_code is not null do update
  set name_i18n = excluded.name_i18n,
      icon = excluded.icon,
      color = excluded.color,
      default_points = excluded.default_points,
      polarity = excluded.polarity,
      context = excluded.context,
      sort_order = excluded.sort_order;

-- Badge templates. criteria: POINTS_TOTAL {"threshold": n, "context": "CLASS"|"SCHOOL"?} (net points of CLASS and SCHOOL
-- context, HOME never counts, D2); CATEGORY_TOTAL {"threshold": n, "templateCode": "..."} or {"categoryId": "..."};
-- ATTENDANCE_STREAK {"days": n} (granted by the attendance module, M7); MANUAL (a teacher awards it).
insert into badges (school_id, code, name_i18n, description_i18n, icon, criteria_type, criteria, sort_order)
select null, t.code, t.name_i18n::jsonb, t.description_i18n::jsonb, t.icon, t.criteria_type, t.criteria::jsonb, t.sort_order
from (values
  ('first_steps',         '{"en": "First steps", "am": "የመጀመሪያ እርምጃ"}',
   '{"en": "Earned the first 10 points", "am": "የመጀመሪያዎቹን 10 ነጥቦች አግኝቷል"}',
   'sprout', 'POINTS_TOTAL', '{"threshold": 10}', 10),
  ('rising_star',         '{"en": "Rising star", "am": "እየወጣ ያለ ኮከብ"}',
   '{"en": "Reached 50 points", "am": "50 ነጥብ ደርሷል"}',
   'star', 'POINTS_TOTAL', '{"threshold": 50}', 20),
  ('century',             '{"en": "100 points", "am": "100 ነጥብ"}',
   '{"en": "Reached 100 points", "am": "100 ነጥብ ደርሷል"}',
   'trophy', 'POINTS_TOTAL', '{"threshold": 100}', 30),
  ('helpful_friend',      '{"en": "Helpful friend", "am": "ረዳት ጓደኛ"}',
   '{"en": "Lent a hand 15 times", "am": "15 ጊዜ እጅ ሰጥቷል"}',
   'hand-helping', 'CATEGORY_TOTAL', '{"threshold": 15, "templateCode": "helping"}', 40),
  ('reading_champion',    '{"en": "Reading champion", "am": "የንባብ ሻምፒዮን"}',
   '{"en": "Great reading 15 times", "am": "15 ጊዜ ጥሩ አንብቧል"}',
   'book-open', 'CATEGORY_TOTAL', '{"threshold": 15, "templateCode": "reading"}', 50),
  ('team_player',         '{"en": "Team player", "am": "የቡድን ተጫዋች"}',
   '{"en": "Showed team spirit 15 times", "am": "15 ጊዜ የቡድን መንፈስ አሳይቷል"}',
   'users', 'CATEGORY_TOTAL', '{"threshold": 15, "templateCode": "teamwork"}', 60),
  ('excellent_participation', '{"en": "Excellent participation", "am": "ምርጥ ተሳትፎ"}',
   '{"en": "Joined in 15 times", "am": "15 ጊዜ በንቃት ተሳትፏል"}',
   'hand-raised', 'CATEGORY_TOTAL', '{"threshold": 15, "templateCode": "participation"}', 70),
  ('perfect_attendance',  '{"en": "Perfect attendance", "am": "ሙሉ ተገኝነት"}',
   '{"en": "Present every school day for 20 days in a row", "am": "ለተከታታይ 20 ቀናት በየትምህርት ቀኑ ተገኝቷል"}',
   'calendar-check', 'ATTENDANCE_STREAK', '{"days": 20}', 80),
  ('teachers_pick',       '{"en": "Teacher''s pick", "am": "የመምህሩ ምርጫ"}',
   '{"en": "Chosen by the teacher for something special", "am": "ለልዩ ሥራ በመምህሩ የተመረጠ"}',
   'award', 'MANUAL', null, 90)
) as t(code, name_i18n, description_i18n, icon, criteria_type, criteria, sort_order)
on conflict (school_id, code) do update
  set name_i18n = excluded.name_i18n,
      description_i18n = excluded.description_i18n,
      icon = excluded.icon,
      criteria_type = excluded.criteria_type,
      criteria = excluded.criteria,
      sort_order = excluded.sort_order;
