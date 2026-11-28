import type { components } from '@school/api-client';

import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';

import { FEED_PAGE_SIZE } from './keys';
import { filterOf, type FeedTab } from './tabs';
import type { CommentPage, FeedCapabilities, PostPage, PostStatus, PostView, ReactionType, StoryView } from './types';

type S = components['schemas'];

/** One call per endpoint of the feed, typed from the generated client. Screens and hooks compose them. */

const page = (cursor: string | undefined, limit: number = FEED_PAGE_SIZE) => ({ limit, ...(cursor ? { cursor } : {}) });

// --- reading -----------------------------------------------------------------------------------

/** The three feeds take the same optional filters (`type`, `photos=true`), chosen by the tab; the server applies them. */
export const fetchClassFeed = (schoolId: string, classId: string, cursor?: string, tab: FeedTab = 'FEED'): Promise<PostPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/feed', {
        params: { path: { schoolId, classId }, query: { ...page(cursor), ...filterOf(tab) } },
      }),
    ),
  );

export const fetchSchoolFeed = (schoolId: string, cursor?: string, tab: FeedTab = 'FEED'): Promise<PostPage> =>
  request(async () =>
    unwrap(await api.GET('/api/v1/schools/{schoolId}/feed', { params: { path: { schoolId }, query: { ...page(cursor), ...filterOf(tab) } } })),
  );

export const fetchHomeFeed = (studentId: string | null, cursor?: string, tab: FeedTab = 'FEED'): Promise<PostPage> =>
  request(async () =>
    unwrap(await api.GET('/api/v1/me/feed', { params: { query: { ...page(cursor), ...filterOf(tab), ...(studentId ? { studentId } : {}) } } })),
  );

export const fetchMyPosts = (schoolId: string, status: PostStatus | null, classId: string | null, cursor?: string): Promise<PostPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/my-posts', {
        params: { path: { schoolId }, query: { ...page(cursor), ...(status ? { status } : {}), ...(classId ? { classId } : {}) } },
      }),
    ),
  );

export const fetchPending = (schoolId: string, classId: string | null, cursor?: string): Promise<PostPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/contributions/pending', {
        params: { path: { schoolId }, query: { ...page(cursor), ...(classId ? { classId } : {}) } },
      }),
    ),
  );

export const fetchPost = (schoolId: string, postId: string): Promise<PostView> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/posts/{postId}', { params: { path: { schoolId, postId } } })));

export const fetchCapabilities = (schoolId: string, classId: string | null): Promise<FeedCapabilities> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/feed/capabilities', {
        params: { path: { schoolId }, query: classId ? { classId } : {} },
      }),
    ),
  );

export const fetchComments = (schoolId: string, postId: string, cursor?: string): Promise<CommentPage> =>
  request(async () =>
    unwrap(await api.GET('/api/v1/schools/{schoolId}/posts/{postId}/comments', { params: { path: { schoolId, postId }, query: page(cursor, 30) } })),
  );

export const fetchClassStories = (schoolId: string, classId: string): Promise<StoryView[]> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/stories', { params: { path: { schoolId, classId } } })));

export const fetchSchoolStories = (schoolId: string): Promise<StoryView[]> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/stories', { params: { path: { schoolId } } })));

export const fetchHomeStories = (studentId: string | null): Promise<StoryView[]> =>
  request(async () => unwrap(await api.GET('/api/v1/me/stories', { params: { query: studentId ? { studentId } : {} } })));

export const fetchStory = (schoolId: string, storyId: string): Promise<StoryView> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/stories/{storyId}', { params: { path: { schoolId, storyId } } })));

// --- creating (each carries an Idempotency-Key) -------------------------------------------------

type Key = { idempotencyKey: string };

export const createClassPost = (schoolId: string, classId: string, body: S['PostCreateRequest'], { idempotencyKey }: Key): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/posts', {
        params: { path: { schoolId, classId } },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

export const createSchoolPost = (schoolId: string, body: S['PostCreateRequest'], { idempotencyKey }: Key): Promise<PostView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/posts', { params: { path: { schoolId } }, body, headers: { 'Idempotency-Key': idempotencyKey } })),
  );

export const createContribution = (schoolId: string, classId: string, body: S['ContributionCreateRequest'], { idempotencyKey }: Key): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/contributions', {
        params: { path: { schoolId, classId } },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

export const createClassStory = (schoolId: string, classId: string, body: S['StoryCreateRequest'], { idempotencyKey }: Key): Promise<StoryView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/stories', {
        params: { path: { schoolId, classId } },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

export const createSchoolStory = (schoolId: string, body: S['StoryCreateRequest'], { idempotencyKey }: Key): Promise<StoryView> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/stories', { params: { path: { schoolId } }, body, headers: { 'Idempotency-Key': idempotencyKey } })),
  );

