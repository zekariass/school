"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { useSubmit } from "@/components/console/use-submit";
import { Input } from "@/components/ui/input";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import {
  CLASS_COLORS,
  CLASS_ICONS,
  DEFAULT_CLASS_COLOR,
  DEFAULT_CLASS_ICON,
  isHexColor,
} from "@/lib/school/class-view";
import { required, type FieldError } from "@/lib/school/forms";
import {
  fetchGrades,
  fetchSubjects,
  fetchYears,
  sk,
  type ClassType,
  type ClassView,
} from "@/lib/school/queries";
import { cn } from "@/lib/utils";
import { SelectField, SwatchPicker } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";
import { StudentPicker } from "./student-picker";

const CLASS_TYPES: readonly ClassType[] = ["HOMEROOM", "SUBJECT", "CLUB", "OTHER"];

/** Quick create (name, grade, icon, color, subjects, first students) or edit a class. */
export function ClassFormDialog({
  cls,
  onClose,
  onSaved,
}: {
  /** Absent: create. */
  cls?: ClassView | null;
  onClose: () => void;
  onSaved?: (cls: ClassView) => void;
}) {
  const t = useTranslations("school.classes.form");
  const tType = useTranslations("school.classes.type");
  const tv = useTranslations("school.validation");
  const { school, permissions } = useSchool();
  const editing = !!cls;
  const isManager = permissions.has(SP.classManage);
  const [name, setName] = useState(cls?.name ?? "");
  const [gradeLevelId, setGradeLevelId] = useState(cls?.gradeLevelId ?? "");
  const [yearId, setYearId] = useState(cls?.academicYearId ?? "");
  const [classType, setClassType] = useState<string>(cls?.classType ?? "HOMEROOM");
  const [icon, setIcon] = useState(cls?.icon || DEFAULT_CLASS_ICON);
  const [color, setColor] = useState(isHexColor(cls?.color) ? cls.color : DEFAULT_CLASS_COLOR);
  const [subjectIds, setSubjectIds] = useState<string[]>(cls?.subjectIds ?? []);
  const [studentIds, setStudentIds] = useState<string[]>([]);
  const [nameError, setNameError] = useState<FieldError>(null);

  const grades = useQuery({ queryKey: sk.grades(school.id), queryFn: () => fetchGrades(school.id), staleTime: 60_000 });
  const subjects = useQuery({ queryKey: sk.subjects(school.id), queryFn: () => fetchSubjects(school.id), staleTime: 60_000 });
  const years = useQuery({ queryKey: sk.years(school.id), queryFn: () => fetchYears(school.id), staleTime: 60_000 });

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const shared = {
        name: name.trim(),
        gradeLevelId: gradeLevelId || undefined,
        classType: classType as ClassType,
        icon,
        color,
        subjectIds,
      };
      if (cls) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/classes/{classId}", {
            params: { path: { schoolId: school.id, classId: cls.id } },
            body: { ...shared, ...(isManager && yearId ? { academicYearId: yearId } : {}) },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/classes", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            ...shared,
            ...(yearId ? { academicYearId: yearId } : {}),
            ...(studentIds.length > 0 ? { studentIds } : {}),
          },
        }),
      );
    },
    invalidate: [sk.classes(school.id), sk.summary(school.id), sk.students(school.id)],
    successMessage: editing ? t("updated") : t("created"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: (saved) => {
      onSaved?.(saved);
      onClose();
    },
  });
  const server = fieldErrors(save.error);
  const forbidden = save.error instanceof ApiRequestError && save.error.status === 403;

  return (
    <FormDialog
      title={editing ? t("editTitle") : t("createTitle")}
      description={editing ? undefined : t("createDescription")}
      submitLabel={editing ? undefined : t("create")}
      pending={save.isPending}
      error={
        save.isError && Object.keys(server).length === 0
          ? forbidden && !editing
            ? t("forbiddenCreate")
            : errorMessage(save.error, t("failed"))
          : null
      }
      onSubmit={() => {
        const error = required(name);
        setNameError(error);
        if (!error) save.mutate();
      }}
      onClose={onClose}
      className="sm:max-w-2xl"
    >
      <div className="flex items-center gap-4 rounded-2xl border p-3">
        <span
          aria-hidden
          style={{ backgroundColor: color }}
          className="grid size-16 shrink-0 place-items-center rounded-2xl text-4xl"
        >
          {icon}
        </span>
        <div className="min-w-0 flex-1">
          <FormField label={t("name")} hint={t("nameHint")} error={nameError ? tv(nameError) : (server.name ?? null)}>
            {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
          </FormField>
        </div>
      </div>
      <SwatchPicker label={t("icon")} value={icon} onChange={setIcon} choices={CLASS_ICONS} kind="emoji" />
      <SwatchPicker label={t("color")} value={color} onChange={setColor} choices={CLASS_COLORS} kind="color" />
      <div className="grid gap-4 sm:grid-cols-2">
        <SelectField
          label={t("grade")}
          value={gradeLevelId}
          onChange={setGradeLevelId}
          noneLabel={t("noGrade")}
          options={[...(grades.data ?? [])]
            .filter((g) => g.active || g.id === cls?.gradeLevelId)
            .sort((a, b) => a.sequence - b.sequence)
            .map((g) => ({ value: g.id, label: g.name }))}
        />
        <SelectField
          label={t("type")}
          value={classType}
          onChange={setClassType}
          options={CLASS_TYPES.map((v) => ({ value: v, label: tType(v) }))}
        />
      </div>
      {(!editing || isManager) && (years.data ?? []).length > 0 ? (
        <SelectField
          label={t("year")}
          hint={t("yearHint")}
          value={yearId}
          onChange={setYearId}
          noneLabel={t("currentYear")}
          options={(years.data ?? []).map((y) => ({ value: y.id, label: y.current ? `${y.name} ★` : y.name }))}
        />
      ) : null}
      {(subjects.data ?? []).filter((s) => s.active).length > 0 ? (
        <fieldset className="flex flex-col gap-1.5">
          <legend className="pb-1.5 text-sm leading-none font-medium">{t("subjects")}</legend>
          <div className="flex flex-wrap gap-2">
            {(subjects.data ?? [])
              .filter((s) => s.active || subjectIds.includes(s.id))
              .map((s) => {
                const on = subjectIds.includes(s.id);
                return (
                  <label
                    key={s.id}
                    className={cn(
                      "flex min-h-11 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-medium transition-colors",
                      on ? "border-primary bg-primary/10" : "hover:bg-accent",
                    )}
                  >
                    <input
                      type="checkbox"
                      className="sr-only"
                      checked={on}
                      onChange={() => setSubjectIds(on ? subjectIds.filter((id) => id !== s.id) : [...subjectIds, s.id])}
                    />
                    {s.name}
                  </label>
                );
              })}
          </div>
        </fieldset>
      ) : null}
      {!editing ? <StudentPicker label={t("students")} value={studentIds} onChange={setStudentIds} /> : null}
    </FormDialog>
  );
}
