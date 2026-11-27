import { LOCALE_CODES } from '@school/i18n';
import { looksLikeInviteToken } from '@school/validation/guardians';

/**
 * The in-app route of an invitation link, or null when `path` is not one. Accepts what the OS hands to
 * the app: a full URL (`https://host/en/invite/{token}?x=1`, `school://invite/{token}`) or a bare path
 * (`/en/invite/{token}`, `/invite/{token}`). The token must look like one; anything else is not ours.
 */
export function inviteRouteFor(link: string): string | null {
  const withoutOrigin = link.replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]*/i, '');
  // `school://invite/{token}` has "invite" as its host, so the path starts after the scheme alone.
  const path = /^school:\/\//i.test(link) ? `/${link.replace(/^school:\/\//i, '')}` : withoutOrigin;
  const [clean = ''] = path.split(/[?#]/);
  const parts = clean.split('/').filter(Boolean);
  const rest = parts[0] && (LOCALE_CODES as readonly string[]).includes(parts[0]) ? parts.slice(1) : parts;
  if (rest.length !== 2 || rest[0] !== 'invite') return null;
  const token = rest[1];
  return looksLikeInviteToken(token) ? `/invite/${token}` : null;
}
