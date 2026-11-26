-- M8a · Class Story, School Story and feed (Database_Design §4.10).
--
-- Deviations from the design doc (also noted there):
--   * media.context_type gains CLASS: media of a class post or story belongs to the class (SCHOOL for school-wide ones)
--     and is read through the owning post or story (R-30).
--   * posts: no course_id/COURSE scope yet (P3); school_id is NOT NULL; status gains ARCHIVED (the author took the post
--     down) and keeps HIDDEN for a moderator's hide; decided_by/decided_at record an approval or a rejection (instead
--     of approved_by/approved_at); pinned_at orders pins; edited_at marks an edit; media_confirmed_by/_at keep the
--     teacher's "no child without consent appears" confirmation of untagged media (it is dropped when the media or the
--     tags change); publish_error_code is why a scheduled publish was blocked (the post went back to DRAFT);
--     deleted_by records who deleted it. Enumerations get CHECK constraints (DB §1).
--   * post_media/post_student_tags: school_id is not copied (the post carries it); a media file belongs to at most one
--     post (unique media_id), and never to a story as well (checked by the service).
--   * post_comments: author_role (to apply the name-visibility rules without a join); status VISIBLE|HIDDEN|DELETED
--     (PENDING_APPROVAL is not used); threading (parent_comment_id) exists but is not exposed yet.
--   * post_views.user_id and story_views.user_id are NOT NULL; only the count is ever read by teachers.
--   * stories: media_id is optional (a story may be a short text: a caption is then required); author_role, school_id as
--     in posts; status PUBLISHED|HIDDEN|EXPIRED|DELETED (no approval flow: stories are written by staff); deleted_at.
--     story_student_tags is new (consent gate and parent notifications, same as post_student_tags).
--   * content_translations: unchanged except for the CHECK constraints.

alter table media drop constraint media_context_type_check;
alter table media add constraint media_context_type_check
  check (context_type in ('USER', 'SCHOOL', 'ORGANIZATION', 'CLASS'));

create table posts (
  id                  uuid primary key default uuidv7(),
  scope_type          text not null check (scope_type in ('CLASS', 'SCHOOL')),
  school_id           uuid not null references schools,
  class_id            uuid,
  author_id           uuid not null references users,
  author_role         text not null check (author_role in ('TEACHER', 'SCHOOL_ADMIN', 'GUARDIAN', 'STUDENT')),
  post_type           text not null default 'POST' check (post_type in ('POST', 'ACTIVITY', 'ACHIEVEMENT')),
  body                text check (body is null or char_length(body) <= 5000),
  body_language_code  text references languages,
  is_contribution     boolean not null default false,
  status              text not null default 'DRAFT'
                        check (status in ('DRAFT', 'PENDING_APPROVAL', 'SCHEDULED', 'PUBLISHED', 'REJECTED', 'HIDDEN',
                                          'ARCHIVED')),
  scheduled_for       timestamptz,
  published_at        timestamptz,
  decided_by          uuid references users,
  decided_at          timestamptz,
  rejection_reason    text check (rejection_reason is null or char_length(rejection_reason) <= 500),
  comments_enabled    boolean not null default true,
  is_pinned           boolean not null default false,
  pinned_at           timestamptz,
  edited_at           timestamptz,
  media_confirmed_by  uuid references users,
  media_confirmed_at  timestamptz,
  publish_error_code  text,
  deleted_at          timestamptz,
  deleted_by          uuid references users,
  foreign key (class_id, school_id) references classes (id, school_id),
  check ((scope_type = 'CLASS' and class_id is not null) or (scope_type = 'SCHOOL' and class_id is null)),
  check ((status = 'SCHEDULED') = (scheduled_for is not null and status = 'SCHEDULED')),
  check (status <> 'PUBLISHED' or published_at is not null),
  check (not is_pinned or status = 'PUBLISHED'),
  check (not is_contribution or scope_type = 'CLASS'),
  unique (id, school_id),
  -- std
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  version             int not null default 0
);
create index ix_posts_class_feed on posts (class_id, is_pinned desc, published_at desc, id desc)
  where status = 'PUBLISHED' and deleted_at is null and scope_type = 'CLASS';
create index ix_posts_school_feed on posts (school_id, published_at desc, id desc)
  where status = 'PUBLISHED' and deleted_at is null and scope_type = 'SCHOOL';
create index ix_posts_due on posts (scheduled_for, id) where status = 'SCHEDULED' and deleted_at is null;
create index ix_posts_pending on posts (school_id, class_id, created_at, id)
  where status = 'PENDING_APPROVAL' and deleted_at is null;
create index ix_posts_author on posts (author_id, created_at desc);
create index ix_posts_class_fk on posts (class_id, school_id) where class_id is not null;
create index ix_posts_decided_by on posts (decided_by) where decided_by is not null;
create index ix_posts_confirmed_by on posts (media_confirmed_by) where media_confirmed_by is not null;
create index ix_posts_deleted_by on posts (deleted_by) where deleted_by is not null;
create index ix_posts_language on posts (body_language_code) where body_language_code is not null;

create table post_media (
  post_id         uuid not null references posts on delete cascade,
  media_id        uuid not null references media,
  sort_order      smallint not null default 0,
  caption         text check (caption is null or char_length(caption) <= 300),
  primary key (post_id, media_id),
  unique (media_id)
);

