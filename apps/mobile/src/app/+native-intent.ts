import { inviteRouteFor } from '@/lib/family/invite-link';

/**
 * Rewrites the path of an incoming link before the router sees it. The invitation SMS carries the web
 * link `https://{host}/{language}/invite/{token}` (and the app's own scheme `school://invite/{token}`);
 * both open the app's `invite/[token]` screen. Everything else is left as it is.
 */
export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  return inviteRouteFor(path) ?? path;
}
