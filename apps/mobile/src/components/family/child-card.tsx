import { View } from 'react-native';
import { useTranslations } from 'use-intl';

import { Avatar } from '@/components/avatar';
import { Button, Card, Text } from '@/components/ui';
import type { MyChild, PendingLink } from '@/lib/family/queries';
import { useAppTheme } from '@/lib/theme/theme-provider';
import { teacherNames } from '@school/validation/family';

/**
 * One child: picture and name only, the relationship, and per school the classes and teachers the
 * link allows. No roster data, nothing about other children.
 */
export function ChildCard({
  child,
  consent,
  onReviewConsent,
  onOpenPoints,
}: {
  child: MyChild;
  /** Opens the child's points (school and home) when the link allows seeing or giving them. */
  onOpenPoints?: () => void;
  /** "needed" shows the way to the decision, "done" a calm confirmation, null nothing (not loaded). */
  consent: 'needed' | 'done' | null;
  onReviewConsent: () => void;
}) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  return (
    <Card accessibilityLabel={child.displayName}>
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: spacing[4] }}>
        <Avatar mediaId={child.avatar?.id} name={child.displayName} seed={child.studentId} config={child.avatarConfig} size={64} />
        <View style={{ flex: 1, gap: spacing[1] }}>
          <Text variant="heading">{child.displayName}</Text>
          <Text tone="muted">{t(`family.relationship.${child.relationshipType}`)}</Text>
        </View>
      </View>

      {child.schools.map((school) => {
        const teachers = teacherNames(school);
        return (
          <View key={school.schoolId} style={{ gap: spacing[1], padding: spacing[3], borderRadius: 16, backgroundColor: colors.muted }}>
            <Text variant="label">{t('family.school', { school: school.schoolName })}</Text>
            {!child.permissions.canViewAcademics ? (
              <Text tone="muted">{t('family.noAcademics')}</Text>
            ) : school.classes.length === 0 ? (
              <Text tone="muted">{t('family.noClasses')}</Text>
            ) : (
              <Text>{school.classes.map((cls) => `${cls.icon ? `${cls.icon} ` : ''}${cls.name}`).join('  ·  ')}</Text>
            )}
            {teachers.length > 0 ? <Text tone="muted">{t('family.teachers', { names: teachers.join(', ') })}</Text> : null}
          </View>
        );
      })}

      {onOpenPoints && (child.permissions.canViewAcademics || child.permissions.canAwardPoints) ? (
        <Button variant="ghost" label={t('mobile.points.child.open')} onPress={onOpenPoints} />
      ) : null}

      {consent === 'needed' ? (
        <Button variant="secondary" label={t('family.consentNeeded')} onPress={onReviewConsent} />
      ) : consent === 'done' ? (
        <Text tone="muted">{t('family.consentUpToDate')}</Text>
      ) : null}
    </Card>
  );
}

/** A connection request the school has not confirmed yet: calm, first name only. */
export function PendingCard({ link }: { link: PendingLink }) {
  const t = useTranslations();
  const { colors, spacing } = useAppTheme();
  return (
    <Card style={{ borderWidth: 1, borderStyle: 'dashed', borderColor: colors.border, boxShadow: undefined }}>
      <View style={{ gap: spacing[1] }}>
        <Text variant="heading">{link.childFirstName}</Text>
        <Text variant="label">{t('family.pendingStatus')}</Text>
        <Text tone="muted">
          {link.schoolName
            ? t('family.pendingBody', { school: link.schoolName, child: link.childFirstName })
            : t('family.pendingBodyNoSchool', { child: link.childFirstName })}
        </Text>
      </View>
    </Card>
  );
}