-- Students appearing in a post: the media-consent check (R-09), parent notifications and later portfolio links.
create table post_student_tags (
  post_id         uuid not null references posts on delete cascade,
  student_id      uuid not null references student_profiles,
  primary key (post_id, student_id)
);
create index ix_post_student_tags_student on post_student_tags (student_id);

create table post_comments (
  id                  uuid primary key default uuidv7(),
  post_id             uuid not null references posts on delete cascade,
  school_id           uuid not null references schools,
  parent_comment_id   uuid references post_comments,
  author_id           uuid not null references users,
  author_role         text not null check (author_role in ('TEACHER', 'SCHOOL_ADMIN', 'GUARDIAN', 'STUDENT')),
  body                text not null check (char_length(body) between 1 and 1000),
  body_language_code  text references languages,
  status              text not null default 'VISIBLE' check (status in ('VISIBLE', 'HIDDEN', 'DELETED')),
  moderated_by        uuid references users,
  edited_at           timestamptz,
  created_at          timestamptz not null default now()
);
create index ix_post_comments_post on post_comments (post_id, id);
create index ix_post_comments_author on post_comments (author_id);
create index ix_post_comments_school on post_comments (school_id);
create index ix_post_comments_parent on post_comments (parent_comment_id) where parent_comment_id is not null;
create index ix_post_comments_moderator on post_comments (moderated_by) where moderated_by is not null;
create index ix_post_comments_language on post_comments (body_language_code) where body_language_code is not null;

create table post_reactions (
  post_id         uuid not null references posts on delete cascade,
  user_id         uuid not null references users,
  reaction        text not null default 'LIKE' check (reaction in ('LIKE', 'HEART', 'CLAP', 'STAR')),
  created_at      timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index ix_post_reactions_user on post_reactions (user_id);

create table post_views (                        -- "seen by N": only the count is read, never the names
  post_id         uuid not null references posts on delete cascade,
  user_id         uuid not null references users,
  viewed_at       timestamptz not null default now(),
  primary key (post_id, user_id)
);
create index ix_post_views_user on post_views (user_id);

create table stories (
  id                    uuid primary key default uuidv7(),
  scope_type            text not null check (scope_type in ('CLASS', 'SCHOOL')),
  school_id             uuid not null references schools,
  class_id              uuid,
  author_id             uuid not null references users,
  author_role           text not null check (author_role in ('TEACHER', 'SCHOOL_ADMIN')),
  media_id              uuid references media,
  caption               text check (caption is null or char_length(caption) <= 500),
  caption_language_code text references languages,
  status                text not null default 'PUBLISHED' check (status in ('PUBLISHED', 'HIDDEN', 'EXPIRED', 'DELETED')),
  published_at          timestamptz not null,
  expires_at            timestamptz not null,
  media_confirmed_by    uuid references users,
  media_confirmed_at    timestamptz,
  deleted_at            timestamptz,
  foreign key (class_id, school_id) references classes (id, school_id),
  check ((scope_type = 'CLASS') = (class_id is not null)),
  check (media_id is not null or caption is not null or status in ('EXPIRED', 'DELETED')),
  check (expires_at > published_at),
  unique (media_id),
  -- std
  created_at            timestamptz not null default now(),
  updated_at            timestamptz not null default now(),
  version               int not null default 0
);
create index ix_stories_class on stories (class_id, expires_at desc) where status = 'PUBLISHED' and scope_type = 'CLASS';
create index ix_stories_school on stories (school_id, expires_at desc) where status = 'PUBLISHED' and scope_type = 'SCHOOL';
create index ix_stories_expiry on stories (expires_at, id) where status = 'PUBLISHED';
create index ix_stories_expired on stories (expires_at, id) where status = 'EXPIRED';
create index ix_stories_author on stories (author_id);
create index ix_stories_class_fk on stories (class_id, school_id) where class_id is not null;
create index ix_stories_confirmed_by on stories (media_confirmed_by) where media_confirmed_by is not null;
create index ix_stories_language on stories (caption_language_code) where caption_language_code is not null;

create table story_student_tags (
  story_id        uuid not null references stories on delete cascade,
  student_id      uuid not null references student_profiles,
  primary key (story_id, student_id)
);
create index ix_story_student_tags_student on story_student_tags (student_id);

create table story_views (
  story_id        uuid not null references stories on delete cascade,
  user_id         uuid not null references users,
  viewed_at       timestamptz not null default now(),
  primary key (story_id, user_id)
);
create index ix_story_views_user on story_views (user_id);

-- Translations of user-generated content (§32, §71, §125; R-24, D5). Originals are never overwritten; only text of
-- published content is ever sent to a provider (see feed.application.TranslationService).
create table content_translations (
  id                   uuid primary key default uuidv7(),
  source_type          text not null check (source_type in ('POST', 'POST_COMMENT', 'STORY', 'MESSAGE', 'ANNOUNCEMENT',
                         'PORTFOLIO_COMMENT')),
  source_id            uuid not null,
  source_field         text not null default 'body',
  source_hash          text not null,
  source_language_code text references languages,
  target_language_code text not null references languages,
  translated_text      text not null,
  is_machine           boolean not null default true,
  provider             text,
  created_at           timestamptz not null default now(),
  unique (source_type, source_id, source_field, target_language_code, source_hash)
);
create index ix_content_translations_source_language on content_translations (source_language_code)
  where source_language_code is not null;
create index ix_content_translations_target_language on content_translations (target_language_code);
