import { SymbolView } from 'expo-symbols';
import { useState } from 'react';
import { Pressable, View } from 'react-native';
import { useTranslations } from 'use-intl';

import { problemOf } from '@/lib/api-error';
import { useMe } from '@/lib/auth/use-me';
import { formatInstant } from '@/lib/calendar/format-instant';
import { useFormatInstant } from '@/lib/calendar/use-format-instant';
import {
  authorLabel,
  ageOf,
  canOfferTranslation,
  isMine,
  overflowActions,
  photosHidden,
  photosWarning,
  postInstant,
  postVariant,
  roleChip,
  splitHeadline,
  type OverflowAction,
} from '@/lib/feed/post-model';
import { useReact } from '@/lib/feed/queries';
import { useClock } from '@/lib/feed/use-clock';
import { translatePost } from '@/lib/feed/requests';
import type { PostView } from '@/lib/feed/types';
import { usePreferences } from '@/lib/preferences/preferences-provider';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from '../avatar';
import { Card, Text } from '../ui';
import { LinkedText } from '../announcements/linked-text';

import { MediaGrid } from './media-grid';
import { ReactionBar } from './reaction-bar';
import { StudentRow } from './student-row';
import { Tint } from './tint';

export interface PostCardProps {
  post: PostView;
  /** `detail` shows the whole text; `list` trims it and the card opens the post. */
  mode?: 'list' | 'detail';
  /** Whether the school switched "See translation" on (from the feed's capabilities); hidden otherwise. */
  translationEnabled?: boolean;
  /** The school's zone: scheduled times are shown in it, never the phone's. */
  timeZone?: string | undefined;
  onOpen?: (post: PostView) => void;
  onMenu?: (post: PostView, actions: OverflowAction[]) => void;
}

/**
 * A post as a warm, photo-first card: who, when, the words, the photos, who is in them, reactions and comments. An achievement
 * has a trophy badge and its students as avatars with name chips; an activity carries a chip. Everything shown comes from the
 * payload as the API filtered it for this viewer; the card never guesses a name.
 */
