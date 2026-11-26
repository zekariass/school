-- M9a · messaging (Database_Design §4.11; rule book: docs/Messaging_Rules.md).
--
-- Deviations from the design doc (also noted there):
--   * conversations: school_id is NOT NULL (P1 conversations belong to a school; COURSE and TUTORING arrive with P3/P4 with
--     their own columns). direct_kind and direct_key (dedupe: one thread per guardian/teacher/child), group_kind (CLASS_STUDENTS,
--     CLASS_PARENTS, STAFF, STUDENT_GROUP, SCHOOL_STAFF), safeguarded (the adult-student floor applies; R5), admin_visible
--     (no eligible observer: reviewers only, through the audited endpoint), activity_at (inbox order), approved_by/at
--     (student groups with approval). status adds PENDING_APPROVAL and REJECTED. avatar_media_id is not built.
--   * conversation_members: school_id (composite FK), left_reason, added_by, last_read_seq (the watermark as a number, see
--     messages.seq). acting_as is NOT NULL (TEACHER|GUARDIAN|STUDENT|STAFF; TUTOR comes with P4).
--   * messages: seq (a global increasing number given when the message is SENT; ordering, keyset paging and the read watermark
--     use it because UUIDv7 ids are only time-ordered to the millisecond), sent_at, sender_member_id (the capacity the message was
--     sent in: labels), system_code (SYSTEM messages), hidden_*, blocked_code (a scheduled message that could not be sent),
--     notified_at (the delivery listener's claim, makes it idempotent). status adds CANCELLED (scheduled message withdrawn) and
--     BLOCKED (scheduled message the policy no longer allowed). The body of a DELETED message is moved to message_edits and nulled.
--   * message_edits (new): the history of a message: every earlier text of an edit and the original of a deletion.
--   * message_reports (new): one row per report (message_reported audit + event MessageReported carry ids only).
--   * message_attachments, message_reads, message_reactions: as designed (reads and reactions add school-less composite PKs only).
--   * user_blocks: school_id (the school the block was made in; blocks by students of adults are listed for reviewers),
--     blocker_is_student, via_conversation_id.
--   * media.context_type gains CONVERSATION (media of a message belongs to its conversation).

alter table media drop constraint media_context_type_check;
alter table media add constraint media_context_type_check
  check (context_type in ('USER', 'SCHOOL', 'ORGANIZATION', 'CLASS', 'CONVERSATION'));

create sequence message_seq;

create table conversations (
  id                  uuid primary key default uuidv7(),
  conversation_type   text not null check (conversation_type in ('DIRECT', 'GROUP', 'CLASS', 'SCHOOL')),
  school_id           uuid not null references schools,
  class_id            uuid,
  context_student_id  uuid references student_profiles,
  direct_kind         text check (direct_kind in ('GUARDIAN_TEACHER', 'GUARDIAN_GUARDIAN', 'TEACHER_STUDENT', 'STUDENT_STUDENT')),
  direct_key          text,
  group_kind          text check (group_kind in ('CLASS_STUDENTS', 'CLASS_PARENTS', 'STAFF', 'STUDENT_GROUP', 'SCHOOL_STAFF')),
  title               text check (title is null or char_length(title) between 1 and 120),
  posting_policy      text not null default 'ALL_MEMBERS' check (posting_policy in ('ALL_MEMBERS', 'MODERATORS_ONLY')),
  status              text not null default 'ACTIVE'
                        check (status in ('PENDING_APPROVAL', 'ACTIVE', 'LOCKED', 'ARCHIVED', 'REJECTED')),
  safeguarded         boolean not null default false,
  admin_visible       boolean not null default false,
  created_by          uuid not null references users,
  approved_by         uuid references users,
  approved_at         timestamptz,
  last_message_at     timestamptz,
  activity_at         timestamptz not null default now(),
  unique (id, school_id),
  foreign key (class_id, school_id) references classes (id, school_id),
  check ((conversation_type = 'DIRECT') = (direct_kind is not null)),
  check ((conversation_type = 'DIRECT') = (direct_key is not null)),
  check ((conversation_type = 'DIRECT') = (group_kind is null)),
  check (conversation_type <> 'CLASS' or (class_id is not null and group_kind in ('CLASS_STUDENTS', 'CLASS_PARENTS'))),
  check (conversation_type <> 'GROUP' or group_kind in ('STAFF', 'STUDENT_GROUP')),
  check (conversation_type <> 'SCHOOL' or group_kind = 'SCHOOL_STAFF'),
  check (group_kind is distinct from 'STUDENT_GROUP' or class_id is not null),
  check (direct_kind is distinct from 'GUARDIAN_TEACHER' or context_student_id is not null),
  check (direct_kind is distinct from 'TEACHER_STUDENT' or context_student_id is not null),
  check (safeguarded = (direct_kind = 'TEACHER_STUDENT') or conversation_type <> 'DIRECT'),
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create unique index ux_conversation_direct on conversations (school_id, direct_key) where direct_key is not null;
create unique index ux_conversation_class_kind on conversations (class_id, group_kind)
  where group_kind in ('CLASS_STUDENTS', 'CLASS_PARENTS') and status <> 'REJECTED';
create index ix_conversations_school on conversations (school_id, activity_at desc, id desc);
create index ix_conversations_class on conversations (class_id) where class_id is not null;
create index ix_conversations_student on conversations (context_student_id) where context_student_id is not null;
create index ix_conversations_creator on conversations (created_by);
create index ix_conversations_review on conversations (school_id, id desc) where safeguarded or admin_visible;
create index ix_conversations_approver on conversations (approved_by) where approved_by is not null;

create table conversation_members (
  id                      uuid primary key default uuidv7(),
  conversation_id         uuid not null,
  school_id               uuid not null,
  user_id                 uuid not null references users,
  member_role             text not null default 'MEMBER' check (member_role in ('OWNER', 'MODERATOR', 'MEMBER', 'OBSERVER')),
  acting_as               text not null check (acting_as in ('TEACHER', 'GUARDIAN', 'STUDENT', 'STAFF')),
  on_behalf_of_student_id uuid references student_profiles,
  joined_at               timestamptz not null default now(),
  left_at                 timestamptz,
  left_reason             text check (left_reason in ('REMOVED', 'LEFT', 'ROSTER', 'LINK_CHANGED')),
  removed_by              uuid references users,
  added_by                uuid references users,
  muted_until             timestamptz,
  last_read_message_id    uuid,
  last_read_seq           bigint not null default 0,
  last_read_at            timestamptz,
  foreign key (conversation_id, school_id) references conversations (id, school_id),
  check ((left_at is null) = (left_reason is null)),
  check (member_role <> 'OBSERVER' or acting_as = 'GUARDIAN')
);
create unique index ux_conversation_member on conversation_members (conversation_id, user_id) where left_at is null;
create index ix_member_user on conversation_members (user_id, conversation_id) where left_at is null;
create index ix_member_conversation on conversation_members (conversation_id, member_role) where left_at is null;
create index ix_member_student on conversation_members (on_behalf_of_student_id) where on_behalf_of_student_id is not null;
create index ix_member_removed_by on conversation_members (removed_by) where removed_by is not null;
create index ix_member_added_by on conversation_members (added_by) where added_by is not null;
create index ix_member_school on conversation_members (school_id);

create table messages (
  id                  uuid primary key default uuidv7(),
  conversation_id     uuid not null,
  school_id           uuid not null,
  sender_id           uuid not null references users,
  sender_member_id    uuid not null references conversation_members,
  message_type        text not null default 'TEXT' check (message_type in ('TEXT', 'MEDIA', 'VOICE', 'SYSTEM')),
  system_code         text,
  body                text check (body is null or char_length(body) <= 2000),
  body_language_code  text references languages,
  reply_to_message_id uuid references messages,
  client_message_id   uuid not null,
  send_at             timestamptz,
  status              text not null default 'SENT'
                        check (status in ('SCHEDULED', 'SENT', 'EDITED', 'DELETED', 'HIDDEN', 'CANCELLED', 'BLOCKED')),
  seq                 bigint unique,
  sent_at             timestamptz,
  edited_at           timestamptz,
  deleted_at          timestamptz,
  hidden_at           timestamptz,
  hidden_by           uuid references users,
  hide_reason         text check (hide_reason is null or char_length(hide_reason) <= 300),
  blocked_code        text,
  notified_at         timestamptz,
  created_at          timestamptz not null default now(),
  foreign key (conversation_id, school_id) references conversations (id, school_id),
  unique (sender_id, client_message_id),
  check (status not in ('SENT', 'EDITED') or seq is not null),
  check (status <> 'SCHEDULED' or send_at is not null),
  check (message_type <> 'SYSTEM' or system_code is not null)
);
create index ix_messages_conversation on messages (conversation_id, seq desc) where seq is not null;
create index ix_messages_due on messages (send_at, id) where status = 'SCHEDULED';
create index ix_messages_unnotified on messages (seq) where notified_at is null and seq is not null and message_type <> 'SYSTEM';
create index ix_messages_sender on messages (sender_id, seq desc);
create index ix_messages_sender_member on messages (sender_member_id);
create index ix_messages_reply on messages (reply_to_message_id) where reply_to_message_id is not null;
create index ix_messages_hidden_by on messages (hidden_by) where hidden_by is not null;
create index ix_messages_school on messages (school_id);

alter table conversation_members
  add constraint fk_member_last_read foreign key (last_read_message_id) references messages (id) on delete set null;
create index ix_member_last_read on conversation_members (last_read_message_id) where last_read_message_id is not null;

create table message_edits (
  id          uuid primary key default uuidv7(),
  message_id  uuid not null references messages on delete cascade,
  kind        text not null check (kind in ('EDIT', 'DELETE')),
  body        text not null,
  edited_by   uuid not null references users,
  edited_at   timestamptz not null default now()
);
create index ix_message_edits_message on message_edits (message_id, edited_at, id);
create index ix_message_edits_by on message_edits (edited_by);

create table message_attachments (
  message_id      uuid not null references messages on delete cascade,
  media_id        uuid not null references media,
  sort_order      smallint not null default 0,
  primary key (message_id, media_id)
);
create unique index ux_message_attachment_media on message_attachments (media_id);

create table message_reads (
  message_id      uuid not null references messages on delete cascade,
  user_id         uuid not null references users,
  read_at         timestamptz not null default now(),
  primary key (message_id, user_id)
);
create index ix_message_reads_user on message_reads (user_id);

create table message_reactions (
  message_id      uuid not null references messages on delete cascade,
  user_id         uuid not null references users,
  emoji           text not null check (char_length(emoji) <= 16),
  created_at      timestamptz not null default now(),
  primary key (message_id, user_id, emoji)
);
create index ix_message_reactions_user on message_reactions (user_id);

create table message_reports (
  id              uuid primary key default uuidv7(),
  message_id      uuid not null references messages on delete cascade,
  conversation_id uuid not null references conversations,
  school_id       uuid not null references schools,
  reporter_id     uuid not null references users,
  reason          text not null check (reason in ('INAPPROPRIATE', 'BULLYING', 'PRIVACY', 'SAFETY', 'SPAM', 'OTHER')),
  note            text check (note is null or char_length(note) <= 500),
  status          text not null default 'OPEN' check (status in ('OPEN', 'RESOLVED')),
  created_at      timestamptz not null default now(),
  unique (message_id, reporter_id)
);
create index ix_message_reports_school on message_reports (school_id, status, created_at desc, id desc);
create index ix_message_reports_conversation on message_reports (conversation_id, status);
create index ix_message_reports_reporter on message_reports (reporter_id, created_at);

create table user_blocks (
  id                  uuid not null unique default uuidv7(),
  blocker_id          uuid not null references users,
  blocked_id          uuid not null references users,
  school_id           uuid not null references schools,
  blocker_is_student  boolean not null default false,
  via_conversation_id uuid references conversations,
  reason              text,
  created_at          timestamptz not null default now(),
  primary key (blocker_id, blocked_id)
);
create index ix_user_blocks_blocked on user_blocks (blocked_id);
create index ix_user_blocks_school on user_blocks (school_id, created_at desc) where blocker_is_student;
create index ix_user_blocks_conversation on user_blocks (via_conversation_id) where via_conversation_id is not null;
