import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { QueryClient } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';

import { MODERATION_ROOT, emergencyInfoKey } from './keys';
import { clearModerationMemory } from './memory';

describe('clearModerationMemory', () => {
  it('drops everything under the moderation root and nothing else', () => {
    const client = new QueryClient();
    client.setQueryData(emergencyInfoKey('XX'), { text: 'safety text' });
    client.setQueryData([MODERATION_ROOT, 'other'], 1);
    client.setQueryData(['classes', 'mine'], ['kept']);
    clearModerationMemory(client);
    expect(client.getQueryData(emergencyInfoKey('XX'))).toBeUndefined();
    expect(client.getQueryData([MODERATION_ROOT, 'other'])).toBeUndefined();
    expect(client.getQueryData(['classes', 'mine'])).toEqual(['kept']);
  });
  it('is harmless when there is nothing to clear', () => {
    expect(() => clearModerationMemory(new QueryClient())).not.toThrow();
  });
});

describe('sign-out', () => {
  // The session provider needs the device runtime, so its two end-of-session paths are checked in the source: the one that runs
  // when the API ends the session and the one the person triggers. Both must clear the moderation memory.
  const source = readFileSync(join(__dirname, '..', 'session', 'session-provider.tsx'), 'utf8');
  it('clears the moderation memory when the session ends and when the person signs out', () => {
    expect(source.match(/clearModerationMemory\(queryClient\)/g)?.length).toBe(2);
    expect(source).toContain("from '@/lib/moderation/memory'");
  });
  it('still wipes the whole query cache, memory and disk, in both paths', () => {
    expect(source.match(/clearQueryCache\(\)/g)?.length).toBeGreaterThanOrEqual(2);
  });
});