export function PostCard({ post, mode = 'list', translationEnabled = false, timeZone, onOpen, onMenu }: PostCardProps) {
  const t = useTranslations();
  const { colors, radii, spacing, minTouchTarget } = useAppTheme();
  const { locale } = usePreferences();
  const format = useFormatInstant();
  const me = useMe();
  const react = useReact(post.schoolId);
  const now = useClock();
  const [width, setWidth] = useState(0);
  const [reactionError, setReactionError] = useState<string | null>(null);

  const label = authorLabel(post.author);
  const authorText = label.kind === 'name' ? label.name : t(`mobile.feed.author.${label.role}`);
  const chip = roleChip(post.author);
  const variant = postVariant(post);
  const age = ageOf(postInstant(post), now);
  const when =
    age.unit === 'now'
      ? t('mobile.feed.age.now')
      : age.unit === 'minutes'
        ? t('mobile.feed.age.minutes', { value: age.value })
        : age.unit === 'hours'
          ? t('mobile.feed.age.hours', { value: age.value })
          : format(postInstant(post));
  const actions = overflowActions(post);
  const commentCount = post.commentCount ?? 0;
  const mine = isMine(post, me.data?.id);
  const open = mode === 'list' && onOpen ? () => onOpen(post) : undefined;
  const meta = [post.scope === 'SCHOOL' ? t('mobile.feed.schoolWide') : null, when, post.editedAt ? t('mobile.feed.edited') : null].filter(Boolean).join(' · ');

  return (
    <Card
      style={{
        // Full width, edge to edge, with a divider under the post (the list and the detail screen both pad their content by spacing[5]).
        marginHorizontal: -spacing[5],
        paddingHorizontal: spacing[5],
        paddingVertical: spacing[4],
        gap: spacing[3],
        borderRadius: 0,
        borderWidth: 0,
        borderBottomWidth: 1,
        borderBottomColor: colors.border,
        shadowOpacity: 0,
        elevation: 0,
      }}
      onLayout={(event) => setWidth(event.nativeEvent.layout.width - spacing[5] * 2)}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[3] }}>
        {variant.achievement ? (
          <View
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
            style={{ width: 44, height: 44, borderRadius: radii.full, overflow: 'hidden', alignItems: 'center', justifyContent: 'center' }}>
            <Tint color={colors.primary} opacity={0.16} />
            <SymbolView name={{ ios: 'trophy.fill', android: 'emoji_events', web: 'emoji_events' }} size={24} tintColor={colors.primary} />
          </View>
        ) : (
          <View accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
            <Avatar
              mediaId={post.author.avatar?.id}
              name={authorText}
              seed={post.author.userId ?? post.author.role}
              size={44}
              maxInitials={1}
              memoryOnly
            />
          </View>
        )}
        <View style={{ flex: 1, gap: 2 }}>
          {variant.achievement ? (
            <Text variant="heading" numberOfLines={1} style={{ color: colors.primary }}>
              {t('mobile.feed.type.ACHIEVEMENT')}
            </Text>
          ) : (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[2] }}>
              <Text variant="label" numberOfLines={1} style={{ flexShrink: 1 }}>
                {authorText}
                {mine ? ` · ${t('mobile.feed.you')}` : ''}
              </Text>
              {chip ? <RoleChip text={t(`mobile.feed.author.${chip}`)} /> : null}
            </View>
          )}
          <Text variant="caption" numberOfLines={1}>
            {variant.achievement ? `${authorText}${mine ? ` · ${t('mobile.feed.you')}` : ''} · ${meta}` : meta}
          </Text>
        </View>
        {actions.length > 0 && onMenu ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={t('mobile.feed.moreActions')}
            onPress={() => onMenu(post, actions)}
            hitSlop={6}
            style={{ width: minTouchTarget, height: minTouchTarget, alignItems: 'center', justifyContent: 'center' }}>
            <SymbolView name={{ ios: 'ellipsis', android: 'more_horiz', web: 'more_horiz' }} size={22} tintColor={colors.mutedForeground} />
          </Pressable>
        ) : null}
      </View>

      <Badges post={post} activity={variant.activityChip} />
      <Notices post={post} timeZone={timeZone} />

      <Pressable accessibilityRole={open ? 'button' : undefined} accessibilityHint={open ? t('mobile.feed.openPostHint') : undefined} onPress={open} disabled={!open} style={{ gap: spacing[3] }}>
        {post.body ? <Translatable post={post} mode={mode} enabled={translationEnabled} locale={locale} /> : null}
        {photosHidden(post) ? (
          <View
            accessibilityRole="text"
            style={{ padding: spacing[4], borderRadius: radii.xl, backgroundColor: colors.muted, gap: spacing[1], flexDirection: 'row', alignItems: 'center' }}>
            <SymbolView name={{ ios: 'eye.slash', android: 'visibility_off', web: 'visibility_off' }} size={22} tintColor={colors.mutedForeground} />
            <View style={{ flex: 1 }}>
              <Text variant="label">{t('mobile.feed.withheld.title')}</Text>
              <Text variant="caption">{t('mobile.feed.withheld.body')}</Text>
            </View>
          </View>
        ) : (
          <MediaGrid media={post.media} width={width} />
        )}
      </Pressable>

      {variant.studentRow ? (
        <StudentRow tags={post.tags} />
      ) : post.tags.length > 0 ? (
        <Text variant="caption" accessibilityLabel={t('mobile.feed.tagsLine', { names: post.tags.map((tag) => tag.displayName).join(', ') })}>
          {t('mobile.feed.tagsLine', { names: post.tags.map((tag) => tag.displayName).join(', ') })}
        </Text>
      ) : null}

      {post.status === 'PUBLISHED' ? (
        <View style={{ gap: spacing[2] }}>
          <ReactionBar
            counts={post.reactionCounts}
            mine={post.myReaction}
            onReact={(next) => {
              setReactionError(null);
              react(post, next).catch((error) => setReactionError(problemOf(error)?.detail ?? t('mobile.feed.reactionFailed')));
            }}>
            {post.commentsEnabled ? (
              <Pressable
                accessibilityRole="button"
                accessibilityLabel={t('mobile.feed.commentsButton', { count: commentCount })}
                onPress={() => onOpen?.(post)}
                disabled={!onOpen || mode === 'detail'}
                hitSlop={4}
                style={{
                  minHeight: minTouchTarget - 4,
                  minWidth: minTouchTarget + spacing[2],
                  paddingHorizontal: spacing[3],
                  flexDirection: 'row',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 6,
                  borderRadius: radii.full,
                  borderWidth: 1,
                  borderColor: colors.border,
                  backgroundColor: colors.card,
                }}>
                <SymbolView name={{ ios: 'bubble.left', android: 'chat_bubble', web: 'chat_bubble' }} size={18} tintColor={colors.mutedForeground} />
                <Text variant="label" style={{ color: colors.foreground }}>
                  {commentCount > 0 ? commentCount : t('mobile.feed.comment')}
                </Text>
              </Pressable>
            ) : null}
          </ReactionBar>
          {reactionError ? (
            <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
              {reactionError}
            </Text>
          ) : null}
          {!post.commentsEnabled || typeof post.seenCount === 'number' ? (
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[4] }}>
              {!post.commentsEnabled ? <Text variant="caption">{t('mobile.feed.commentsOff')}</Text> : null}
              {typeof post.seenCount === 'number' ? <Text variant="caption">{t('mobile.feed.seenCount', { count: post.seenCount })}</Text> : null}
            </View>
          ) : null}
        </View>
      ) : null}
    </Card>
  );
}

