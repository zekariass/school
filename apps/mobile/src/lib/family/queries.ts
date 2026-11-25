import type { components } from '@school/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { RelationshipType } from '@school/validation/guardians';

import { api } from '@/lib/api';
import { request, unwrap } from '@/lib/api-error';

import type { InvitationKey } from './invitation-requests';
import { FAMILY_ROOT, myChildrenKey, myConsentsKey } from './keys';

type S = components['schemas'];

export type MyChildren = S['MyChildren'];
export type MyChild = S['Child'];
export type PendingLink = S['PendingLink'];
export type MyConsents = S['MyConsents'];
export type ChildConsents = S['ChildConsents'];
export type ConsentItem = S['ConsentItem'];

/** The adult's children, with classes and teachers when the link allows, and requests still waiting for the school. */
export function useMyChildren() {
  return useQuery({
    queryKey: myChildrenKey,
    queryFn: () => request(async () => unwrap(await api.GET('/api/v1/me/children'))),
  });
}

/** Open and past consent decisions for my children, and my own agreements. */
export function useMyConsents(enabled = true) {
  return useQuery({
    queryKey: myConsentsKey,
    enabled,
    retry: false,
    queryFn: () => request(async () => unwrap(await api.GET('/api/v1/me/consents'))),
  });
}

/**
 * Connects the signed-in adult with a child through an invitation link token or a printed parent code.
 * The key is sent once in the request body and is not kept: no variables stay in the mutation (`gcTime: 0`).
 */
export function useRedeemInvitation(key: InvitationKey) {
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    networkMode: 'always',
    retry: false,
    mutationFn: async (relationshipType: RelationshipType) =>
      request(async () => unwrap(await api.POST('/api/v1/me/guardian-links', { body: { ...key, relationshipType } }))),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [FAMILY_ROOT] }),
  });
}

export interface ConsentDecision {
  consentType: ConsentItem['consentType'];
  policyVersionId: string;
  decision: 'GRANTED' | 'DENIED' | 'WITHDRAWN';
  studentId?: string;
  schoolId?: string;
}

/**
 * Records one decision. `recordMyConsent` is idempotent: the key identifies this attempt, so a retry
 * after a dropped response does not record the decision twice.
 */
export function useRecordConsent() {
  const queryClient = useQueryClient();
  return useMutation({
    networkMode: 'always',
    retry: false,
    mutationFn: async (input: ConsentDecision & { idempotencyKey: string }) => {
      const { idempotencyKey, ...body } = input;
      return request(async () =>
        unwrap(await api.POST('/api/v1/me/consents', { body, headers: { 'Idempotency-Key': idempotencyKey } })),
      );
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [FAMILY_ROOT] }),
  });
}