export const createComment = (schoolId: string, postId: string, body: string, bodyLanguageCode: string, { idempotencyKey }: Key) =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/comments', {
        params: { path: { schoolId, postId } },
        body: { body, bodyLanguageCode },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

// --- changing a post -----------------------------------------------------------------------------

export const updatePost = (schoolId: string, postId: string, body: S['PostUpdateRequest']): Promise<PostView> =>
  request(async () => unwrap(await api.PATCH('/api/v1/schools/{schoolId}/posts/{postId}', { params: { path: { schoolId, postId } }, body })));

export const publishPost = (schoolId: string, postId: string, confirmNoUnconsentedStudents: boolean): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/publish', {
        params: { path: { schoolId, postId } },
        body: confirmNoUnconsentedStudents ? { confirmNoUnconsentedStudents: true } : {},
      }),
    ),
  );

export const schedulePost = (schoolId: string, postId: string, scheduledFor: string, confirmNoUnconsentedStudents: boolean): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/schedule', {
        params: { path: { schoolId, postId } },
        body: { scheduledFor, ...(confirmNoUnconsentedStudents ? { confirmNoUnconsentedStudents: true } : {}) },
      }),
    ),
  );

export const unschedulePost = (schoolId: string, postId: string): Promise<PostView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/unschedule', { params: { path: { schoolId, postId } } })));

export const archivePost = (schoolId: string, postId: string): Promise<PostView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/archive', { params: { path: { schoolId, postId } } })));

export const hidePost = (schoolId: string, postId: string, reason: string | null): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/hide', {
        params: { path: { schoolId, postId } },
        body: reason ? { reason } : {},
      }),
    ),
  );

export const unhidePost = (schoolId: string, postId: string, confirmNoUnconsentedStudents: boolean): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/unhide', {
        params: { path: { schoolId, postId } },
        body: confirmNoUnconsentedStudents ? { confirmNoUnconsentedStudents: true } : {},
      }),
    ),
  );

export const pinPost = (schoolId: string, postId: string, pinned: boolean): Promise<PostView> =>
  request(async () =>
    unwrap(
      pinned
        ? await api.PUT('/api/v1/schools/{schoolId}/posts/{postId}/pin', { params: { path: { schoolId, postId } } })
        : await api.DELETE('/api/v1/schools/{schoolId}/posts/{postId}/pin', { params: { path: { schoolId, postId } } }),
    ),
  );

export const deletePost = (schoolId: string, postId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/schools/{schoolId}/posts/{postId}', { params: { path: { schoolId, postId } } })));

export const deleteStory = (schoolId: string, storyId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/schools/{schoolId}/stories/{storyId}', { params: { path: { schoolId, storyId } } })));

export const deleteComment = (schoolId: string, postId: string, commentId: string): Promise<void> =>
  request(async () =>
    assertOk(await api.DELETE('/api/v1/schools/{schoolId}/posts/{postId}/comments/{commentId}', { params: { path: { schoolId, postId, commentId } } })),
  );

// --- contributions -------------------------------------------------------------------------------

export const approveContribution = (
  schoolId: string,
  postId: string,
  input: { confirmNoUnconsentedStudents: boolean; studentIds?: string[] },
): Promise<PostView> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/contributions/{postId}/approve', {
        params: { path: { schoolId, postId } },
        body: {
          ...(input.confirmNoUnconsentedStudents ? { confirmNoUnconsentedStudents: true } : {}),
          ...(input.studentIds ? { studentIds: input.studentIds } : {}),
        },
      }),
    ),
  );

export const rejectContribution = (schoolId: string, postId: string, reason: string): Promise<PostView> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/contributions/{postId}/reject', { params: { path: { schoolId, postId } }, body: { reason } })));

// --- interactions ----------------------------------------------------------------------------------

export const putReaction = (schoolId: string, postId: string, reaction: ReactionType | null) =>
  request(async () =>
    unwrap(
      reaction
        ? await api.PUT('/api/v1/schools/{schoolId}/posts/{postId}/reaction', { params: { path: { schoolId, postId } }, body: { reaction } })
        : await api.DELETE('/api/v1/schools/{schoolId}/posts/{postId}/reaction', { params: { path: { schoolId, postId } } }),
    ),
  );

export const markPostSeen = (schoolId: string, postId: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/view', { params: { path: { schoolId, postId } } })));

export const markStoryViewed = (schoolId: string, storyId: string): Promise<void> =>
  request(async () => assertOk(await api.POST('/api/v1/schools/{schoolId}/stories/{storyId}/view', { params: { path: { schoolId, storyId } } })));

export const translatePost = (schoolId: string, postId: string, language: string) =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/posts/{postId}/translation', { params: { path: { schoolId, postId }, query: { language } } })),
  );
