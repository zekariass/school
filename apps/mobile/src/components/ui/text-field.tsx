import type { ComponentRef, Ref } from 'react';
import { TextInput, View, type TextInputProps } from 'react-native';

import { useAppTheme } from '@/lib/theme/theme-provider';

import { Text } from './text';

export interface TextFieldProps extends TextInputProps {
  label: string;
  /** Already-translated error message. */
  error?: string | undefined;
  hint?: string | undefined;
  /** Ref of the underlying input (React 19 passes `ref` as a prop). */
  ref?: Ref<ComponentRef<typeof TextInput>>;
}

export function TextField({ label, error, hint, style, ...props }: TextFieldProps) {
  const { colors, radii, spacing, minTouchTarget, text } = useAppTheme();
  const body = text('body');

  return (
    <View style={{ gap: spacing[2] }}>
      <Text variant="label">
        {label}
      </Text>
      <TextInput
        accessibilityLabel={label}
        accessibilityHint={hint}
        accessibilityState={{ disabled: props.editable === false }}
        placeholderTextColor={colors.mutedForeground}
        selectionColor={colors.primary}
        {...props}
        style={[
          {
            ...body,
            color: colors.foreground,
            backgroundColor: colors.card,
            borderColor: error ? colors.destructive : colors.input,
            borderWidth: error ? 2 : 1,
            borderRadius: radii.lg,
            minHeight: minTouchTarget + 8,
            paddingHorizontal: spacing[4],
            paddingVertical: spacing[2],
            // TextInput centers text vertically; a taller line box clips Ethiopic ascenders.
            lineHeight: undefined,
          },
          style,
        ]}
      />
      {error ? (
        <Text variant="caption" tone="destructive" accessibilityLiveRegion="polite">
          {error}
        </Text>
      ) : hint ? (
        <Text variant="caption">{hint}</Text>
      ) : null}
    </View>
  );
}
