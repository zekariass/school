"use client";

import { useQuery } from "@tanstack/react-query";
import { ArrowRightLeftIcon, UserMinusIcon, UserPlusIcon, UserRoundPlusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { EmptyNotice, ErrorNotice, LoadingRows } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage } from "@/lib/console/errors";
import type { ClassAbilities } from "@/lib/school/class-view";
import { fetchClasses, fetchRoster, fetchStudent, sk, type ClassView, type RosterStudent } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";
import { StudentAvatar } from "./student-avatar";
import { StudentCard } from "./student-detail";
import { StudentFormDialog } from "./student-form";
import { StudentPicker } from "./student-picker";

/** ClassDojo-style roster: big friendly avatars; click one for the student's card, move and remove. */
export function ClassRosterTab({ cls, abilities }: { cls: ClassView; abilities: ClassAbilities }) {
  const t = useTranslations("school.roster");
  const { school } = useSchool();
  const [openId, setOpenId] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [creating, setCreating] = useState(false);
  const roster = useQuery({
    queryKey: sk.classRoster(school.id, cls.id),
    queryFn: () => fetchRoster(school.id, cls.id),
  });
  const canChange = abilities.canTeach && abilities.rosterOpen;
  const students = roster.data ?? [];

  return (
    <div className="flex flex-col gap-4">
      {canChange ? (
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => setAdding(true)}>
            <UserPlusIcon className="size-4" aria-hidden />
            {t("addExisting")}
          </Button>
          <Button variant="outline" onClick={() => setCreating(true)}>
            <UserRoundPlusIcon className="size-4" aria-hidden />
            {t("createNew")}
          </Button>
        </div>
      ) : null}

      {roster.isPending ? (
        <LoadingRows rows={3} />
      ) : roster.isError ? (
        <ErrorNotice message={errorMessage(roster.error, t("loadFailed"))} onRetry={() => void roster.refetch()} />
      ) : students.length === 0 ? (
        <EmptyNotice>{t("empty")}</EmptyNotice>
      ) : (
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
          {students.map((student) => (
            <li key={student.studentId}>
              <button
                type="button"
                onClick={() => setOpenId(student.studentId)}
                className="flex min-h-36 w-full flex-col items-center gap-2 rounded-2xl border bg-card p-3 text-center transition-all outline-none hover:-translate-y-0.5 hover:shadow-md focus-visible:ring-[3px] focus-visible:ring-ring/50"
              >
                <StudentAvatar
                  id={student.studentId}
                  name={student.displayName}
                  mediaId={student.avatar?.id}
                  config={student.avatarConfig}
                  size={80}
                />
                <span className="line-clamp-2 text-sm font-semibold break-words">{student.displayName}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      {openId ? (
        <StudentDrawer
          cls={cls}
          roster={students}
          studentId={openId}
          canChange={canChange}
          onClose={() => setOpenId(null)}
        />
      ) : null}
      {adding ? (
        <AddStudentsDialog cls={cls} existing={new Set(students.map((s) => s.studentId))} onClose={() => setAdding(false)} />
      ) : null}
      {creating ? <StudentFormDialog classId={cls.id} onClose={() => setCreating(false)} /> : null}
    </div>
  );
}

function AddStudentsDialog({
  cls,
  existing,
  onClose,
}: {
  cls: ClassView;
  existing: ReadonlySet<string>;
  onClose: () => void;
}) {
  const t = useTranslations("school.roster.add");
  const { school } = useSchool();
  const [ids, setIds] = useState<string[]>([]);
  const add = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/students", {
          params: { path: { schoolId: school.id, classId: cls.id } },
          body: { studentIds: ids },
        }),
      ),
    invalidate: [sk.classes(school.id), sk.students(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  return (
    <FormDialog
      title={t("title", { name: cls.name })}
      description={t("description")}
      submitLabel={t("submit", { count: ids.length })}
      pending={add.isPending}
      error={add.isError ? errorMessage(add.error, t("failed")) : null}
      onSubmit={() => ids.length > 0 && add.mutate()}
      onClose={onClose}
      className="sm:max-w-xl"
    >
      <StudentPicker label={t("label")} value={ids} onChange={setIds} exclude={existing} />
    </FormDialog>
  );
}

/** The student's card with the roster actions: move to another class, remove from this one. */
function StudentDrawer({
  cls,
  roster,
  studentId,
  canChange,
  onClose,
}: {
  cls: ClassView;
  roster: readonly RosterStudent[];
  studentId: string;
  canChange: boolean;
  onClose: () => void;
}) {
  const t = useTranslations("school.roster.drawer");
  const { school } = useSchool();
  const [moving, setMoving] = useState(false);
  const [removing, setRemoving] = useState(false);
  const rostered = roster.find((s) => s.studentId === studentId);
  const student = useQuery({
    queryKey: sk.studentOne(school.id, studentId),
    queryFn: () => fetchStudent(school.id, studentId),
  });

  const remove = useSubmit({
    mutationFn: async () =>
      ensureOk(
        await getBrowserApi().DELETE("/api/v1/schools/{schoolId}/classes/{classId}/students/{studentId}", {
          params: { path: { schoolId: school.id, classId: cls.id, studentId }, query: { reason: "REMOVED" } },
        }),
      ),
    invalidate: [sk.classes(school.id), sk.students(school.id)],
    successMessage: t("removed"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle className="sr-only">{rostered?.displayName ?? t("title")}</DialogTitle>
          <DialogDescription className="sr-only">{t("description")}</DialogDescription>
        </DialogHeader>
        {student.isPending ? (
          <LoadingRows rows={2} />
        ) : student.isError ? (
          <ErrorNotice message={errorMessage(student.error, t("loadFailed"))} onRetry={() => void student.refetch()} />
        ) : (
          <StudentCard student={student.data} compact />
        )}
        {canChange ? (
          <DialogFooter className="sm:justify-start">
            <Button variant="outline" onClick={() => setMoving(true)}>
              <ArrowRightLeftIcon className="size-4" aria-hidden />
              {t("move")}
            </Button>
            <Button variant="outline" className="text-destructive" onClick={() => setRemoving(true)}>
              <UserMinusIcon className="size-4" aria-hidden />
              {t("remove")}
            </Button>
          </DialogFooter>
        ) : null}
      </DialogContent>
      {moving ? (
        <MoveDialog
          cls={cls}
          studentId={studentId}
          name={rostered?.displayName ?? ""}
          onClose={() => setMoving(false)}
          onMoved={onClose}
        />
      ) : null}
      {removing ? (
        <ConfirmDialog
          title={t("removeTitle", { name: rostered?.displayName ?? "" })}
          description={t("removeBody", { class: cls.name })}
          confirmLabel={t("removeConfirm")}
          pending={remove.isPending}
          error={remove.isError ? errorMessage(remove.error, t("failed")) : null}
          onConfirm={() => remove.mutate()}
          onClose={() => setRemoving(false)}
        />
      ) : null}
    </Dialog>
  );
}

function MoveDialog({
  cls,
  studentId,
  name,
  onClose,
  onMoved,
}: {
  cls: ClassView;
  studentId: string;
  name: string;
  onClose: () => void;
  onMoved: () => void;
}) {
  const t = useTranslations("school.roster.move");
  const { school } = useSchool();
  const [target, setTarget] = useState("");
  const classes = useQuery({
    queryKey: sk.classList(school.id, { status: "ACTIVE", picker: true }),
    queryFn: () => fetchClasses(school.id, { status: "ACTIVE" }, undefined, 100),
    staleTime: 60_000,
  });
  const move = useSubmit({
    mutationFn: async () =>
      ensureOk(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/classes/{classId}/students/{studentId}/move", {
          params: { path: { schoolId: school.id, classId: cls.id, studentId } },
          body: { toClassId: target },
        }),
      ),
    invalidate: [sk.classes(school.id), sk.students(school.id)],
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: () => {
      onClose();
      onMoved();
    },
  });
  const options = (classes.data?.items ?? [])
    .filter((c) => c.id !== cls.id)
    .map((c) => ({ value: c.id, label: c.gradeLevelName ? `${c.name} · ${c.gradeLevelName}` : c.name }));
  return (
    <FormDialog
      title={t("title", { name })}
      description={t("description", { class: cls.name })}
      submitLabel={t("submit")}
      pending={move.isPending}
      error={move.isError ? errorMessage(move.error, t("failed")) : null}
      onSubmit={() => target && move.mutate()}
      onClose={onClose}
    >
      <SelectField
        label={t("target")}
        value={target}
        onChange={setTarget}
        placeholder={t("choose")}
        options={options}
        hint={options.length === 0 && !classes.isPending ? t("noOtherClass") : undefined}
      />
    </FormDialog>
  );
}