/** "Teacher", "Student": a small tinted pill beside the author's name. */
function RoleChip({ text }: { text: string }) {
  const { colors, radii, spacing } = useAppTheme();
  return (
    <View style={{ paddingHorizontal: spacing[2], paddingVertical: 1, borderRadius: radii.full, overflow: 'hidden' }}>
      <Tint color={colors.primary} opacity={0.16} />
      <Text variant="caption" numberOfLines={1} style={{ color: colors.foreground, fontSize: 12, lineHeight: 18 }}>
        {text}
      </Text>
    </View>
  );
}

/** The post's words, with "See translation" when the school allows it. A translation is always labelled as a machine's. */
function Translatable({ post, mode, enabled, locale }: { post: PostView; mode: 'list' | 'detail'; enabled: boolean; locale: string }) {
  const t = useTranslations();
  const theme = useAppTheme();
  const [state, setState] = useState<{ phase: 'idle' | 'loading' | 'shown' | 'hidden'; text?: string; error?: string }>({ phase: 'idle' });
  const [gone, setGone] = useState(false);
  const offer = !gone && canOfferTranslation(post, enabled, locale);

  async function load() {
    setState({ phase: 'loading' });
    try {
      const result = await translatePost(post.schoolId, post.id, locale);
      setState({ phase: 'shown', text: result.text });
    } catch (error) {
      const code = problemOf(error)?.code;
      // Off for this school (403) or no provider (503): the control goes away; anything else may be tried again.
      if (code === 'feed.translation_disabled' || code === 'feed.translation_unavailable') {
        setGone(true);
        setState({ phase: 'idle', error: problemOf(error)?.detail ?? t('mobile.feed.translation.unavailable') });
        return;
      }
      setState({ phase: 'idle', error: problemOf(error)?.detail ?? t('mobile.feed.translation.failed') });
    }
  }

  const shown = state.phase === 'shown' && state.text;
  const text = (shown ? state.text : post.body) ?? '';
  // Presentation only: a short first line is set in the bold face. The text itself is shown exactly as it is.
  const split = splitHeadline(text);
  const bold = { fontFamily: theme.text('heading').fontFamily };
  return (
    <View style={{ gap: 6 }}>
      <Text numberOfLines={mode === 'list' ? 8 : undefined} selectable={mode === 'detail'}>
        {split ? (
          <>
            <LinkedText text={split.headline} style={bold} />
            {split.separator}
            <LinkedText text={split.rest} />
          </>
        ) : (
          <LinkedText text={text} />
        )}
      </Text>
      {shown ? <Text variant="caption">{t('mobile.feed.translation.label')}</Text> : null}
      {offer ? (
        <Pressable
          accessibilityRole="button"
          accessibilityLabel={shown ? t('mobile.feed.translation.seeOriginal') : t('mobile.feed.translation.see')}
          disabled={state.phase === 'loading'}
          onPress={() => (shown ? setState({ phase: 'idle' }) : void load())}
          hitSlop={6}>
          <Text variant="label" tone="primary">
            {state.phase === 'loading' ? t('common.loading') : shown ? t('mobile.feed.translation.seeOriginal') : t('mobile.feed.translation.see')}
          </Text>
        </Pressable>
      ) : null}
      {state.error ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {state.error}
        </Text>
      ) : null}
    </View>
  );
}

