/**
 * Photos on their way to a screen's form (a portfolio item): the same list state as the feed composer, kept in memory while the
 * screen is open and never written to storage. `usePhotoUploads` drives it with these actions.
 */

export type AttachmentStatus = 'preparing' | 'uploading' | 'ready' | 'failed';

export interface Attachment {
  /** Local key (the list key and the handle of its upload). */
  key: string;
  /** The converted JPEG on the device, for the preview ("" for a file already on the item: its signed link is fetched). */
  uri: string;
  caption: string;
  status: AttachmentStatus;
  percent: number;
  /** Set when `status` is `ready`. */
  mediaId?: string;
  /** The already-localized reason of a failed upload. */
  failure?: string;
}

export type AttachmentAction =
  | { type: 'attach'; key: string; uri: string }
  | { type: 'attachStatus'; key: string; status: AttachmentStatus }
  | { type: 'attachProgress'; key: string; percent: number }
  | { type: 'attachReady'; key: string; mediaId: string }
  | { type: 'attachFailed'; key: string; message: string }
  | { type: 'attachRetry'; key: string }
  | { type: 'detach'; key: string }
  | { type: 'move'; key: string; direction: -1 | 1 };

export function isAttachmentAction(action: { type: string }): action is AttachmentAction {
  switch (action.type) {
    case 'attach':
    case 'attachStatus':
    case 'attachProgress':
    case 'attachReady':
    case 'attachFailed':
    case 'attachRetry':
    case 'detach':
    case 'move':
      return true;
    default:
      return false;
  }
}

function update(list: Attachment[], key: string, change: (item: Attachment) => Attachment): Attachment[] {
  const current = list.find((item) => item.key === key);
  if (!current) return list;
  const changed = change(current);
  return changed === current ? list : list.map((item) => (item.key === key ? changed : item));
}

const clampPercent = (value: number) => Math.min(100, Math.max(0, Math.floor(value)));

/** The list after an action; the same array when nothing changed. */
export function reduceAttachments(list: Attachment[], action: AttachmentAction, max: number): Attachment[] {
  switch (action.type) {
    case 'attach':
      if (list.some((item) => item.key === action.key) || list.length >= max) return list;
      return [...list, { key: action.key, uri: action.uri, caption: '', status: 'preparing', percent: 0 }];
    case 'attachStatus':
      return update(list, action.key, (item) => ({ ...item, status: action.status, percent: action.status === 'uploading' ? 0 : item.percent }));
    case 'attachProgress':
      return update(list, action.key, (item) => (item.status === 'uploading' ? { ...item, percent: clampPercent(action.percent) } : item));
    case 'attachReady':
      return update(list, action.key, ({ failure: _failure, ...item }) => ({ ...item, status: 'ready', percent: 100, mediaId: action.mediaId }));
    case 'attachFailed':
      return update(list, action.key, (item) => ({ ...item, status: 'failed', failure: action.message }));
    case 'attachRetry':
      return update(list, action.key, ({ failure: _failure, ...item }) => ({ ...item, status: 'preparing', percent: 0 }));
    case 'detach':
      return list.some((item) => item.key === action.key) ? list.filter((item) => item.key !== action.key) : list;
    case 'move': {
      const index = list.findIndex((item) => item.key === action.key);
      const target = index + action.direction;
      if (index < 0 || target < 0 || target >= list.length) return list;
      const next = [...list];
      [next[index], next[target]] = [next[target]!, next[index]!];
      return next;
    }
  }
}

export const readyAttachments = (list: readonly Attachment[]): Attachment[] => list.filter((item) => item.status === 'ready' && item.mediaId);
export const anyUploading = (list: readonly Attachment[]): boolean => list.some((item) => item.status === 'preparing' || item.status === 'uploading');
export const anyFailed = (list: readonly Attachment[]): boolean => list.some((item) => item.status === 'failed');
