-- Scope-revision cleanup (Spec_Review D32-D40; Implementation_Plan C1, U-01 to U-04).
-- There are no online courses, video, recordings, tutor bookings or AI tutor, so the values that only they used leave
-- the database. Nothing is deployed yet and the API cannot create any of the removed values, but this migration must
-- also be safe on a database that someone filled by hand:
--   * feature flags and their entitlements are configuration owned by the code: they are deleted (children first);
--     the repeatable seeds (R__20, R__30) no longer list them, so they are not re-created;
--   * for the check constraints, rows that carry a removed value are never deleted silently (consent_records is a
--     legal record, moderation cases are evidence): the migration REFUSES with a message that names the table, so an
--     operator decides what to do with the data before narrowing the constraint.

-- U-01 --------------------------------------------------------------------------------------------------------------
delete from plan_entitlements
 where feature_key in ('courses', 'marketplace', 'tutoring', 'ai_copilot', 'ai_student_tutor');
delete from feature_overrides
 where feature_key in ('courses', 'marketplace', 'tutoring', 'ai_copilot', 'ai_student_tutor');
delete from feature_definitions
 where key in ('courses', 'marketplace', 'tutoring', 'ai_copilot', 'ai_student_tutor');

-- Guards (U-02 to U-04) ---------------------------------------------------------------------------------------------
do $$
begin
  if exists (select 1 from moderation_cases where target_type in ('COURSE', 'TUTOR_REVIEW', 'AI_OUTPUT')) then
    raise exception 'scope cleanup: moderation_cases holds target types COURSE, TUTOR_REVIEW or AI_OUTPUT; resolve and remove them first';
  end if;
  if exists (select 1 from content_reports where target_type in ('COURSE', 'TUTOR_REVIEW', 'AI_OUTPUT')) then
    raise exception 'scope cleanup: content_reports holds target types COURSE, TUTOR_REVIEW or AI_OUTPUT; resolve and remove them first';
  end if;
  if exists (select 1 from events where scope_type = 'COURSE' or course_id is not null) then
    raise exception 'scope cleanup: events holds COURSE scope or a course_id; remove those events first';
  end if;
  if exists (select 1 from policy_documents where policy_type in ('RECORDING', 'TRANSCRIPTION')) then
    raise exception 'scope cleanup: policy_documents holds types RECORDING or TRANSCRIPTION; remove them first';
  end if;
  if exists (select 1 from consent_records where consent_type in ('RECORDING', 'TRANSCRIPTION')) then
    raise exception 'scope cleanup: consent_records holds types RECORDING or TRANSCRIPTION; they are legal records, review them first';
  end if;
end $$;

-- U-02: moderation target types (content_reports.target_type has no check; it follows its case) ----------------------
alter table moderation_cases drop constraint moderation_cases_target_type_check;
alter table moderation_cases add constraint moderation_cases_target_type_check
  check (target_type in ('POST', 'POST_COMMENT', 'STORY', 'MESSAGE', 'USER', 'PORTFOLIO_ITEM', 'TUTOR_PROFILE',
                         'LIBRARY_RESOURCE', 'MEDIA'));

-- U-03: event scope; events.course_id never had a foreign key or an index ---------------------------------------------
alter table events drop constraint events_scope_type_check;
alter table events add constraint events_scope_type_check
  check (scope_type in ('SCHOOL', 'CAMPUS', 'GRADE_LEVEL', 'CLASS', 'PERSONAL', 'INVITEES'));
alter table events drop column course_id;

-- U-04: policy and consent types (AI_PROCESSING stays for the deferred assistant) ------------------------------------
alter table policy_documents drop constraint policy_documents_policy_type_check;
alter table policy_documents add constraint policy_documents_policy_type_check
  check (policy_type in ('TERMS', 'PRIVACY', 'ACCOUNT_PARTICIPATION', 'MEDIA_PUBLICATION', 'AI_PROCESSING',
                         'RESEARCH_ANALYTICS'));
alter table consent_records drop constraint consent_records_consent_type_check;
alter table consent_records add constraint consent_records_consent_type_check
  check (consent_type in ('TERMS', 'PRIVACY', 'ACCOUNT_PARTICIPATION', 'MEDIA_PUBLICATION', 'AI_PROCESSING',
                          'RESEARCH_ANALYTICS'));
