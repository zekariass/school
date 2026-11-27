import type { components } from '@school/api-client';

type S = components['schemas'];

export type PortfolioItem = S['PortfolioItemView'];
export type PortfolioItemType = PortfolioItem['itemType'];
export type PortfolioStatus = PortfolioItem['status'];
export type PortfolioVisibility = PortfolioItem['visibility'];
export type PortfolioMedia = S['PortfolioMediaView'];
export type PortfolioCapabilities = S['PortfolioCapabilities'];
export type PortfolioItemCapabilities = S['PortfolioItemCapabilities'];
export type PortfolioComment = S['PortfolioCommentView'];
export type PortfolioHistoryEntry = S['PortfolioHistoryEntry'];
export type PortfolioPage = S['CursorPagePortfolioItemView'];
export type PortfolioCommentPage = S['CursorPagePortfolioCommentView'];
export type ReviewDecision = S['PortfolioReviewRequest']['decision'];
export type ItemRequest = S['PortfolioItemRequest'];
export type ItemUpdateRequest = S['PortfolioItemUpdateRequest'];
export type QuickCaptureRequest = S['PortfolioQuickCaptureRequest'];
