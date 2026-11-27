/**
 * Routes of the portfolio screens. Only ids go into a path (never a title, a name or a note). The item page exists
 * twice: standalone for students, guardians and notification links (the school comes from the path), and inside the
 * school workspace for teachers (the school comes from the workspace).
 */

/** The item page outside the workspace: `/portfolio/items/{schoolId}/{itemId}`. */
export const itemPath = (schoolId: string, itemId: string): string => `/portfolio/items/${schoolId}/${itemId}`;

/** The item page inside a school's workspace. */
export const workspaceItemPath = (slug: string, itemId: string): string => `/s/${encodeURIComponent(slug)}/portfolio/items/${itemId}`;

/** The student's own portfolio. */
export const STUDENT_PORTFOLIO = "/portfolio";

/** A guardian's portfolio view of all children, or of one child. */
export const familyPortfolioPath = (studentId?: string): string => (studentId ? `/family/portfolio/${studentId}` : "/family/portfolio");

/** The school gallery page in the workspace. */
export const workspacePortfolioPath = (slug: string): string => `/s/${encodeURIComponent(slug)}/portfolio`;
