import type { AnnouncementAudienceView, AudienceRole, AudienceType, AnnouncementView } from './types';

export interface AudienceLine {
  type: AudienceType;
  /** How many classes, grade levels or campuses (1 for the whole school; the people count for chosen users). */
  count: number;
  roles: AudienceRole[];
}

const ROLE_ORDER: AudienceRole[] = ['GUARDIAN', 'STUDENT', 'STAFF'];

/** The audience rows of an announcement grouped by kind and roles ("3 classes: guardians"), for the sender's summary. */
export function summarizeAudiences(audiences: readonly AnnouncementAudienceView[] | undefined): AudienceLine[] {
  const lines = new Map<string, AudienceLine>();
  for (const audience of audiences ?? []) {
    const roles = ROLE_ORDER.filter((role) => audience.roles.includes(role));
    const key = `${audience.type}:${roles.join(',')}`;
    const line = lines.get(key);
    if (line) line.count += 1;
    else lines.set(key, { type: audience.type, count: 1, roles });
  }
  return [...lines.values()];
}

/** The text shown for an edited announcement: the revision is above 1 or an edit time is set. */
export function wasEdited(view: Pick<AnnouncementView, 'revision' | 'editedAt'>): boolean {
  return view.revision > 1 || !!view.editedAt;
}

/**
 * The translation to offer a reader, if the school wrote one for their language and the text is not already in it.
 * Only people-written translations exist (no machine translation here).
 */
export function translationFor(
  view: Pick<AnnouncementView, 'translations' | 'bodyLanguageCode' | 'title' | 'body'>,
  locale: string,
): { title: string; body: string } | null {
  if (!view.body || view.bodyLanguageCode === locale) return null;
  const found = view.translations?.[locale];
  return found && (found.title || found.body) ? { title: found.title || view.title, body: found.body || view.body } : null;
}
