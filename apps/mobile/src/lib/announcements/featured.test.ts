import { describe, expect, it } from 'vitest';

import { ANNOUNCEMENTS_ROOT, featuredKey, isPersistableAnnouncementQuery } from './keys';
import { pickFeatured } from './featured';

describe('pickFeatured', () => {
  it('takes the newest published announcement', () => {
    const cards = [
      { id: 'a', status: 'PUBLISHED' as const },
      { id: 'b', status: 'PUBLISHED' as const },
    ];
    expect(pickFeatured(cards)?.id).toBe('a');
  });
  it('steps over a withdrawn one and over drafts the person should not see anyway', () => {
    const cards = [
      { id: 'w', status: 'WITHDRAWN' as const },
      { id: 'd', status: 'DRAFT' as const },
      { id: 'p', status: 'PUBLISHED' as const },
    ];
    expect(pickFeatured(cards)?.id).toBe('p');
  });
  it('shows no card without an announcement in force', () => {
    expect(pickFeatured([])).toBeNull();
    expect(pickFeatured(undefined)).toBeNull();
    expect(pickFeatured([{ status: 'WITHDRAWN' as const }])).toBeNull();
  });
});

describe('featuredKey', () => {
  it('lives under the announcements root, so it is kept out of the persisted cache', () => {
    expect(featuredKey(null)[0]).toBe(ANNOUNCEMENTS_ROOT);
    expect(isPersistableAnnouncementQuery(featuredKey('s'))).toBe(false);
    expect(featuredKey(null)).not.toEqual(featuredKey('s'));
  });
});
