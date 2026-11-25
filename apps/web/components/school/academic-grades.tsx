"use client";

import { useQuery } from "@tanstack/react-query";
import { PencilIcon, PlusIcon, Trash2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import { CLASS_COLORS } from "@/lib/school/class-view";
import { isHexColor } from "@/lib/school/class-view";
import { required, type FieldError } from "@/lib/school/forms";
import { fetchGrades, fetchSubjects, sk, type GradeLevel, type Subject } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { CheckboxField, SwatchPicker } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";

export function GradesTab() {
  const t = useTranslations("school.academic.grades");
  const { school } = useSchool();
  const [editing, setEditing] = useState<GradeLevel | "new" | null>(null);
  const [deleting, setDeleting] = useState<GradeLevel | null>(null);
  const grades = useQuery({ queryKey: sk.grades(school.id), queryFn: () => fetchGrades(school.id) });
  const rows = [...(grades.data ?? [])].sort((a, b) => a.sequence - b.sequence);

  const remove = useSubmit({
    mutationFn: async (gradeId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/grade-levels/{gradeId}", {
          params: { path: { schoolId: school.id, gradeId } },
        }),
      ),
    invalidate: [sk.grades(school.id), sk.summary(school.id)],
    successMessage: t("deleted"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>
      {grades.isPending ? (
        <LoadingRows rows={3} />
      ) : grades.isError ? (
        <ErrorNotice message={errorMessage(grades.error, t("loadFailed"))} onRetry={() => void grades.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ol className="flex flex-col gap-2">
          {rows.map((grade) => (
            <li key={grade.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card px-4 py-2">
              <span className="grid size-9 place-items-center rounded-full bg-primary/10 font-bold text-primary">
                {grade.sequence}
              </span>
              <span className="min-w-0 flex-1">
                <span className="block font-semibold">{grade.name}</span>
                {grade.code ? <span className="block font-mono text-sm text-muted-foreground">{grade.code}</span> : null}
              </span>
              {!grade.active ? <Badge variant="outline">{t("inactive")}</Badge> : null}
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={t("edit", { name: grade.name })}
                onClick={() => setEditing(grade)}
              >
                <PencilIcon className="size-4" aria-hidden />
              </Button>
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={t("delete", { name: grade.name })}
                onClick={() => setDeleting(grade)}
              >
                <Trash2Icon className="size-4" aria-hidden />
              </Button>
            </li>
          ))}
        </ol>
      )}
      {editing !== null ? (
        <GradeDialog grade={editing === "new" ? null : editing} nextSequence={(rows.at(-1)?.sequence ?? 0) + 1} onClose={() => setEditing(null)} />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { name: deleting.name })}
          description={t("deleteBody")}
          confirmLabel={t("deleteConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("saveFailed")) : null}
          onConfirm={() => remove.mutate(deleting.id)}
          onClose={() => setDeleting(null)}
        />
      ) : null}
    </div>
  );
}

function GradeDialog({
  grade,
  nextSequence,
  onClose,
}: {
  grade: GradeLevel | null;
  nextSequence: number;
  onClose: () => void;
}) {
  const t = useTranslations("school.academic.grades");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [name, setName] = useState(grade?.name ?? "");
  const [code, setCode] = useState(grade?.code ?? "");
  const [sequence, setSequence] = useState(String(grade?.sequence ?? nextSequence));
  const [active, setActive] = useState(grade?.active ?? true);
  const [errors, setErrors] = useState<Record<string, FieldError>>({});

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const body = { name: name.trim(), code: code.trim() || undefined, sequence: Number(sequence) };
      if (grade) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/grade-levels/{gradeId}", {
            params: { path: { schoolId: school.id, gradeId: grade.id } },
            body: { ...body, active },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/grade-levels", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body,
        }),
      );
    },
    invalidate: [sk.grades(school.id), sk.summary(school.id), sk.classes(school.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const message = (key: string, local?: FieldError) => (local ? tv(local) : (server[key] ?? null));

  function submit() {
    const next = {
      name: required(name),
      sequence: /^[1-9]\d{0,3}$/.test(sequence.trim()) ? null : ("invalid" as const),
    };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    save.mutate();
  }

  return (
    <FormDialog
      title={grade ? t("editTitle") : t("add")}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("saveFailed")) : null}
      onSubmit={submit}
      onClose={onClose}
    >
      <FormField label={t("fields.name")} hint={t("fields.nameHint")} error={message("name", errors.name)}>
        {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("fields.code")} error={message("code")}>
          {(props) => <Input {...props} value={code} maxLength={32} onChange={(e) => setCode(e.target.value)} />}
        </FormField>
        <FormField label={t("fields.sequence")} hint={t("fields.sequenceHint")} error={message("sequence", errors.sequence)}>
          {(props) => <Input {...props} inputMode="numeric" value={sequence} onChange={(e) => setSequence(e.target.value)} />}
        </FormField>
      </div>
      {grade ? <CheckboxField label={t("fields.active")} checked={active} onChange={setActive} /> : null}
    </FormDialog>
  );
}

