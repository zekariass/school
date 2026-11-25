-- M5a · guardians (Database_Design §4.4, R-10, R-29): guardian profiles, the student-guardian relationship and
-- invitations (guardian links only so far).
--
-- Deviations from the design doc (also noted there):
--   * invitations.course_id is omitted (P3); invitations.import_job_id is omitted (the M4 import module adds it when
--     it wires staged guardian contacts to invitations).
--   * invitations.destination is split into destination_e164 / destination_email (normalized, unverified: an
--     invited destination never proves guardianship), plus guardian_name (what the inviter typed, a hint for
--     verifiers), max_uses / use_count (printed parent codes are multi-use), code_hash instead of short_code
--     (HMAC-SHA256(pepper, 'parent-code:' || code); the code itself is never stored), revoked_at / revoked_by.
--     token_hash is the SHA-256 of a 256-bit random link token. "Live" = PENDING or SENT, not revoked, not expired,
--     use_count < max_uses; EXPIRED is derived from expires_at (no job flips it yet).
--   * student_guardians adds requested_via (INVITE_LINK|PARENT_CODE|ADMIN), invitation_id, the verification decision
--     (decided_by / decided_at / decision_note; decision_note is internal to the school), the std columns, and one
--     live primary guardian per student. restriction_note stays admin-only (never in audit rows or events).
--   * Enumerations get CHECK constraints (DB §1).

create table guardian_profiles (
  user_id                   uuid primary key references users,
  preferred_contact_channel text check (preferred_contact_channel is null
                                        or preferred_contact_channel in ('PUSH', 'SMS', 'EMAIL', 'VOICE')),
  created_at                timestamptz not null default now()
);

create table invitations (
  id                  uuid primary key default uuidv7(),
  invitation_type     text not null check (invitation_type in ('GUARDIAN_LINK', 'STAFF', 'STUDENT_ACCOUNT', 'CLASS_JOIN')),
  school_id           uuid references schools,
  class_id            uuid,
  student_id          uuid references student_profiles,            -- for GUARDIAN_LINK
  relationship_type   text check (relationship_type is null or relationship_type in
                        ('MOTHER', 'FATHER', 'GRANDPARENT', 'SIBLING', 'AUNT_UNCLE', 'LEGAL_GUARDIAN', 'OTHER')),
  guardian_name       text,                                        -- typed by the inviter; not proof of anything
  payload             jsonb,
  channel             text not null check (channel in ('SMS', 'EMAIL', 'LINK', 'PRINTED_CODE')),
  destination_e164    text check (destination_e164 is null or destination_e164 ~ '^\+[1-9][0-9]{6,14}$'),
  destination_email   citext,
  token_hash          text unique,                                 -- SHA-256 (hex) of the link token
  code_hash           text unique,                                 -- HMAC-SHA256(pepper, 'parent-code:' || code)
  status              text not null default 'PENDING'
                        check (status in ('PENDING', 'SENT', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
  max_uses            smallint not null default 1 check (max_uses between 1 and 50),
  use_count           smallint not null default 0 check (use_count >= 0),
  invited_by          uuid not null references users,
  accepted_by         uuid references users,                       -- the latest acceptor (multi-use codes)
  accepted_at         timestamptz,
  expires_at          timestamptz not null,
  revoked_at          timestamptz,
  revoked_by          uuid references users,
  created_at          timestamptz not null default now(),
  foreign key (class_id, school_id) references classes (id, school_id),
  check (use_count <= max_uses),
  check (token_hash is not null or code_hash is not null),
  check (invitation_type <> 'GUARDIAN_LINK' or (student_id is not null and school_id is not null)),
  check ((revoked_at is null) = (status <> 'REVOKED'))
);
create index ix_invitations_student_live on invitations (student_id, created_at)
  where status in ('PENDING', 'SENT');
create index ix_invitations_school on invitations (school_id, created_at);
create index ix_invitations_class on invitations (class_id) where class_id is not null;
create index ix_invitations_invited_by on invitations (invited_by);
create index ix_invitations_accepted_by on invitations (accepted_by) where accepted_by is not null;
create index ix_invitations_revoked_by on invitations (revoked_by) where revoked_by is not null;

-- Guardian relationship: global, verified by a school (R-29)
create table student_guardians (
  id                        uuid primary key default uuidv7(),
  student_id                uuid not null references student_profiles,
  guardian_id               uuid not null references guardian_profiles,
  relationship_type         text not null check (relationship_type in
                              ('MOTHER', 'FATHER', 'GRANDPARENT', 'SIBLING', 'AUNT_UNCLE', 'LEGAL_GUARDIAN', 'OTHER')),
  is_primary                boolean not null default false,
  status                    text not null default 'PENDING' check (status in ('PENDING', 'ACTIVE', 'REJECTED', 'REVOKED')),
  requested_via             text not null check (requested_via in ('INVITE_LINK', 'PARENT_CODE', 'ADMIN')),
  invitation_id             uuid references invitations,
  verification_method       text check (verification_method is null or verification_method in
                              ('SCHOOL_INVITE', 'SCHOOL_VERIFIED', 'DOCUMENT', 'PLATFORM_ADMIN')),
  verified_by_school_id     uuid references schools,
  verified_by               uuid references users,
  verified_at               timestamptz,
  decided_by                uuid references users,                 -- who approved or rejected (verification audit)
  decided_at                timestamptz,
  decision_note             text,
  can_view_academics        boolean not null default true,
  can_message_staff         boolean not null default true,
  can_receive_notifications boolean not null default true,
  can_award_points          boolean not null default true,
  can_give_consent          boolean not null default true,
  can_book_and_pay          boolean not null default true,
  custody_restricted        boolean not null default false,       -- court order etc.; overrides every flag above
  restriction_note          text,                                  -- visible to school.guardians.manage only
  revoked_at                timestamptz,
  revoked_by                uuid references users,
  -- std
  created_at                timestamptz not null default now(),
  updated_at                timestamptz not null default now(),
  version                   int not null default 0,
  check (status <> 'ACTIVE' or verified_at is not null),
  check ((revoked_at is null) = (status <> 'REVOKED'))
);
create unique index ux_student_guardian_live on student_guardians (student_id, guardian_id)
  where status in ('PENDING', 'ACTIVE');
create unique index ux_student_guardian_primary on student_guardians (student_id)
  where is_primary and status in ('PENDING', 'ACTIVE');
create index ix_student_guardians_guardian_active on student_guardians (guardian_id) where status = 'ACTIVE';
create index ix_student_guardians_guardian on student_guardians (guardian_id);
create index ix_student_guardians_student on student_guardians (student_id, status);
create index ix_student_guardians_pending on student_guardians (created_at, id) where status = 'PENDING';
create index ix_student_guardians_invitation on student_guardians (invitation_id) where invitation_id is not null;
create index ix_student_guardians_school on student_guardians (verified_by_school_id)
  where verified_by_school_id is not null;
create index ix_student_guardians_verified_by on student_guardians (verified_by) where verified_by is not null;
create index ix_student_guardians_decided_by on student_guardians (decided_by) where decided_by is not null;
create index ix_student_guardians_revoked_by on student_guardians (revoked_by) where revoked_by is not null;
