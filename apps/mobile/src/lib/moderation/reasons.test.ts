import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { INTRO_LABEL, REASON_LABEL, REPORT_REASONS, isOfferedReason } from './reasons';

const SPEC = JSON.parse(readFileSync(join(__dirname, '..', '..', '..', '..', '..', 'packages', 'api-client', 'openapi', 'openapi.json'), 'utf8')) as {
  components: { schemas: { ModerationReportRequest: { properties: { reasonCode: { enum: string[] }; targetType: { enum: string[] } } } } };
};
const api = SPEC.components.schemas.ModerationReportRequest.properties;

describe('the reasons', () => {
  it('are all codes the API accepts', () => {
    for (const reason of REPORT_REASONS) expect(api.reasonCode.enum, reason).toContain(reason);
  });
  it('leave out only the library and tutor reasons', () => {
    const left = api.reasonCode.enum.filter((code) => !(REPORT_REASONS as readonly string[]).includes(code));
    expect(left.sort()).toEqual(['COPYRIGHT', 'INACCURATE']);
  });
  it("each have a label, and the sheet names every target it can report in the API's own words", () => {
    expect(Object.keys(REASON_LABEL).sort()).toEqual([...REPORT_REASONS].sort());
    for (const type of Object.keys(INTRO_LABEL)) expect(api.targetType.enum, type).toContain(type);
  });
  it('recognise only the offered codes', () => {
    expect(isOfferedReason('SPAM')).toBe(true);
    expect(isOfferedReason('COPYRIGHT')).toBe(false);
    expect(isOfferedReason(3)).toBe(false);
  });
});
