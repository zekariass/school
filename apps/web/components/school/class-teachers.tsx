"use client";

import { useQuery } from "@tanstack/react-query";
import { UserPlusIcon, UserMinusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, hasProblemCode } from "@/lib/console/errors";
import type { ClassAbilities } from "@/lib/school/class-view";
import { canRemoveTeacher } from "@/lib/school/class-teachers";
import {
  fetchClassTeachers,
  fetchStaff,
  fetchSubjects,
  sk,
  type ClassTeacher,
  type ClassView,
  type TeacherRole,
} from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { ConfirmDialog } from "./confirm-dialog";
import { SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";
import { Input } from "@/components/ui/input";
import { FormField } from "@/components/console/form-field";

const ROLES: readonly TeacherRole[] = ["LEAD", "CO_TEACHER", "SUBJECT_TEACHER", "ASSISTANT"];

export function ClassTeachersTab({ cls, abilities }: { cls: ClassView; abilities: ClassAbilities }) {
  const t = useTranslations("school.classTeachers");
  const tRole = useTranslations("school.teacherRole");
  const { school, userId } = useSchool();
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<ClassTeacher | null>(null);
  const teachers = useQuery({
    queryKey: sk.classTeachers(school.id, cls.id),
    queryFn: () => fetchClassTeachers(school.id, cls.id),
  });
  const rows = teachers.data ?? [];

  const remove = useSubmit({
    mutationFn: async (classTeacherId: string) =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/classes/{classId}/teachers/{classTeacherId}", {
          params: { path: { schoolId: school.id, classId: cls.id, classTeacherId } },
        }),
      ),
    invalidate: [sk.classes(school.id)],
    successMessage: t("removed"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => setRemoving(null),
  });
  const lastLead = hasProblemCode(remove.error, "class.last_lead");

  return (
    <div className="flex flex-col gap-4">
      {abilities.canLead && abilities.rosterOpen ? (
        <div>
          <Button onClick={() => setAdding(true)}>
            <UserPlusIcon className="size-4" aria-hidden />
            {t("add")}
          </Button>
        </div>
      ) : null}
      {teachers.isPending ? (
        <LoadingRows rows={2} />
      ) : teachers.isError ? (
        <ErrorNotice message={errorMessage(teachers.error, t("loadFailed"))} onRetry={() => void teachers.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="flex flex-col gap-2">
          {rows.map((teacher) => {
            const mayRemove = abilities.rosterOpen && canRemoveTeacher(teacher, rows, { canLead: abilities.canLead, userId });
            return (
              <li key={teacher.id} className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-3">
                <StudentAvatar id={teacher.teacherId} name={teacher.displayName} mediaId={teacher.avatar?.id} size={48} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-semibold">{teacher.displayName}</span>
                  <span className="flex flex-wrap items-center gap-2 pt-0.5">
                    <Badge variant={teacher.role === "LEAD" ? "default" : "secondary"}>{tRole(teacher.role)}</Badge>
                    {teacher.subjectName ? (
                      <span className="text-sm text-muted-foreground">{teacher.subjectName}</span>
                    ) : null}
                  </span>
                </span>
                {mayRemove ? (
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => {
                      remove.reset();
                      setRemoving(teacher);
                    }}
                  >
                    <UserMinusIcon className="size-4" aria-hidden />
                    {teacher.teacherId === userId ? t("leave") : t("remove")}
                  </Button>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}
      {adding ? <AddTeacherDialog cls={cls} onClose={() => setAdding(false)} /> : null}
      {removing ? (
        <ConfirmDialog
          title={t("removeTitle", { name: removing.displayName })}
          description={t("removeBody", { class: cls.name })}
          confirmLabel={t("removeConfirm")}
          pending={remove.isPending}
          error={remove.isError ? (lastLead ? t("lastLead") : errorMessage(remove.error, t("failed"))) : null}
          onConfirm={() => remove.mutate(removing.id)}
          onClose={() => setRemoving(null)}
        />
      ) : null}
    </div>
  );
}

function AddTeacherDialog({ cls, onClose }: { cls: ClassView; onClose: () => void }) {
  const t = useTranslations("school.classTeachers.addDialog");
  const tRole = useTranslations("school.teacherRole");
  const { school } = useSchool();
  const [search, setSearch] = useState("");
  const [teacherId, setTeacherId] = useState("");
  const [role, setRole] = useState<TeacherRole>("CO_TEACHER");
  const [subjectId, setSubjectId] = useState("");
  const [localError, setLocalError] = useState<string | null>(null);
  const q = useDebounced(search.trim());
  const staff = useQuery({
    queryKey: sk.staffList(school.id, { picker: true, q }),
    queryFn: () => fetchStaff(school.id, { staffType: "TEACHER", status: "ACTIVE", q }, undefined, 50),
    staleTime: 30_000,
  });
  const subjects = useQuery({ queryKey: sk.subjects(school.id), queryFn: () => fetchSubjects(school.id), staleTime: 60_000 });

  const add = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/teachers", {
          params: { path: { schoolId: school.id, classId: cls.id } },
          body: { teacherId, role, ...(role === "SUBJECT_TEACHER" && subjectId ? { subjectId } : {}) },
        }),
      ),
    invalidate: [sk.classes(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  return (
    <FormDialog
      title={t("title", { name: cls.name })}
      description={t("description")}
      submitLabel={t("submit")}
      pending={add.isPending}
      error={localError ?? (add.isError ? errorMessage(add.error, t("failed")) : null)}
      onSubmit={() => {
        if (!teacherId) return setLocalError(t("chooseTeacher"));
        if (role === "SUBJECT_TEACHER" && !subjectId) return setLocalError(t("chooseSubject"));
        setLocalError(null);
        add.mutate();
      }}
      onClose={onClose}
    >
      <FormField label={t("search")}>
        {(props) => <Input {...props} type="search" value={search} onChange={(e) => setSearch(e.target.value)} />}
      </FormField>
      <SelectField
        label={t("teacher")}
        value={teacherId}
        onChange={setTeacherId}
        placeholder={staff.isPending ? t("loading") : t("choose")}
        options={(staff.data?.items ?? []).map((s) => ({ value: s.userId, label: s.displayName }))}
        hint={staff.data && staff.data.items.length === 0 ? t("noTeachers") : undefined}
      />
      <SelectField
        label={t("role")}
        value={role}
        onChange={(v) => setRole(v as TeacherRole)}
        options={ROLES.map((r) => ({ value: r, label: tRole(r) }))}
      />
      {role === "SUBJECT_TEACHER" ? (
        <SelectField
          label={t("subject")}
          value={subjectId}
          onChange={setSubjectId}
          placeholder={t("chooseSubjectPlaceholder")}
          options={(subjects.data ?? []).filter((s) => s.active).map((s) => ({ value: s.id, label: s.name }))}
        />
      ) : null}
    </FormDialog>
  );
}
