import { useState } from 'react';
import { Pressable, View } from 'react-native';

import { chunk, gridLayout, type RosterEntry } from '@/lib/people/roster';
import { useAppTheme } from '@/lib/theme/theme-provider';

import { Avatar } from './avatar';
import { Text } from './ui';

export interface RosterGridProps {
  students: readonly RosterEntry[];
  onSelect: (student: RosterEntry) => void;
}

const GAP = 12;

/** ClassDojo-style wall of big, friendly avatars with first names, 3 to 4 per row. */
export function RosterGrid({ students, onSelect }: RosterGridProps) {
  const { spacing } = useAppTheme();
  const [width, setWidth] = useState(0);
  const layout = gridLayout(width, { gap: GAP });
  const rows = width > 0 ? chunk(students, layout.columns) : [];

  return (
    <View onLayout={(event) => setWidth(event.nativeEvent.layout.width)} style={{ gap: spacing[4] }}>
      {rows.map((row) => (
        <View key={row[0]!.id} style={{ flexDirection: 'row', gap: GAP }}>
          {row.map((student) => (
            <Pressable
              key={student.id}
              accessibilityRole="button"
              accessibilityLabel={student.name}
              onPress={() => onSelect(student)}
              style={({ pressed }) => ({
                width: layout.cellWidth,
                alignItems: 'center',
                gap: spacing[2],
                opacity: pressed ? 0.7 : 1,
              })}>
              <Avatar
                mediaId={student.avatarMediaId}
                name={student.name}
                seed={student.id}
                config={student.avatarConfig}
                size={layout.avatarSize}
                maxInitials={1}
              />
              <Text variant="label" numberOfLines={2} style={{ textAlign: 'center' }}>
                {student.firstName}
              </Text>
            </Pressable>
          ))}
        </View>
      ))}
    </View>
  );
}
