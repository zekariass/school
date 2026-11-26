-- M12a · student portfolios, basic (Database_Design §4.16; rule book: docs/Portfolio_Rules.md).
--
-- Deviations from the design doc (also noted there):
--   * Not created (P2): portfolio_collections, portfolio_feedback, collection_id. Items cannot be MIXED.
--   * portfolios: default_visibility defaults to GUARDIANS as designed, CHECKed; created lazily with the first item.
--   * portfolio_items: status defaults to DRAFT (the design said SUBMITTED; a student saves a draft first). item_type keeps
--     AUDIO and VIDEO reserved in the CHECK although the service refuses them (media pipeline limits, see the rule book);
--     title is required while the item lives and erased with the item's text on delete (the row stays as a tombstone);
--     shows_other_children and media_confirmed_by/_at carry the consent gate (R-09); archived_by/_at, deleted_by.
--   * portfolio_item_tags (new): other children who appear in the item's files; every tagged child needs media consent.
--   * portfolio_attachments: a file belongs to one item (unique media_id); caption is not built.
--   * portfolio_comments: school_id, status VISIBLE|HIDDEN|DELETED, hide reason, moderated_by/_at; the body is erased on delete.
--   * portfolio_approvals: school_id is not copied (the item carries it); the action vocabulary adds CAPTURED (teacher quick
--     capture), ARCHIVED, UNARCHIVED, UNFEATURED and DELETED; the table is append-only (trigger refuses UPDATE and DELETE).

