"use client";

import { useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { useSubmit } from "@/components/console/use-submit";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors, hasProblemCode } from "@/lib/console/errors";
import { pastDate, required, todayIso, validUsername, type FieldError } from "@/lib/school/forms";
import { fetchClasses, sk, type StudentView } from "@/lib/school/queries";
import { SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";

const GENDERS = ["FEMALE", "MALE", "OTHER"] as const;

/** Create a student (optionally into a class) or edit one. */
export function StudentFormDialog({
  student,
  classId,
  onClose,
  onSaved,
}: {
  /** Absent: create. */
  student?: StudentView | null;
  /** Preselected class when creating (e.g. from a class roster). */
  classId?: string;
  onClose: () => void;
  onSaved?: (student: StudentView) => void;
}) {
  const t = useTranslations("school.students.form");
  const tGender = useTranslations("school.students.gender");
  const tv = useTranslations("school.validation");
  const { school, permissions } = useSchool();
  const editing = !!student;
  const [givenName, setGivenName] = useState(student?.givenName ?? "");
  const [middleName, setMiddleName] = useState(student?.middleName ?? "");
  const [familyName, setFamilyName] = useState(student?.familyName ?? "");
  const [dateOfBirth, setDateOfBirth] = useState(student?.dateOfBirth ?? "");
  const [gender, setGender] = useState(student?.gender ?? "");
  const [studentNumber, setStudentNumber] = useState(student?.studentNumber ?? "");
  const [username, setUsername] = useState(student?.username ?? "");
  const [chosenClass, setChosenClass] = useState(classId ?? "");
  const [errors, setErrors] = useState<Record<string, FieldError>>({});

  const classes = useQuery({
    queryKey: sk.classList(school.id, { status: "ACTIVE", picker: true }),
    queryFn: () => fetchClasses(school.id, { status: "ACTIVE" }, undefined, 100),
    enabled: !editing,
    staleTime: 60_000,
  });

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      const body = {
        givenName: givenName.trim(),
        middleName: middleName.trim() || undefined,
        familyName: familyName.trim() || undefined,
        dateOfBirth: dateOfBirth || undefined,
        gender: gender || undefined,
        studentNumber: studentNumber.trim() || undefined,
        username: username.trim() || undefined,
      };
      if (student) {
        return unwrap(
          await api.PATCH("/api/v1/schools/{schoolId}/students/{studentId}", {
            params: { path: { schoolId: school.id, studentId: student.id } },
            body,
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/schools/{schoolId}/students", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { ...body, classId: chosenClass || undefined },
        }),
      );
    },
    invalidate: [sk.students(school.id), sk.summary(school.id), sk.classes(school.id)],
    successMessage: editing ? t("updated") : t("created"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: (saved) => {
      onSaved?.(saved);
      onClose();
    },
  });
  const server = fieldErrors(save.error);
  const numberTaken = hasProblemCode(save.error, "student.number_taken");
  const usernameTaken = hasProblemCode(save.error, "student.username_taken");
  const message = (key: string, local?: FieldError) => (local ? tv(local) : (server[key] ?? null));
  const canPickClass = !editing && (permissions.size > 0 || !!classId);

  function submit() {
    const next = {
      givenName: required(givenName),
      dateOfBirth: pastDate(dateOfBirth, todayIso()),
      username: validUsername(username),
    };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    save.mutate();
  }

  const generalError =
    save.isError && Object.keys(server).length === 0 && !numberTaken && !usernameTaken
      ? errorMessage(save.error, t("failed"))
      : null;

  return (
    <FormDialog
      title={editing ? t("editTitle") : t("addTitle")}
      description={editing ? undefined : t("addDescription")}
      submitLabel={editing ? undefined : t("create")}
      pending={save.isPending}
      error={generalError}
      onSubmit={submit}
      onClose={onClose}
    >
      <div className="grid gap-4 sm:grid-cols-3">
        <FormField label={t("givenName")} error={message("givenName", errors.givenName)}>
          {(props) => <Input {...props} value={givenName} maxLength={100} onChange={(e) => setGivenName(e.target.value)} />}
        </FormField>
        <FormField label={t("middleName")} error={message("middleName")}>
          {(props) => <Input {...props} value={middleName} maxLength={100} onChange={(e) => setMiddleName(e.target.value)} />}
        </FormField>
        <FormField label={t("familyName")} error={message("familyName")}>
          {(props) => <Input {...props} value={familyName} maxLength={100} onChange={(e) => setFamilyName(e.target.value)} />}
        </FormField>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("dateOfBirth")} error={message("dateOfBirth", errors.dateOfBirth)}>
          {(props) => (
            <Input {...props} type="date" max={todayIso()} value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} />
          )}
        </FormField>
        <SelectField
          label={t("gender")}
          value={gender}
          onChange={setGender}
          noneLabel={t("noGender")}
          options={[
            ...GENDERS.map((g) => ({ value: g, label: tGender(g) })),
            ...(gender && !(GENDERS as readonly string[]).includes(gender) ? [{ value: gender, label: gender }] : []),
          ]}
        />
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField
          label={t("studentNumber")}
          hint={t("studentNumberHint")}
          error={numberTaken ? errorMessage(save.error, t("numberTaken")) : message("studentNumber")}
        >
          {(props) => (
            <Input {...props} value={studentNumber} maxLength={64} onChange={(e) => setStudentNumber(e.target.value)} />
          )}
        </FormField>
        <FormField
          label={t("username")}
          hint={t("usernameHint")}
          error={usernameTaken ? errorMessage(save.error, t("usernameTaken")) : message("username", errors.username)}
        >
          {(props) => (
            <Input
              {...props}
              value={username}
              maxLength={31}
              autoCapitalize="none"
              spellCheck={false}
              onChange={(e) => setUsername(e.target.value.toLowerCase())}
            />
          )}
        </FormField>
      </div>
      {canPickClass ? (
        <SelectField
          label={t("class")}
          hint={t("classHint")}
          value={chosenClass}
          onChange={setChosenClass}
          noneLabel={t("noClass")}
          options={(classes.data?.items ?? []).map((c) => ({
            value: c.id,
            label: c.gradeLevelName ? `${c.name} · ${c.gradeLevelName}` : c.name,
          }))}
          disabled={!!classId}
        />
      ) : null}
    </FormDialog>
  );
}
