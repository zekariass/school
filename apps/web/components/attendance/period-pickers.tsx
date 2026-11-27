"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { SelectField } from "@/components/school/fields";
import { useSchool } from "@/components/school/school-context";
import { groupChoices, isPeriodSlot, subjectChoices, subjectEditable, subjectLabel, type SubjectChoice } from "@/lib/attendance/periods";
import { fetchGroups, fetchSubjects, sk, type ClassView } from "@/lib/school/queries";

/**
 * The subject and the saved student group of a roll (M7d). The subject is offered on a period roll (P1..P8) from the
 * class's own active subjects and is fixed once the roll exists; the group (an elective's roster) narrows the roster to
 * its members. Both are optional: the whole class is the default. Lists the API does not let this person read simply
 * leave the picker out (the API still checks every write).
 */
export function usePeriodChoices(cls: ClassView): { subjects: SubjectChoice[]; groups: ReturnType<typeof groupChoices> } {
  const { school } = useSchool();
  const subjects = useQuery({
    queryKey: sk.subjects(school.id),
    queryFn: () => fetchSubjects(school.id),
    staleTime: 5 * 60_000,
    retry: false,
  });
  const groups = useQuery({
    queryKey: sk.classGroups(school.id, cls.id),
    queryFn: () => fetchGroups(school.id, cls.id),
    staleTime: 60_000,
    retry: false,
  });
  return { subjects: subjectChoices(cls.subjectIds, subjects.data), groups: groupChoices(groups.data) };
}

export function PeriodPickers({
  slot,
  sessionId,
  rollSubjectName,
  choices,
  subjectId,
  groupId,
  disabled,
  onSubject,
  onGroup,
}: {
  slot: string;
  sessionId?: string | null;
  rollSubjectName?: string | null;
  choices: ReturnType<typeof usePeriodChoices>;
  /** The subject chosen for a roll that does not exist yet. */
  subjectId: string | null;
  /** The group the roster is restricted to (null: the whole class). */
  groupId: string | null;
  disabled?: boolean;
  onSubject: (subjectId: string | null) => void;
  onGroup: (groupId: string | null) => void;
}) {
  const t = useTranslations("school.attendance.periods");
  const showSubject = isPeriodSlot(slot) && (choices.subjects.length > 0 || !!rollSubjectName);
  const showGroup = choices.groups.length > 0;
  if (!showSubject && !showGroup) return null;
  const editable = subjectEditable({ slot, sessionId });
  const rollSubject = subjectLabel({ subjectName: rollSubjectName });
  // A subject that is no longer on offer (retired) still names the roll: shown as text, never as a stale option.
  const fixedText = !editable && showSubject;

  return (
    <div className="flex flex-wrap items-end gap-3" data-testid="period-pickers">
      {showSubject ? (
        fixedText ? (
          <p className="flex min-h-11 flex-col justify-center text-sm" data-testid="roll-subject-fixed">
            <span className="font-medium">{t("subject")}</span>
            <span>{rollSubject ?? t("noSubject")}</span>
          </p>
        ) : (
          <SelectField
            label={t("subject")}
            value={subjectId ?? ""}
            onChange={(v) => onSubject(v || null)}
            noneLabel={t("noSubject")}
            options={choices.subjects.map((s) => ({ value: s.id, label: s.name }))}
            className="w-56"
            disabled={disabled}
            hint={t("subjectHint")}
          />
        )
      ) : null}
      {showGroup ? (
        <SelectField
          label={t("group")}
          value={groupId ?? ""}
          onChange={(v) => onGroup(v || null)}
          noneLabel={t("wholeClass")}
          options={choices.groups.map((g) => ({ value: g.id, label: t("groupOption", { name: g.name, count: g.size }) }))}
          className="w-56"
          disabled={disabled}
          hint={t("groupHint")}
        />
      ) : null}
    </div>
  );
}