function Badges({ post, activity }: { post: PostView; activity: boolean }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const items: { key: string; text: string; icon?: { ios: string; android: string }; warn?: boolean }[] = [];
  if (post.pinned) items.push({ key: 'pinned', text: t('mobile.feed.pinned'), icon: { ios: 'pin.fill', android: 'push_pin' } });
  if (activity) items.push({ key: 'activity', text: t('mobile.feed.type.ACTIVITY'), icon: { ios: 'calendar', android: 'event' } });
  if (post.contribution) items.push({ key: 'family', text: t('mobile.feed.familyContribution') });
  if (post.status !== 'PUBLISHED') items.push({ key: 'status', text: t(`mobile.feed.status.${post.status}`), warn: post.status === 'REJECTED' || post.status === 'HIDDEN' });
  if (items.length === 0) return null;
  return (
    <View style={{ flexDirection: 'row', flexWrap: 'wrap', gap: spacing[2] }}>
      {items.map((item) => {
        const color = item.warn ? colors.destructiveForeground : colors.foreground;
        return (
          <View
            key={item.key}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              gap: 4,
              paddingHorizontal: spacing[3],
              paddingVertical: 3,
              borderRadius: radii.full,
              overflow: 'hidden',
              backgroundColor: item.warn ? colors.destructive : colors.card,
            }}>
            {item.warn ? null : <Tint color={colors.primary} opacity={0.16} />}
            {item.icon ? <SymbolView name={{ ios: item.icon.ios, android: item.icon.android, web: item.icon.android } as never} size={13} tintColor={colors.primary} /> : null}
            <Text variant="caption" style={{ color }}>
              {item.text}
            </Text>
          </View>
        );
      })}
    </View>
  );
}

/** Why something is not live (the author sees this), and the warning that photos are hidden from everybody else. */
function Notices({ post, timeZone }: { post: PostView; timeZone: string | undefined }) {
  const t = useTranslations();
  const { colors, radii, spacing } = useAppTheme();
  const { locale, calendar } = usePreferences();
  const notices: string[] = [];
  if (post.status === 'REJECTED' && post.rejectionReason) notices.push(t('mobile.feed.notice.rejected', { reason: post.rejectionReason }));
  if (post.status === 'HIDDEN' && post.rejectionReason) notices.push(t('mobile.feed.notice.hidden', { reason: post.rejectionReason }));
  if (post.publishErrorCode) {
    const reason =
      post.publishErrorCode === 'feed.media_consent_missing'
        ? t('mobile.feed.publishError.consent')
        : post.publishErrorCode === 'feed.media_confirmation_required'
          ? t('mobile.feed.publishError.confirmation')
          : t('mobile.feed.publishError.other');
    notices.push(t('mobile.feed.notice.notPublished', { reason }));
  }
  if (post.status === 'PENDING_APPROVAL') notices.push(t('mobile.feed.notice.pending'));
  if (photosWarning(post)) notices.push(t('mobile.feed.notice.withheldForOthers'));
  if (post.status === 'SCHEDULED' && post.scheduledFor) {
    notices.push(t('mobile.feed.notice.scheduled', { when: formatInstant(post.scheduledFor, { locale, calendar, ...(timeZone ? { timeZone } : {}) }) }));
  }
  if (notices.length === 0) return null;
  return (
    <View style={{ gap: spacing[2] }} accessibilityRole="summary">
      {notices.map((text) => (
        <View key={text} style={{ padding: spacing[3], borderRadius: radii.lg, backgroundColor: colors.muted }}>
          <Text variant="caption" style={{ color: colors.foreground }}>
            {text}
          </Text>
        </View>
      ))}
    </View>
  );
}
