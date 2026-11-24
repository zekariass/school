-- Reference data seed (repeatable, idempotent). Flyway re-runs this file whenever its
-- checksum changes, so every statement must be safe to run again.
--
-- Admin-editable data (country status, language enabled flags, ...) uses
-- ON CONFLICT DO NOTHING: an operator's edit is never overwritten by a redeploy.
-- To add a row, add it here; to change an existing row, edit it through the admin API
-- or write a versioned migration.

insert into currencies (code, name, symbol, minor_units) values
  ('ETB', 'Ethiopian Birr', 'Br', 2),
  ('KES', 'Kenyan Shilling', 'KSh', 2),
  ('USD', 'US Dollar', '$', 2)
on conflict (code) do nothing;

insert into languages (code, name, native_name, script, direction) values
  ('am', 'Amharic', 'አማርኛ', 'Ethi', 'LTR'),
  ('en', 'English', 'English', 'Latn', 'LTR'),
  ('om', 'Oromo', 'Afaan Oromoo', 'Latn', 'LTR'),
  ('ti', 'Tigrinya', 'ትግርኛ', 'Ethi', 'LTR'),
  ('so', 'Somali', 'Soomaali', 'Latn', 'LTR'),
  ('sw', 'Swahili', 'Kiswahili', 'Latn', 'LTR')
on conflict (code) do nothing;

insert into calendar_systems (code, name) values
  ('GREGORIAN', 'Gregorian'),
  ('ETHIOPIAN', 'Ethiopian')
on conflict (code) do nothing;

-- Ethiopia is launched; Kenya is prepared as a draft (not selectable for new schools).
insert into countries (code, name_i18n, default_currency_code, default_language_code, default_timezone,
                       default_calendar_code, phone_country_code, address_format, name_format, status, launched_at)
values
  ('ET',
   '{"en": "Ethiopia", "am": "ኢትዮጵያ"}',
   'ETB', 'am', 'Africa/Addis_Ababa', 'ETHIOPIAN', '+251',
   '{"levels": [
       {"level": 1, "required": true,  "labels": {"en": "Region",  "am": "ክልል"}},
       {"level": 2, "required": false, "labels": {"en": "Zone",    "am": "ዞን"}},
       {"level": 3, "required": false, "labels": {"en": "Woreda",  "am": "ወረዳ"}},
       {"level": 4, "required": false, "labels": {"en": "Kebele",  "am": "ቀበሌ"}}
     ],
     "postalCode": {"required": false}}',
   '{"given_name":  {"en": "Given name",         "am": "የራስ ስም"},
     "middle_name": {"en": "Father''s name",      "am": "የአባት ስም"},
     "family_name": {"en": "Grandfather''s name", "am": "የአያት ስም"}}',
   'ENABLED', timestamptz '2026-01-01 00:00:00+00'),
  ('KE',
   '{"en": "Kenya", "am": "ኬንያ"}',
   'KES', 'en', 'Africa/Nairobi', 'GREGORIAN', '+254',
   '{"levels": [
       {"level": 1, "required": true,  "labels": {"en": "County",     "am": "ካውንቲ"}},
       {"level": 2, "required": false, "labels": {"en": "Sub-county", "am": "ንዑስ ካውንቲ"}},
       {"level": 3, "required": false, "labels": {"en": "Ward",       "am": "ዋርድ"}}
     ],
     "postalCode": {"required": true}}',
   '{"given_name":  {"en": "Given name",  "am": "የራስ ስም"},
     "middle_name": {"en": "Middle name", "am": "የመካከለኛ ስም"},
     "family_name": {"en": "Family name", "am": "የቤተሰብ ስም"}}',
   'DRAFT', null)
on conflict (code) do nothing;

insert into country_languages (country_code, language_code, is_default, is_official, sort_order) values
  ('ET', 'am', true,  true,  0),
  ('ET', 'en', false, false, 1),
  ('ET', 'om', false, false, 2),
  ('ET', 'ti', false, false, 3),
  ('ET', 'so', false, false, 4),
  ('KE', 'en', true,  true,  0),
  ('KE', 'sw', false, true,  1)
on conflict (country_code, language_code) do nothing;

insert into country_calendars (country_code, calendar_code, is_default) values
  ('ET', 'ETHIOPIAN', true),
  ('ET', 'GREGORIAN', false),
  ('KE', 'GREGORIAN', true)
on conflict (country_code, calendar_code) do nothing;

-- Ethiopia, administrative level 1: regional states and chartered cities as of 2026.
-- Listed alphabetically by English name: ids are UUIDv7 generated in this order, so the
-- default id ordering of GET /reference/countries/ET/administrative-areas is alphabetical.
-- Codes follow the ISO 3166-2:ET suffixes where one exists (AA, AF, AM, BE, DD, GA, HA,
-- OR, SI, SO, SW, TI); CE and SE are platform codes for the newer regions.
-- Level 2 (zones) and level 3 (woredas) are deferred to a data import (CSA / OCHA
-- administrative boundaries), not hand-maintained here.
insert into administrative_areas (country_code, level, code, name_i18n) values
  ('ET', 1, 'AA', '{"en": "Addis Ababa", "am": "አዲስ አበባ"}'),
  ('ET', 1, 'AF', '{"en": "Afar", "am": "አፋር"}'),
  ('ET', 1, 'AM', '{"en": "Amhara", "am": "አማራ"}'),
  ('ET', 1, 'BE', '{"en": "Benishangul-Gumuz", "am": "ቤኒሻንጉል ጉሙዝ"}'),
  ('ET', 1, 'CE', '{"en": "Central Ethiopia", "am": "ማዕከላዊ ኢትዮጵያ"}'),
  ('ET', 1, 'DD', '{"en": "Dire Dawa", "am": "ድሬዳዋ"}'),
  ('ET', 1, 'GA', '{"en": "Gambela", "am": "ጋምቤላ"}'),
  ('ET', 1, 'HA', '{"en": "Harari", "am": "ሐረሪ"}'),
  ('ET', 1, 'OR', '{"en": "Oromia", "am": "ኦሮሚያ"}'),
  ('ET', 1, 'SI', '{"en": "Sidama", "am": "ሲዳማ"}'),
  ('ET', 1, 'SO', '{"en": "Somali", "am": "ሶማሌ"}'),
  ('ET', 1, 'SE', '{"en": "South Ethiopia", "am": "ደቡብ ኢትዮጵያ"}'),
  ('ET', 1, 'SW', '{"en": "South West Ethiopia Peoples''", "am": "ደቡብ ምዕራብ ኢትዮጵያ ሕዝቦች"}'),
  ('ET', 1, 'TI', '{"en": "Tigray", "am": "ትግራይ"}')
on conflict (country_code, level, code) do nothing;
