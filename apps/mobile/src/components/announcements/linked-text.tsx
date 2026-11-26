import * as WebBrowser from 'expo-web-browser';
import { useMemo } from 'react';
import { useTranslations } from 'use-intl';

import { linkify, safeWebUrl } from '@/lib/announcements/linkify';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text, type TextProps } from '../ui';

/**
 * Announcement text, shown strictly as text. The only thing added is a tappable link for an `http` or `https` address
 * (`linkify`); everything else, markup included, is printed as typed. A link opens in the in-app browser.
 */
export function LinkedText({ text, ...props }: { text: string } & Omit<TextProps, 'children'>) {
  const t = useTranslations();
  const { colors } = useAppTheme();
  const segments = useMemo(() => linkify(text), [text]);
  return (
    <Text {...props}>
      {segments.map((segment, index) =>
        segment.type === 'text' ? (
          segment.text
        ) : (
          <Text
            key={index}
            accessibilityRole="link"
            accessibilityLabel={t('mobile.announcements.openLink', { link: segment.text })}
            style={{ color: colors.primary, textDecorationLine: 'underline' }}
            onPress={() => {
              const url = safeWebUrl(segment.url);
              if (url) void WebBrowser.openBrowserAsync(url).catch(() => undefined);
            }}>
            {segment.text}
          </Text>
        ),
      )}
    </Text>
  );
}
