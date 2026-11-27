import type { CommentView, PostView, StoryView } from '@/lib/feed/types';

/**
 * Where the Report action is offered. These are menu rules built only from what the API sent about the item (its status, `rights`,
 * `mine`, the author id it sends to people who may know the author): never from the viewer's role, and never a policy. The server
 * decides for real: a target the reporter may not see answers 404, a report of your own content 409 `moderation.cannot_report_own`,
 * and both are shown inline by the sheet. The rules lean towards offering Report, because a hidden button is a child who cannot tell
 * anybody; the only things left out are the viewer's own content (the API refuses it anyway) and content that is no longer shown.
 */

/** A post is reportable while published and when the viewer cannot edit it (an author can; a moderator who is not the author cannot, they have "hide"). */
export function canReportPost(post: Pick<PostView, 'status' | 'rights'>): boolean {
  return post.status === 'PUBLISHED' && !post.rights.canEdit;
}

/** A comment is reportable while it is shown (VISIBLE) and when it is not the viewer's own. */
export function canReportComment(comment: Pick<CommentView, 'status' | 'mine'>): boolean {
  return comment.status === 'VISIBLE' && !comment.mine;
}

/**
 * A story is reportable while it is published and when its author is not the viewer. The API sends no "mine" flag for stories, so the
 * author id (sent only to those who may know the author) is compared with the viewer's id; when it is missing the button is offered
 * and the API answers for itself.
 */
export function canReportStory(story: Pick<StoryView, 'status' | 'author'>, viewerId: string | null | undefined): boolean {
  if (story.status !== 'PUBLISHED') return false;
  return !(viewerId && story.author.userId === viewerId);
}

/** A person is reportable when they are somebody else; whether the two share a class, school or conversation is the API's call. */
export function canReportPerson(personId: string | null | undefined, viewerId: string | null | undefined): boolean {
  return !!personId && !!viewerId && personId !== viewerId;
}
