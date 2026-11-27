import { describe, expect, it } from 'vitest';

import { anyFailed, anyUploading, isAttachmentAction, readyAttachments, reduceAttachments, type Attachment } from './attachments';

const fresh = (key: string): Attachment => ({ key, uri: `file:///${key}.jpg`, caption: '', status: 'preparing', percent: 0 });

describe('reduceAttachments', () => {
  it('adds a picture once, up to the maximum', () => {
    let list: Attachment[] = [];
    list = reduceAttachments(list, { type: 'attach', key: 'a', uri: 'u' }, 2);
    expect(reduceAttachments(list, { type: 'attach', key: 'a', uri: 'u' }, 2)).toBe(list);
    list = reduceAttachments(list, { type: 'attach', key: 'b', uri: 'u' }, 2);
    expect(reduceAttachments(list, { type: 'attach', key: 'c', uri: 'u' }, 2)).toBe(list);
    expect(list.map((item) => item.key)).toEqual(['a', 'b']);
  });
  it('follows an upload from preparing to ready, and clears the failure on a retry', () => {
    let list = [fresh('a')];
    list = reduceAttachments(list, { type: 'attachStatus', key: 'a', status: 'uploading' }, 10);
    list = reduceAttachments(list, { type: 'attachProgress', key: 'a', percent: 41.7 }, 10);
    expect(list[0]).toMatchObject({ status: 'uploading', percent: 41 });
    list = reduceAttachments(list, { type: 'attachFailed', key: 'a', message: 'no' }, 10);
    expect(list[0]).toMatchObject({ status: 'failed', failure: 'no' });
    list = reduceAttachments(list, { type: 'attachRetry', key: 'a' }, 10);
    expect(list[0]).toEqual({ key: 'a', uri: 'file:///a.jpg', caption: '', status: 'preparing', percent: 0 });
    list = reduceAttachments(list, { type: 'attachReady', key: 'a', mediaId: 'm' }, 10);
    expect(list[0]).toMatchObject({ status: 'ready', percent: 100, mediaId: 'm' });
  });
  it('ignores progress for a picture that is not uploading and actions for an unknown key', () => {
    const list = [fresh('a')];
    expect(reduceAttachments(list, { type: 'attachProgress', key: 'a', percent: 50 }, 10)).toBe(list);
    expect(reduceAttachments(list, { type: 'attachReady', key: 'zzz', mediaId: 'm' }, 10)).toBe(list);
    expect(reduceAttachments(list, { type: 'detach', key: 'zzz' }, 10)).toBe(list);
  });
  it('removes and moves', () => {
    const list = [fresh('a'), fresh('b'), fresh('c')];
    expect(reduceAttachments(list, { type: 'detach', key: 'b' }, 10).map((item) => item.key)).toEqual(['a', 'c']);
    expect(reduceAttachments(list, { type: 'move', key: 'c', direction: -1 }, 10).map((item) => item.key)).toEqual(['a', 'c', 'b']);
    expect(reduceAttachments(list, { type: 'move', key: 'a', direction: -1 }, 10)).toBe(list);
  });
});

describe('the helpers', () => {
  it('tell what is ready, running or failed', () => {
    const list: Attachment[] = [
      { ...fresh('a'), status: 'ready', mediaId: 'm' },
      { ...fresh('b'), status: 'uploading' },
      { ...fresh('c'), status: 'failed' },
      { ...fresh('d'), status: 'ready' },
    ];
    expect(readyAttachments(list).map((item) => item.key)).toEqual(['a']);
    expect(anyUploading(list)).toBe(true);
    expect(anyFailed(list)).toBe(true);
    expect(anyUploading([list[0]!])).toBe(false);
  });
  it('recognizes its own actions', () => {
    expect(isAttachmentAction({ type: 'attach' })).toBe(true);
    expect(isAttachmentAction({ type: 'title' })).toBe(false);
  });
});
