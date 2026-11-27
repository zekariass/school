import { request, unwrap } from '@/lib/api-error';
import type { IssuedTokens } from '@/lib/auth/refresh';
import { authApi } from '@/lib/http';

import type { ContactDestination } from '@school/validation/guardians';

/** Exactly one of the token of an invitation link and a printed parent code (normalized, no hyphen). */
export type InvitationKey = { token: string; code?: undefined } | { code: string; token?: undefined };

export interface InvitationPreview {
  schoolName: string;
  childFirstName: string;
  relationshipType?: string | undefined;
  channel: string;
}

/** What a live invitation is about. Public. A dead invitation is the uniform 404 `invitation.not_found`. */
export function previewInvitation(key: InvitationKey): Promise<InvitationPreview> {
  return request(async () => {
    const preview = unwrap(await authApi.POST('/api/v1/invitations/preview', { body: { ...key } }));
    return {
      schoolName: preview.schoolName,
      childFirstName: preview.childFirstName,
      relationshipType: preview.relationshipType,
      channel: preview.channel,
    };
  });
}

export interface InvitationChallenge {
  codeLength: number;
  /** Seconds the code stays valid. */
  expiresIn: number;
}

/**
 * Sends the one-time code to the phone or email of the adult accepting the invitation. `country` is the one a national
 * number was entered in (D42); absent means the platform default on the API side.
 */
export function requestInvitationOtp(key: InvitationKey, destination: ContactDestination, country?: string): Promise<InvitationChallenge> {
  return request(async () => {
    const accepted = unwrap(
      await authApi.POST('/api/v1/invitations/otp', { body: { ...key, ...(country ? { country } : {}), ...destination } }),
    );
    return { codeLength: accepted.codeLength, expiresIn: accepted.expiresIn };
  });
}

export interface AcceptInput {
  key: InvitationKey;
  destination: ContactDestination;
  otpCode: string;
  displayName?: string;
  preferredLanguage?: string;
}

/** Accepts the invitation with the code; the adult is signed in with the returned token pair. */
export function acceptInvitation(input: AcceptInput): Promise<IssuedTokens> {
  return request(async () =>
    unwrap(
      await authApi.POST('/api/v1/invitations/accept', {
        body: {
          ...input.key,
          ...input.destination,
          otpCode: input.otpCode,
          ...(input.displayName ? { displayName: input.displayName } : {}),
          ...(input.preferredLanguage ? { preferredLanguage: input.preferredLanguage } : {}),
        },
      }),
    ),
  );
}
