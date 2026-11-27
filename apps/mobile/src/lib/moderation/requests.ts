import { api } from '@/lib/api';
import { ApiRequestError, request, unwrap } from '@/lib/api-error';

import type { EmergencyInfo, ReportReceipt, ReportRequest } from './types';

/**
 * One call per endpoint. A report needs a connection: there is no outbox and no retry in the background; the sheet says so and the
 * person presses Send again. `POST /moderation/reports` answers 201 for a new report and 200 with the first report when the same
 * person already has an open one for the target: both are the same thank-you here.
 */
export const submitReport = (body: ReportRequest, { idempotencyKey }: { idempotencyKey: string }): Promise<ReportReceipt> =>
  request(async () => unwrap(await api.POST('/api/v1/moderation/reports', { body, headers: { 'Idempotency-Key': idempotencyKey } })));

/** The country's safety text; `null` when the country has none (404): no other country's text is ever used instead. */
export const fetchEmergencyInfo = (country: string): Promise<EmergencyInfo | null> =>
  request(async () => {
    try {
      return unwrap(await api.GET('/api/v1/safety/emergency-info', { params: { query: { country } } }));
    } catch (error) {
      if (error instanceof ApiRequestError && error.status === 404) return null;
      throw error;
    }
  });
