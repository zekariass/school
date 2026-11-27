export const MAX_COMMENT = 500;

export type CommentProblem = "empty" | "tooLong";

/** What is wrong with a comment before it is sent (the API checks again). */
export function commentProblem(text: string): CommentProblem | null {
  const body = text.trim();
  if (!body) return "empty";
  if (body.length > MAX_COMMENT) return "tooLong";
  return null;
}

export const cleanComment = (text: string): string => text.trim();
