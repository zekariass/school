import { describe, expect, it } from 'vitest';

import type { ProblemDetail } from '@school/api-client';

import { ApiRequestError } from '@/lib/api-error';

import { gateProblemOf, namedBlocked, withoutStudents } from './gate';

function failure(code: string, extra: Record<string, unknown> = {}): ApiRequestError {
  const problem = { type: 'about:blank', title: 'x', status: 422, code, ...extra } as ProblemDetail;
  return new ApiRequestError(422, problem);
}

describe('gateProblemOf', () => {
  it('reads the students of a missing consent, with and without names', () => {
    const gate = gateProblemOf(
      failure('feed.media_consent_missing', {
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
    expect(gateProblemOf(failure('feed.media_consent_missing'))).toEqual({ kind: 'consent_missing', students: [] });
    expect(gateProblemOf(failure('feed.media_consent_missing', { students: [null, 3, { displayName: 'no id' }] }))).toEqual({
      kind: 'consent_missing',
      students: [],
    });
  });
  it('recognizes the confirmation requirement', () => {
    expect(gateProblemOf(failure('feed.media_confirmation_required'))).toEqual({ kind: 'confirmation_required' });
  });
  it('is null for any other failure', () => {
    expect(gateProblemOf(failure('feed.post_empty'))).toBeNull();
    expect(gateProblemOf(new ApiRequestError(undefined))).toBeNull();
    expect(gateProblemOf(new Error('x'))).toBeNull();
  });
});

describe('withoutStudents', () => {
  it('drops the blocked tags and keeps the rest in order', () => {
    expect(withoutStudents(['a', 'b', 'c'], [{ studentId: 'b' }])).toEqual(['a', 'c']);
    expect(withoutStudents(['a'], [])).toEqual(['a']);
  });
});

describe('namedBlocked', () => {
  it('prefers the API name, then the roster, and counts the rest', () => {
    const known = new Map([['s2', 'Roster Name']]);
    expect(
      namedBlocked(
        [
          { studentId: 's1', displayName: 'Api Name' },
          { studentId: 's2', displayName: null },
          { studentId: 's3', displayName: null },
        ],
        known,
      ),
    ).toEqual({ named: ['Api Name', 'Roster Name'], unnamed: 1 });
  });
});