create table portfolios (                        -- one per student per school
  id                  uuid primary key default uuidv7(),
  student_id          uuid not null references student_profiles,
  school_id           uuid not null references schools,
  default_visibility  text not null default 'GUARDIANS'
                        check (default_visibility in ('STUDENT_TEACHER', 'GUARDIANS', 'CLASS', 'SCHOOL')),
  unique (student_id, school_id),
  unique (id, school_id),
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create index ix_portfolios_school on portfolios (school_id);

create table portfolio_items (
  id                    uuid primary key default uuidv7(),
  portfolio_id          uuid not null,
  school_id             uuid not null,
  student_id            uuid not null references student_profiles,
  class_id              uuid,                    -- context; drives teacher review rights
  subject_id            uuid references subjects,
  created_by            uuid not null references users, -- the student, or a teacher's quick capture (§126)
  item_type             text not null
                          check (item_type in ('TEXT', 'PHOTO', 'DRAWING', 'SCAN', 'AUDIO', 'VIDEO', 'DOCUMENT')),
  title                 text check (title is null or char_length(title) between 1 and 120),
  description           text check (description is null or char_length(description) <= 1000),
  reflection            text check (reflection is null or char_length(reflection) <= 1000), -- "what I learned"
  text_content          text check (text_content is null or char_length(text_content) <= 5000),
  work_date             date,
  status                text not null default 'DRAFT'
                          check (status in ('DRAFT', 'SUBMITTED', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'ARCHIVED')),
  visibility            text not null default 'GUARDIANS'
                          check (visibility in ('STUDENT_TEACHER', 'GUARDIANS', 'CLASS', 'SCHOOL')),
  is_featured           boolean not null default false,
  shows_other_children  boolean not null default false,
  media_confirmed_by    uuid references users,   -- "no child without consent appears" (dropped when files or tags change)
  media_confirmed_at    timestamptz,
  submitted_at          timestamptz,
  reviewed_by           uuid references users,
  reviewed_at           timestamptz,
  archived_by           uuid references users,
  archived_at           timestamptz,
  deleted_at            timestamptz,
  deleted_by            uuid references users,
  foreign key (portfolio_id, school_id) references portfolios (id, school_id),
  foreign key (class_id, school_id) references classes (id, school_id),
  check (deleted_at is not null or title is not null),
  check (not is_featured or status = 'APPROVED'),
  check ((media_confirmed_by is null) = (media_confirmed_at is null)),
  unique (id, school_id),
  -- std
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  version               int not null default 0
);
create index ix_portfolio_items_student on portfolio_items (student_id, school_id, id desc) where deleted_at is null;
create index ix_portfolio_items_portfolio on portfolio_items (portfolio_id, school_id);
create index ix_portfolio_items_queue on portfolio_items (class_id, submitted_at, id)
  where status = 'SUBMITTED' and deleted_at is null;
create index ix_portfolio_items_class_gallery on portfolio_items (class_id, id desc)
  where status = 'APPROVED' and visibility in ('CLASS', 'SCHOOL') and deleted_at is null;
create index ix_portfolio_items_school_gallery on portfolio_items (school_id, id desc)
  where status = 'APPROVED' and visibility = 'SCHOOL' and deleted_at is null;
create index ix_portfolio_items_class_fk on portfolio_items (class_id, school_id) where class_id is not null;
create index ix_portfolio_items_subject on portfolio_items (subject_id) where subject_id is not null;
create index ix_portfolio_items_created_by on portfolio_items (created_by, id desc);
create index ix_portfolio_items_confirmed_by on portfolio_items (media_confirmed_by) where media_confirmed_by is not null;
create index ix_portfolio_items_reviewed_by on portfolio_items (reviewed_by) where reviewed_by is not null;
create index ix_portfolio_items_archived_by on portfolio_items (archived_by) where archived_by is not null;
create index ix_portfolio_items_deleted_by on portfolio_items (deleted_by) where deleted_by is not null;

create table portfolio_attachments (
  item_id         uuid not null references portfolio_items,
  media_id        uuid not null references media,
  sort_order      smallint not null default 0,
  primary key (item_id, media_id),
  unique (media_id)                               -- a file belongs to one item
);

-- Other children who appear in the item's files: the consent gate (R-09) checks each of them.
create table portfolio_item_tags (
  item_id         uuid not null references portfolio_items,
  student_id      uuid not null references student_profiles,
  primary key (item_id, student_id)
);
create index ix_portfolio_item_tags_student on portfolio_item_tags (student_id);

create table portfolio_comments (                -- encouragement thread (reviewers, guardians, student)
  id              uuid primary key default uuidv7(),
  item_id         uuid not null references portfolio_items,
  school_id       uuid not null references schools,
  author_id       uuid not null references users,
  author_role     text not null check (author_role in ('TEACHER', 'GUARDIAN', 'STUDENT')),
  body            text check (body is null or char_length(body) between 1 and 500),
  status          text not null default 'VISIBLE' check (status in ('VISIBLE', 'HIDDEN', 'DELETED')),
  hide_reason     text check (hide_reason is null or char_length(hide_reason) <= 200),
  moderated_by    uuid references users,
  moderated_at    timestamptz,
  created_at      timestamptz not null default now(),
  check (body is not null or status = 'DELETED')
);
create index ix_portfolio_comments_item on portfolio_comments (item_id, id);
create index ix_portfolio_comments_school on portfolio_comments (school_id);
create index ix_portfolio_comments_author on portfolio_comments (author_id);
create index ix_portfolio_comments_moderator on portfolio_comments (moderated_by) where moderated_by is not null;

create table portfolio_approvals (               -- status history (append-only)
  id              uuid primary key default uuidv7(),
  item_id         uuid not null references portfolio_items,
  action          text not null check (action in ('CAPTURED', 'SUBMITTED', 'APPROVED', 'CHANGES_REQUESTED', 'REJECTED',
                                                  'ARCHIVED', 'UNARCHIVED', 'FEATURED', 'UNFEATURED', 'DELETED')),
  from_status     text,
  to_status       text,
  actor_id        uuid not null references users,
  note            text check (note is null or char_length(note) <= 500),
  created_at      timestamptz not null default now()
);
create index ix_portfolio_approvals_item on portfolio_approvals (item_id, id);
create index ix_portfolio_approvals_actor on portfolio_approvals (actor_id);

create function portfolio_approvals_append_only() returns trigger language plpgsql as $$
begin
  raise exception 'portfolio_approvals is append-only';
end;
$$;
create trigger tr_portfolio_approvals_append_only before update or delete on portfolio_approvals
  for each row execute function portfolio_approvals_append_only();