export function SubjectsTab() {
  const t = useTranslations("school.academic.subjects");
  const { school } = useSchool();
  const [editing, setEditing] = useState<Subject | "new" | null>(null);
  const [deleting, setDeleting] = useState<Subject | null>(null);
  const subjects = useQuery({ queryKey: sk.subjects(school.id), queryFn: () => fetchSubjects(school.id) });

  const remove = useSubmit({
    mutationFn: async (subjectId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/subjects/{subjectId}", {
          params: { path: { schoolId: school.id, subjectId } },
        }),
      ),
    invalidate: [sk.subjects(school.id), sk.summary(school.id)],
    successMessage: t("deleted"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: () => setDeleting(null),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-muted-foreground">{t("intro")}</p>
        <Button onClick={() => setEditing("new")}>
          <PlusIcon className="size-4" aria-hidden />
          {t("add")}
        </Button>
      </div>
      {subjects.isPending ? (
        <LoadingRows rows={3} />
      ) : subjects.isError ? (
        <ErrorNotice message={errorMessage(subjects.error, t("loadFailed"))} onRetry={() => void subjects.refetch()} />
      ) : subjects.data.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="grid gap-2 sm:grid-cols-2">
          {subjects.data.map((subject) => (
            <li key={subject.id} className="flex items-center gap-3 rounded-2xl border bg-card px-4 py-2">
              <span
                aria-hidden
                style={{ backgroundColor: isHexColor(subject.color) ? subject.color : "var(--muted)" }}
                className="size-6 shrink-0 rounded-full"
              />
              <span className="min-w-0 flex-1">
                <span className="block truncate font-semibold">{subject.name}</span>
                {subject.code ? <span className="block font-mono text-sm text-muted-foreground">{subject.code}</span> : null}
              </span>
              {!subject.active ? <Badge variant="outline">{t("inactive")}</Badge> : null}
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={t("edit", { name: subject.name })}
                onClick={() => setEditing(subject)}
              >
                <PencilIcon className="size-4" aria-hidden />
              </Button>
              <Button
                size="icon-sm"
                variant="ghost"
                aria-label={t("delete", { name: subject.name })}
                onClick={() => setDeleting(subject)}
              >
                <Trash2Icon className="size-4" aria-hidden />
              </Button>
            </li>
          ))}
        </ul>
      )}
      {editing !== null ? (
        <SubjectDialog subject={editing === "new" ? null : editing} onClose={() => setEditing(null)} />
      ) : null}
      {deleting ? (
        <ConfirmDialog
          title={t("deleteTitle", { name: deleting.name })}
          description={t("deleteBody")}
          confirmLabel={t("deleteConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("saveFailed")) : null}
          onConfirm={() => remove.mutate(deleting.id)}
          onClose={() => setDeleting(null)}
        />
      ) : null}
    </div>
  );
}

function SubjectDialog({ subject, onClose }: { subject: Subject | null; onClose: () => void }) {
  const t = useTranslations("school.academic.subjects");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [name, setName] = useState(subject?.name ?? "");
  const [code, setCode] = useState(subject?.code ?? "");
  const [color, setColor] = useState(isHexColor(subject?.color) ? subject.color : CLASS_COLORS[1]);
  const [active, setActive] = useState(subject?.active ?? true);
  const [nameError, setNameError] = useState<FieldError>(null);

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const body = { name: name.trim(), code: code.trim() || undefined, color };
      if (subject) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/subjects/{subjectId}", {
            params: { path: { schoolId: school.id, subjectId: subject.id } },
            body: { ...body, active },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/subjects", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body,
        }),
      );
    },
    invalidate: [sk.subjects(school.id), sk.summary(school.id)],
    successMessage: t("saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);

  return (
    <FormDialog
      title={subject ? t("editTitle") : t("add")}
      pending={save.isPending}
      error={save.isError && Object.keys(server).length === 0 ? errorMessage(save.error, t("saveFailed")) : null}
      onSubmit={() => {
        const error = required(name);
        setNameError(error);
        if (!error) save.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("fields.name")} error={nameError ? tv(nameError) : (server.name ?? null)}>
        {(props) => <Input {...props} value={name} maxLength={100} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <FormField label={t("fields.code")} error={server.code ?? null}>
        {(props) => <Input {...props} value={code} maxLength={32} onChange={(e) => setCode(e.target.value)} />}
      </FormField>
      <SwatchPicker label={t("fields.color")} value={color} onChange={setColor} choices={CLASS_COLORS} kind="color" />
      {subject ? <CheckboxField label={t("fields.active")} checked={active} onChange={setActive} /> : null}
    </FormDialog>
  );
}
