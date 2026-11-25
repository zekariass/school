-- Platform-default policy documents for Ethiopia (repeatable, code-owned; Database_Design §4.18, R-09).
--
-- !!! DRAFT PLACEHOLDER — REQUIRES LEGAL REVIEW BEFORE ANY PILOT !!!
-- These texts are short plain-language placeholders written by engineering so that the consent flows can be built
-- and tested. They have not been reviewed by counsel (Ethiopia's Personal Data Protection Proclamation
-- No. 1321/2024 and any education-sector rules) and the Amharic needs native-speaker review. Replace them with a new
-- version label (never edit a published version's text: people consented to that wording) before real families use
-- the platform.
--
-- Documents: ON CONFLICT DO UPDATE (the title is code-owned). Versions: ON CONFLICT DO NOTHING, because a published
-- version is immutable; a changed text needs a new version_label.

insert into policy_documents (policy_type, country_code, title) values
  ('ACCOUNT_PARTICIPATION', 'ET', 'Your child''s school account'),
  ('MEDIA_PUBLICATION',     'ET', 'Photos and videos of your child'),
  ('TERMS',                 'ET', 'Terms of use'),
  ('PRIVACY',               'ET', 'Privacy notice')
on conflict (policy_type, country_code) where school_id is null do update
  set title = excluded.title;

insert into policy_versions (policy_document_id, version_label, language_code, content, effective_from,
                             requires_reconsent, published_at)
select d.id, v.label, v.lang, v.content, timestamptz '2026-01-01 00:00:00+00', false, timestamptz '2026-01-01 00:00:00+00'
from policy_documents d
join (values
  ('ACCOUNT_PARTICIPATION', '2026-11-draft', 'en',
   'DRAFT PLACEHOLDER - requires legal review before any pilot.' || chr(10) || chr(10) ||
   'Your child''s school uses this app for class activities, points, attendance and messages from teachers. ' ||
   'The school creates your child''s account. We keep your child''s name, class, photo (if the school adds one) ' ||
   'and school records only to run these school services. Teachers of your child''s classes and school staff can ' ||
   'see them. We do not sell them or show advertising. You can say no or change your mind at any time; ask the ' ||
   'school what happens to your child''s account if you do.'),
  ('ACCOUNT_PARTICIPATION', '2026-11-draft', 'am',
   'ረቂቅ - ከመጠቀም በፊት የሕግ ግምገማ ያስፈልገዋል።' || chr(10) || chr(10) ||
   'የልጅዎ ትምህርት ቤት ይህን መተግበሪያ ለክፍል እንቅስቃሴዎች፣ ለነጥቦች፣ ለመገኘት መዝገብ እና ከመምህራን ለሚመጡ መልዕክቶች ይጠቀማል። ' ||
   'የልጅዎን መለያ የሚፈጥረው ትምህርት ቤቱ ነው። የልጅዎን ስም፣ ክፍል፣ ፎቶ (ትምህርት ቤቱ ካከለ) እና የትምህርት ቤት መዝገቦችን ' ||
   'የምንይዘው እነዚህን የትምህርት ቤት አገልግሎቶች ለመስጠት ብቻ ነው። የልጅዎ መምህራን እና የትምህርት ቤቱ ሠራተኞች ማየት ይችላሉ። ' ||
   'አንሸጣቸውም፤ ማስታወቂያም አናሳይም። በማንኛውም ጊዜ እምቢ ማለት ወይም ሃሳብዎን መቀየር ይችላሉ።'),
  ('MEDIA_PUBLICATION', '2026-11-draft', 'en',
   'DRAFT PLACEHOLDER - requires legal review before any pilot.' || chr(10) || chr(10) ||
   'Teachers may share photos and videos of class activities in the class story and the school story. ' ||
   'Only the class''s families, students and school staff can see the class story; the school story is seen by ' ||
   'the school''s families and staff. If you say yes, photos and videos in which your child can be seen may be ' ||
   'shared there. If you say no, they will not be. You can change your answer at any time; it applies to new ' ||
   'posts.'),
  ('MEDIA_PUBLICATION', '2026-11-draft', 'am',
   'ረቂቅ - ከመጠቀም በፊት የሕግ ግምገማ ያስፈልገዋል።' || chr(10) || chr(10) ||
   'መምህራን የክፍል እንቅስቃሴዎችን ፎቶዎችና ቪዲዮዎች በክፍሉ ታሪክ እና በትምህርት ቤቱ ታሪክ ውስጥ ሊያጋሩ ይችላሉ። ' ||
   'የክፍሉን ታሪክ የሚያዩት የክፍሉ ቤተሰቦች፣ ተማሪዎች እና የትምህርት ቤቱ ሠራተኞች ብቻ ናቸው። አዎ ካሉ ልጅዎ የሚታይባቸው ' ||
   'ፎቶዎችና ቪዲዮዎች እዚያ ሊጋሩ ይችላሉ። እምቢ ካሉ አይጋሩም። መልስዎን በማንኛውም ጊዜ መቀየር ይችላሉ።'),
  ('TERMS', '2026-11-draft', 'en',
   'DRAFT PLACEHOLDER - requires legal review before any pilot.' || chr(10) || chr(10) ||
   'Use this app to follow and support your child''s learning with their school. Be respectful in messages, ' ||
   'keep your sign-in codes private and do not share other children''s photos or information outside the app. ' ||
   'The school decides who is linked to which child.'),
  ('TERMS', '2026-11-draft', 'am',
   'ረቂቅ - ከመጠቀም በፊት የሕግ ግምገማ ያስፈልገዋል።' || chr(10) || chr(10) ||
   'ይህን መተግበሪያ የልጅዎን ትምህርት ከትምህርት ቤቱ ጋር ለመከታተልና ለመደገፍ ይጠቀሙበት። በመልዕክቶች ውስጥ አክብሮት ያሳዩ፣ ' ||
   'የመግቢያ ኮዶችዎን በሚስጥር ይያዙ፣ የሌሎች ልጆችን ፎቶዎች ወይም መረጃ ከመተግበሪያው ውጭ አያጋሩ። ማን ከየትኛው ልጅ ጋር ' ||
   'እንደሚገናኝ የሚወስነው ትምህርት ቤቱ ነው።'),
  ('PRIVACY', '2026-11-draft', 'en',
   'DRAFT PLACEHOLDER - requires legal review before any pilot.' || chr(10) || chr(10) ||
   'We keep your name, phone number or email, language and the children you are linked to, so that you can sign ' ||
   'in and the school can reach you. School staff of your children''s schools can see your name and how you are ' ||
   'related to the child. We do not sell your data. Ask the school or us to see, correct or delete it.'),
  ('PRIVACY', '2026-11-draft', 'am',
   'ረቂቅ - ከመጠቀም በፊት የሕግ ግምገማ ያስፈልገዋል።' || chr(10) || chr(10) ||
   'እንዲገቡ እና ትምህርት ቤቱ እንዲያገኝዎ ስምዎን፣ ስልክ ቁጥርዎን ወይም ኢሜይልዎን፣ ቋንቋዎን እና የተገናኙባቸውን ልጆች እንይዛለን። ' ||
   'የልጆችዎ ትምህርት ቤቶች ሠራተኞች ስምዎን እና ከልጁ ጋር ያለዎትን ዝምድና ማየት ይችላሉ። መረጃዎን አንሸጥም። ' ||
   'ለማየት፣ ለማስተካከል ወይም ለማጥፋት ትምህርት ቤቱን ወይም እኛን ይጠይቁ።')
) as v (policy_type, label, lang, content) on v.policy_type = d.policy_type
where d.country_code = 'ET' and d.school_id is null
on conflict on constraint ux_policy_versions_label do nothing;
