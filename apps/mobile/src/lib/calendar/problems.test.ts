import { describe, expect, it } from 'vitest';

import { ApiRequestError } from '@/lib/api-error';

import { codeOf, formFieldOf, isFeatureDisabled, isGone, problemKind } from './problems';

function failure(status: number | undefined, code?: string): ApiRequestError {
  return new ApiRequestError(status, code ? ({ type: 'about:blank', title: 'Problem', status: status ?? 0, detail: 'Localized detail', code } as never) : undefined);
}

describe('how a failed call is shown', () => {
  it('reads the stable code of the problem', () => {
    expect(codeOf(failure(409, 'event.rsvp_closed'))).toBe('event.rsvp_closed');
    expect(codeOf(failure(500))).toBeUndefined();
    expect(codeOf(new Error('x'))).toBeUndefined();
  });
  it('tells offline from a refusal', () => {
    expect(problemKind(failure(undefined))).toBe('offline');
    expect(problemKind(failure(403, 'event.access_denied'))).toBe('forbidden');
    expect(problemKind(failure(403, 'calendar.feature_disabled'))).toBe('featureDisabled');
    expect(problemKind(failure(404, 'event.not_found'))).toBe('gone');
    expect(problemKind(failure(409, 'event.cancelled'))).toBe('conflict');
    expect(problemKind(failure(400, 'event.time_invalid'))).toBe('invalid');
    expect(problemKind(failure(422, 'event.no_participants'))).toBe('invalid');
    expect(problemKind(failure(429))).toBe('rateLimited');
    expect(problemKind(failure(500))).toBe('other');
    expect(problemKind('boom')).toBe('other');
  });
  it('knows an event that is not there and a calendar that is off', () => {
    expect(isGone(failure(404, 'event.not_found'))).toBe(true);
    expect(isGone(failure(403))).toBe(false);
    expect(isFeatureDisabled(failure(403, 'calendar.feature_disabled'))).toBe(true);
    expect(isFeatureDisabled(failure(403, 'event.access_denied'))).toBe(false);
  });
  it('puts a form problem under the field it belongs to', () => {
    expect(formFieldOf(failure(400, 'event.title_required'))).toBe('title');
    expect(formFieldOf(failure(400, 'event.description_too_long'))).toBe('description');
    expect(formFieldOf(failure(400, 'event.time_invalid'))).toBe('time');
    expect(formFieldOf(failure(400, 'event.occurrence_shift_too_large'))).toBe('time');
    expect(formFieldOf(failure(400, 'event.recurrence_start_mismatch'))).toBe('repeat');
    expect(formFieldOf(failure(400, 'event.rsvp_unsupported'))).toBe('rsvp');
    expect(formFieldOf(failure(422, 'event.no_participants'))).toBe('audience');
    expect(formFieldOf(failure(403, 'event.meeting_not_allowed'))).toBe('audience');
    expect(formFieldOf(failure(409, 'class.archived'))).toBeNull();
    expect(formFieldOf(failure(500))).toBeNull();
  });
});
