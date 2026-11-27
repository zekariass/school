import { describe, expect, it } from 'vitest';

import { REPORT_NOTIFICATION_TYPES, isModerationPath, isReportNotification } from './notifications';

describe('the reporter notices', () => {
  it('are the two types the API sends', () => {
    expect([...REPORT_NOTIFICATION_TYPES]).toEqual(['REPORT_ACKNOWLEDGED', 'REPORT_REVIEWED']);
    expect(isReportNotification('REPORT_ACKNOWLEDGED')).toBe(true);
    expect(isReportNotification('REPORT_REVIEWED')).toBe(true);
  });
  it('are told apart from every other notification', () => {
    for (const type of ['NEW_MESSAGE', 'MESSAGE_REPORTED', 'SAFEGUARDING_ALERT', 'REPORT', '', undefined, null]) expect(isReportNotification(type)).toBe(false);
  });
});

describe('isModerationPath', () => {
  it('matches the paths the API sends to moderators and leads', () => {
    for (const path of ['/moderation', '/moderation/cases/abc', '/s/riverside/moderation', '/s/riverside/safeguarding/incidents', '/safeguarding']) expect(isModerationPath(path)).toBe(true);
  });
  it('matches nothing else', () => {
    for (const path of ['/', '/messages', '/s/riverside/messages', '/moderationish', '/feed/posts/1']) expect(isModerationPath(path)).toBe(false);
  });
});
