/**
 * Which actions the options sheet of a message offers. Menus only: the server checks every action again (404 for what you may not
 * see, 403 for what you may not do). The choice leans protective: an observer can only report, a tombstone offers nothing except
 * a moderator's "show again", and a student is never offered a block of their own guardians (the API refuses it anyway).
 */

import { editRemainingMs, isSystemMessage, isTombstone, type ConversationAccess } from './derive';
import type { ActingAs, MessageItem } from './types';

export type MessageAction = 'reply' | 'react' | 'edit' | 'delete' | 'report' | 'block' | 'hide' | 'unhide';

export interface ActionContext {
  access: ConversationAccess;
  /** `ConversationView.canModerate`. */
  canModerate: boolean;
  viewer: ActingAs;
  editWindowMinutes: number;
  now: number;
}

export function messageActions(message: MessageItem, context: ActionContext): MessageAction[] {
  if (isSystemMessage(message)) return [];
  if (isTombstone(message)) return message.status === 'HIDDEN' && context.canModerate ? ['unhide'] : [];
  const actions: MessageAction[] = [];
  const writing = context.access.canCompose;
  if (message.mine) {
    if (writing) actions.push('reply', 'react');
    if (editRemainingMs(message, context.editWindowMinutes, context.now) > 0) actions.push('edit');
    if (message.canDelete) actions.push('delete');
    return actions;
  }
  if (writing) actions.push('reply', 'react');
  actions.push('report');
  const sender = message.sender;
  const blockable =
    !!sender && !sender.self && sender.role !== 'OBSERVER' && context.access.banner !== 'observer' && !(context.viewer === 'STUDENT' && sender.actingAs === 'GUARDIAN');
  if (blockable) actions.push('block');
  if (context.canModerate) actions.push('hide');
  return actions;
}
