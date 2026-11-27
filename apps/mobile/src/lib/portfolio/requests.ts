import { api } from '@/lib/api';
import { assertOk, request, unwrap } from '@/lib/api-error';

import { PORTFOLIO_PAGE_SIZE } from './keys';
import type {
  ItemRequest,
  ItemUpdateRequest,
  PortfolioCapabilities,
  PortfolioComment,
  PortfolioCommentPage,
  PortfolioHistoryEntry,
  PortfolioItem,
  PortfolioPage,
  PortfolioStatus,
  QuickCaptureRequest,
  ReviewDecision,
  PortfolioVisibility,
} from './types';

/** One call per endpoint of the portfolio, typed from the generated client. Screens and hooks compose them. */

const page = (cursor: string | undefined, limit: number = PORTFOLIO_PAGE_SIZE) => ({ limit, ...(cursor ? { cursor } : {}) });

// --- reading -----------------------------------------------------------------------------------

export const fetchCapabilities = (schoolId: string): Promise<PortfolioCapabilities> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/portfolio/capabilities', { params: { path: { schoolId } } })));

export const fetchMine = (schoolId: string, status: PortfolioStatus | null, cursor?: string): Promise<PortfolioPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/portfolio/mine', {
        params: { path: { schoolId }, query: { ...page(cursor), ...(status ? { status } : {}) } },
      }),
    ),
  );

export const fetchQueue = (schoolId: string, classId: string, cursor?: string): Promise<PortfolioPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/classes/{classId}/portfolio/review-queue', {
        params: { path: { schoolId, classId }, query: page(cursor) },
      }),
    ),
  );

/** A guardian's combined view of the children, or of one child. */
export const fetchChildren = (studentId: string | null, cursor?: string): Promise<PortfolioPage> =>
  request(async () => unwrap(await api.GET('/api/v1/me/portfolio', { params: { query: { ...page(cursor), ...(studentId ? { studentId } : {}) } } })));

export const fetchItem = (schoolId: string, itemId: string): Promise<PortfolioItem> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/portfolio/items/{itemId}', { params: { path: { schoolId, itemId } } })));

export const fetchHistory = (schoolId: string, itemId: string): Promise<PortfolioHistoryEntry[]> =>
  request(async () => unwrap(await api.GET('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/history', { params: { path: { schoolId, itemId } } })));

export const fetchComments = (schoolId: string, itemId: string, cursor?: string): Promise<PortfolioCommentPage> =>
  request(async () =>
    unwrap(
      await api.GET('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments', {
        params: { path: { schoolId, itemId }, query: page(cursor, 30) },
      }),
    ),
  );

// --- creating (each carries an Idempotency-Key) -------------------------------------------------

type Key = { idempotencyKey: string };

export const createItem = (schoolId: string, body: ItemRequest, { idempotencyKey }: Key): Promise<PortfolioItem> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/portfolio/items', { params: { path: { schoolId } }, body, headers: { 'Idempotency-Key': idempotencyKey } })),
  );

export const quickCapture = (schoolId: string, classId: string, body: QuickCaptureRequest, { idempotencyKey }: Key): Promise<PortfolioItem> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/classes/{classId}/portfolio/quick-capture', {
        params: { path: { schoolId, classId } },
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

export const createComment = (schoolId: string, itemId: string, body: string, { idempotencyKey }: Key): Promise<PortfolioComment> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments', {
        params: { path: { schoolId, itemId } },
        body: { body },
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
    ),
  );

// --- changing ------------------------------------------------------------------------------------

export const updateItem = (schoolId: string, itemId: string, body: ItemUpdateRequest): Promise<PortfolioItem> =>
  request(async () => unwrap(await api.PATCH('/api/v1/schools/{schoolId}/portfolio/items/{itemId}', { params: { path: { schoolId, itemId } }, body })));

export const submitItem = (schoolId: string, itemId: string): Promise<PortfolioItem> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/submit', { params: { path: { schoolId, itemId } } })));

export const reviewItem = (
  schoolId: string,
  itemId: string,
  body: { decision: ReviewDecision; note?: string; visibility?: PortfolioVisibility },
): Promise<PortfolioItem> =>
  request(async () => unwrap(await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/review', { params: { path: { schoolId, itemId } }, body })));

export const archiveItem = (schoolId: string, itemId: string): Promise<PortfolioItem> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/archive', { params: { path: { schoolId, itemId } }, body: {} })),
  );

export const unarchiveItem = (schoolId: string, itemId: string): Promise<PortfolioItem> =>
  request(async () =>
    unwrap(await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/unarchive', { params: { path: { schoolId, itemId } }, body: {} })),
  );

export const setFeatured = (schoolId: string, itemId: string, featured: boolean): Promise<PortfolioItem> =>
  request(async () =>
    unwrap(
      featured
        ? await api.PUT('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/featured', { params: { path: { schoolId, itemId } } })
        : await api.DELETE('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/featured', { params: { path: { schoolId, itemId } } }),
    ),
  );

export const deleteItem = (schoolId: string, itemId: string): Promise<void> =>
  request(async () => assertOk(await api.DELETE('/api/v1/schools/{schoolId}/portfolio/items/{itemId}', { params: { path: { schoolId, itemId } } })));

export const hideComment = (schoolId: string, itemId: string, commentId: string, reason: string): Promise<PortfolioComment> =>
  request(async () =>
    unwrap(
      await api.POST('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments/{commentId}/hide', {
        params: { path: { schoolId, itemId, commentId } },
        body: { reason },
      }),
    ),
  );

export const deleteComment = (schoolId: string, itemId: string, commentId: string): Promise<void> =>
  request(async () =>
    assertOk(await api.DELETE('/api/v1/schools/{schoolId}/portfolio/items/{itemId}/comments/{commentId}', { params: { path: { schoolId, itemId, commentId } } })),
  );
