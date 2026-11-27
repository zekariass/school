import { describe, expect, it } from 'vitest';

import { MAX_DETAILS, attemptFor, buildReportRequest, cleanDetails, keepAttemptAfter, validateReport } from './report';
import type { ReportTarget } from './types';

const post: ReportTarget = { type: 'POST', id: 'p1', schoolId: 's1' };

describe('validateReport', () => {
  it('needs a reason', () => {
    expect(validateReport({ reason: null, details: '' })).toBe('reasonRequired');
  });
  it('takes no details, and details up to 1,000 characters', () => {
    expect(validateReport({ reason: 'OTHER', details: '' })).toBeNull();
    expect(validateReport({ reason: 'OTHER', details: 'a'.repeat(MAX_DETAILS) })).toBeNull();
  });
  it('refuses more than 1,000 characters', () => {
    expect(validateReport({ reason: 'OTHER', details: 'a'.repeat(MAX_DETAILS + 1) })).toBe('detailsTooLong');
  });
  it('counts what the server counts: surrounding blanks and control characters do not', () => {
    expect(validateReport({ reason: 'OTHER', details: `  ${'a'.repeat(MAX_DETAILS)}  \u0000\u0007` })).toBeNull();
  });
  it('does not offer a reason the phone has no use for', () => {
    expect(validateReport({ reason: 'COPYRIGHT' as never, details: '' })).toBe('reasonRequired');
  });
});

describe('cleanDetails', () => {
  it('keeps line breaks, drops control characters and blanks around', () => {
    expect(cleanDetails('  one\r\ntwo\u0000\tthree \n')).toBe('one\ntwo\tthree');
  });
});

describe('buildReportRequest', () => {
  it('sends ids, the reason and the details', () => {
    expect(buildReportRequest(post, { reason: 'HARASSMENT', details: ' he keeps teasing me ' })).toEqual({
      targetType: 'POST',
      targetId: 'p1',
      reasonCode: 'HARASSMENT',
      details: 'he keeps teasing me',
    });
  });
  it('leaves the details out when there are none', () => {
    const body = buildReportRequest({ type: 'STORY', id: 'st1' }, { reason: 'SPAM', details: '   ' });
    expect(body).toEqual({ targetType: 'STORY', targetId: 'st1', reasonCode: 'SPAM' });
    expect('details' in body!).toBe(false);
  });
  it('never carries the school or any content of the target', () => {
    const body = buildReportRequest(post, { reason: 'PRIVACY', details: '' })!;
    expect(Object.keys(body).sort()).toEqual(['reasonCode', 'targetId', 'targetType']);
  });
  it('is null for an invalid draft', () => {
    expect(buildReportRequest(post, { reason: null, details: '' })).toBeNull();
    expect(buildReportRequest(post, { reason: 'OTHER', details: 'x'.repeat(MAX_DETAILS + 1) })).toBeNull();
  });
  it.each(['POST', 'POST_COMMENT', 'STORY', 'MESSAGE', 'USER'] as const)('names the %s target type exactly as the API does', (type) => {
    expect(buildReportRequest({ type, id: 'x' }, { reason: 'OTHER', details: '' })?.targetType).toBe(type);
  });
});

describe('the idempotency key of an attempt', () => {
  let n = 0;
  const newKey = () => `key-${++n}`;
  const body = buildReportRequest(post, { reason: 'SPAM', details: 'x' })!;

  it('is kept for a retry of the same report', () => {
    const first = attemptFor(null, body, newKey);
    expect(attemptFor(first, body, newKey)).toBe(first);
  });
  it('is new when the reason, the details or the target change', () => {
    const first = attemptFor(null, body, newKey);
    for (const other of [
      { ...body, reasonCode: 'OTHER' as const },
      { ...body, details: 'y' },
      { ...body, targetId: 'p2' },
      { ...body, targetType: 'STORY' as const },
    ]) {
      expect(attemptFor(first, other, newKey).key).not.toBe(first.key);
    }
  });
  it('is kept after a failure only when the server may have received the report', () => {
    expect(keepAttemptAfter(undefined)).toBe(true);
    expect(keepAttemptAfter(503)).toBe(true);
    for (const status of [400, 403, 404, 409, 429]) expect(keepAttemptAfter(status)).toBe(false);
  });
});
