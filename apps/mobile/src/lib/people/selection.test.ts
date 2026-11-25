import { describe, expect, it } from 'vitest';

import { MAX_ADD_BATCH, addCandidates, selectAll, toggleSelection, withoutMembers } from './selection';

describe('add-students selection', () => {
  it('lists only students who are not in the class yet', () => {
    const found = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
    expect(addCandidates(found, new Set(['b']))).toEqual([{ id: 'a' }, { id: 'c' }]);
    expect(addCandidates(found, new Set())).toHaveLength(3);
  });

  it('toggles without changing the previous set', () => {
    const empty = new Set<string>();
    const one = toggleSelection(empty, 'a');
    expect([...one]).toEqual(['a']);
    expect(empty.size).toBe(0);
    expect(toggleSelection(one, 'a').size).toBe(0);
  });

  it('never selects more than the API accepts', () => {
    const ids = Array.from({ length: MAX_ADD_BATCH + 20 }, (_, i) => `s${i}`);
    expect(selectAll(new Set(), ids).size).toBe(MAX_ADD_BATCH);
    expect(toggleSelection(selectAll(new Set(), ids), 'extra').has('extra')).toBe(false);
    expect(toggleSelection(new Set(['a', 'b']), 'c', 2).size).toBe(2);
  });

  it('keeps earlier choices when selecting all and drops students who joined meanwhile', () => {
    const picked = selectAll(new Set(['x']), ['a', 'b']);
    expect([...picked].sort()).toEqual(['a', 'b', 'x']);
    expect(withoutMembers(picked, new Set(['b']))).toEqual(['x', 'a']);
  });
});
