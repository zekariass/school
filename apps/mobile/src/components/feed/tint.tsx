import { View } from 'react-native';

/**
 * A soft wash of one theme colour over its parent (which must clip it with `overflow: 'hidden'` and a radius). The tinted
 * surfaces of the feed (announcement card, chips, selected reaction) are the primary colour at low strength, so a change of
 * the brand colour in the design tokens changes them too, in light and dark.
 */
export function Tint({ color, opacity = 0.14 }: { color: string; opacity?: number }) {
  return (
    <View
      pointerEvents="none"
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: color, opacity }}
    />
  );
}
