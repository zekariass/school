export type SessionEndReason = 'expired' | 'revoked' | 'inactive';

/** Server problem codes that end the session for good (a refresh cannot help). */
export const TERMINAL_SESSION_CODES = ['auth.session_revoked', 'auth.account_inactive'] as const;

export function isTerminalSessionCode(code: string | undefined): boolean {
  return code !== undefined && (TERMINAL_SESSION_CODES as readonly string[]).includes(code);
}

/** Why the session ended, from the problem code of the response that ended it. */
export function endReasonFor(code: string | undefined): SessionEndReason {
  if (code === 'auth.session_revoked') return 'revoked';
  if (code === 'auth.account_inactive') return 'inactive';
  return 'expired';
}

export type SessionEndListener = (reason: SessionEndReason) => void;

/** Minimal publish/subscribe so the API layer can tell the session provider "you are signed out". */
export function createSessionEndBus() {
  const listeners = new Set<SessionEndListener>();
  return {
    subscribe(listener: SessionEndListener): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    emit(reason: SessionEndReason): void {
      for (const listener of [...listeners]) listener(reason);
    },
  };
}
