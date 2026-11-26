import type { components } from '@school/api-client';

type S = components['schemas'];

export type PostView = S['PostView'];
export type StoryView = S['StoryView'];
export type CommentView = S['CommentView'];
export type FeedCapabilities = S['FeedCapabilities'];
export type FeedMediaItem = S['FeedMediaItem'];
export type FeedStudentTag = S['FeedStudentTag'];
export type FeedAuthor = S['FeedAuthor'];
export type PostRights = S['PostRights'];
export type PostStatus = PostView['status'];
export type PostType = PostView['postType'];
export type ReactionType = NonNullable<PostView['myReaction']>;
export type ReportReason = S['PostReportRequest']['reason'];
export type PublishMode = NonNullable<S['PostCreateRequest']['mode']>;
export type PostPage = S['CursorPagePostView'];
export type CommentPage = S['CursorPageCommentView'];

/** Reactions in the order the bar shows them. */
export const REACTIONS: readonly ReactionType[] = ['HEART', 'LIKE', 'CLAP', 'STAR'];

/** Where a post or a story lives: the class, or the whole school. */
export type FeedTarget = { type: 'CLASS'; schoolId: string; classId: string } | { type: 'SCHOOL'; schoolId: string };
