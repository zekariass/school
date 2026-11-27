import { describe, expect, it } from 'vitest';

import type { ProblemDetail } from '@school/api-client';

import { ApiRequestError } from '@/lib/api-error';

import { gateProblemOf, namedBlocked, withoutStudents } from './gate';

function failure(code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  const problem = { type: 'about:blank', title: 'x', status: 422, code, ...extra } as ProblemDetail;
  return new ApiRequestError(422, problem);
}

describe('gateProblemOf (portfolio)', () => {
  it('reads the children who lack consent, with and without names', () => {
    const gate = gateProblemOf(
      failure('portfolio.media_consent_missing', {
        students: [{ studentId: 's1', displayName: 'Abebe K.' }, { studentId: 's2' }, { studentId: 's3', displayName: '  ' }],
      }),
    );
    expect(gate).toEqual({
      kind: 'consent_missing',
      students: [
        { studentId: 's1', displayName: 'Abebe K.' },
        { studentId: 's2', displayName: null },
        { studentId: 's3', displayName: null },
      ],
    });
  });
  it('survives a missing or malformed list', () => {
    expect(gateProblemOf(failure('portfolio.media_consent_missing'))).toEqual({ kind: 'consent_missing', students: [] });
    expect(gateProblemOf(failure('portfolio.media_consent_missing', { students: [null, 3, { displayName: 'no id' }] }))).toEqual({
      kind: 'consent_missing',
      students: [],
    });
  });
  it('recognizes the confirmation requirement', () => {
    expect(gateProblemOf(failure('portfolio.media_confirmation_required'))).toEqual({ kind: 'confirmation_required' });
  });
  it('does not take the feed codes, nor any other failure, for its own', () => {
    expect(gateProblemOf(failure('feed.media_consent_missing'))).toBeNull();
    expect(gateProblemOf(failure('portfolio.invalid_state'))).toBeNull();
    expect(gateProblemOf(new ApiRequestError(500))).toBeNull();
    expect(gateProblemOf(new Error('boom'))).toBeNull();
  });
});

describe('the shared helpers', () => {
  it('names what it can and counts the rest', () => {
    const blocked = [
      { studentId: 'a', displayName: 'Abebe' },
      { studentId: 'b', displayName: null },
      { studentId: 'c', displayName: null },
    ];
    expect(namedBlocked(blocked, new Map([['b', 'Bethel']]))).toEqual({ named: ['Abebe', 'Bethel'], unnamed: 1 });
  });
  it('drops the tags the gate named', () => {
    expect(withoutStudents(['a', 'b', 'c'], [{ studentId: 'b' }])).toEqual(['a', 'c']);
  });
});
