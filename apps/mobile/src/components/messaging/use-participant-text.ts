import { useCallback } from 'react';
import { useTranslations } from 'use-intl';

import { participantLabel, type ParticipantLabel } from '@/lib/messaging/derive';
import { RELATIONSHIP_LABEL } from '@/lib/messaging/labels';
import type { ActingAs, Participant } from '@/lib/messaging/types';

/**
 * Turns a person of a conversation into the words shown for them. Teachers, staff and students by name; a guardian only as a role
 * label ("Abebe's mother" to the child's teachers, "Parent" to other guardians, "Your guardian" to a student). The API decides what
 * a label may contain; this only prints it.
 */
export function useParticipantText() {
  const t = useTranslations();
  const text = useCallback(
    (label: ParticipantLabel): string => {
      switch (label.kind) {
        case 'you':
          return t('mobile.messaging.label.you');
        case 'name':
          return label.name;
        case 'guardianOf':
          return t('mobile.messaging.label.guardianOf', { child: label.child, relation: t(RELATIONSHIP_LABEL[label.relationship]) });
        case 'yourRelation':
          return t('mobile.messaging.label.yourRelation', { relation: t(RELATIONSHIP_LABEL[label.relationship]) });
        case 'parent':
          return t('mobile.messaging.label.parent');
        case 'yourGuardian':
          return t('mobile.messaging.label.yourGuardian');
        case 'member':
          return t('mobile.messaging.label.member');
      }
    },
    [t],
  );
  return useCallback(
    (person: Pick<Participant, 'self' | 'displayName' | 'actingAs' | 'guardian'> | undefined, viewer: ActingAs | undefined) => text(participantLabel(person, viewer)),
    [text],
  );
}
