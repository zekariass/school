"use client";

import { useTranslations } from "next-intl";
import { StudentAvatar } from "@/components/school/student-avatar";
import { cn } from "@/lib/utils";

export interface TaggableStudent {
  studentId: string;
  displayName: string;
  avatar?: { id: string } | null;
}

/**
 * Tag children in a post: a checklist of the roster (name and avatar only, BASIC data). Tagging matters for
 * consent: every tagged child needs media consent, and the guardians of tagged children are notified.
 */
export function StudentTags({
  students,
  value,
  onChange,
  max,
  label,
  hint,
  disabled,
}: {
  students: readonly TaggableStudent[];
  value: readonly string[];
  onChange: (ids: string[]) => void;
  max: number;
  label: string;
  hint?: string;
  disabled?: boolean;
}) {
  const t = useTranslations("school.feed.composer");
  const selected = new Set(value);

  function toggle(id: string) {
    if (selected.has(id)) onChange(value.filter((v) => v !== id));
    else if (value.length < max) onChange([...value, id]);
  }

  return (
    <fieldset className="flex flex-col gap-2" disabled={disabled}>
      <legend className="text-sm leading-none font-medium">{label}</legend>
      {hint ? <p className="text-sm text-muted-foreground">{hint}</p> : null}
      {students.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("noStudents")}</p>
      ) : (
        <ul className="max-h-48 overflow-y-auto rounded-xl border">
          {students.map((student) => {
            const checked = selected.has(student.studentId);
            return (
              <li key={student.studentId}>
                <label className={cn("flex min-h-12 cursor-pointer items-center gap-3 px-3 py-1.5 hover:bg-accent", checked && "bg-primary/5")}>
                  <input
                    type="checkbox"
                    className="size-5 accent-primary"
                    checked={checked}
                    onChange={() => toggle(student.studentId)}
                    data-testid="tag-student"
                  />
                  <StudentAvatar id={student.studentId} name={student.displayName} mediaId={student.avatar?.id} size={32} />
                  <span className="min-w-0 flex-1 truncate font-medium">{student.displayName}</span>
                </label>
              </li>
            );
          })}
        </ul>
      )}
      <p className="text-xs text-muted-foreground" aria-live="polite">
        {t("taggedCount", { count: value.length, max })}
      </p>
    </fieldset>
  );
}
