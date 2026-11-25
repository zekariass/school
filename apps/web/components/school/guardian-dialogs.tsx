"use client";

import { useTranslations } from "next-intl";
import { useState } from "react";
import { RELATIONSHIP_TYPES } from "@school/validation/guardians";
import { FormField } from "@/components/console/form-field";
import { useSubmit } from "@/components/console/use-submit";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors } from "@/lib/console/errors";
import {
  PERMISSION_FLAGS,
  draftOf,
  isEmptyPatch,
  linkPatch,
  primaryConflict,
  readContact,
  type ContactFormValues,
  type LinkDraft,
} from "@/lib/school/families";
import { sk, type GuardianLink } from "@/lib/school/queries";
import { ConfirmDialog } from "./confirm-dialog";
import { CheckboxField, SelectField } from "./fields";
import { FormDialog } from "./form-dialog";
import { useSchool } from "./school-context";

type ValidationKey = "validation.required" | "validation.phone" | "validation.email";

/** Relationship choices, labelled in the user's language. */
function useRelationshipOptions() {
  const t = useTranslations("family.relationship");
  return RELATIONSHIP_TYPES.map((value) => ({ value, label: t(value) }));
}

/** Queries that change when a link or an invitation does. */
function useGuardianKeys(studentId?: string) {
  const { school } = useSchool();
  return [
    sk.guardians(school.id),
    ...(studentId ? [sk.studentConsent(school.id, studentId)] : []),
  ];
}

function ContactFields({
  values,
  onChange,
  errors,
  t,
}: {
  values: ContactFormValues;
  onChange: (next: ContactFormValues) => void;
  errors: Partial<Record<"phone" | "email", ValidationKey>>;
  t: (key: "via" | "sms" | "email" | "phone" | "emailLabel") => string;
}) {
  const tv = useTranslations("validation");
  return (
    <>
      <SelectField
        label={t("via")}
        value={values.via}
        onChange={(v) => onChange({ ...values, via: v as ContactFormValues["via"] })}
        options={[
          { value: "SMS", label: t("sms") },
          { value: "EMAIL", label: t("email") },
        ]}
      />
      {values.via === "SMS" ? (
        <FormField label={t("phone")} error={errors.phone ? tv(errors.phone.replace("validation.", "") as "phone") : null}>
          {(props) => (
            <Input
              {...props}
              type="tel"
              inputMode="tel"
              autoComplete="off"
              maxLength={32}
              value={values.phone}
              onChange={(e) => onChange({ ...values, phone: e.target.value })}
            />
          )}
        </FormField>
      ) : (
        <FormField label={t("emailLabel")} error={errors.email ? tv(errors.email.replace("validation.", "") as "email") : null}>
          {(props) => (
            <Input
              {...props}
              type="email"
              inputMode="email"
              autoComplete="off"
              maxLength={254}
              value={values.email}
              onChange={(e) => onChange({ ...values, email: e.target.value })}
            />
          )}
        </FormField>
      )}
    </>
  );
}

/** Invite an adult by SMS or email: they get a link to confirm their number, the school confirms the connection. */
export function InviteGuardianDialog({
  studentId,
  studentName,
  onClose,
}: {
  studentId: string;
  studentName: string;
  onClose: () => void;
}) {
  const t = useTranslations("school.families.inviteDialog");
  const { school } = useSchool();
  const keys = useGuardianKeys(studentId);
  const relationships = useRelationshipOptions();
  const [contact, setContact] = useState<ContactFormValues>({ via: "SMS", phone: "", email: "" });
  const [relationship, setRelationship] = useState<string>("MOTHER");
  const [name, setName] = useState("");
  const [errors, setErrors] = useState<Partial<Record<"phone" | "email", ValidationKey>>>({});

  const invite = useSubmit({
    mutationFn: async (body: { phone?: string; email?: string }, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/guardian-invitations", {
          params: { path: { schoolId: school.id, studentId } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            ...body,
            relationshipType: relationship as (typeof RELATIONSHIP_TYPES)[number],
            guardianName: name.trim() || undefined,
          },
        }),
      ),
    invalidate: keys,
    successMessage: t("sent"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(invite.error);

  return (
    <FormDialog
      title={t("title", { name: studentName })}
      description={t("body")}
      submitLabel={t("submit")}
      pending={invite.isPending}
      error={invite.isError ? errorMessage(invite.error, t("failed")) : null}
      onSubmit={() => {
        const read = readContact(contact);
        if (!read.ok) {
          setErrors({ [read.field]: read.key });
          return;
        }
        setErrors({});
        invite.mutate(read.contact);
      }}
      onClose={onClose}
    >
      <ContactFields values={contact} onChange={setContact} errors={errors} t={t} />
      <SelectField label={t("relationship")} value={relationship} onChange={setRelationship} options={relationships} />
      <FormField label={t("name")} hint={t("nameHint")} error={server.guardianName ?? null}>
        {(props) => <Input {...props} maxLength={100} autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />}
      </FormField>
    </FormDialog>
  );
}

/** Administrators link an adult the school knows: the link is active at once. */
export function AddGuardianDialog({
  studentId,
  studentName,
  onClose,
}: {
  studentId: string;
  studentName: string;
  onClose: () => void;
}) {
  const t = useTranslations("school.families.addDialog");
  const tInvite = useTranslations("school.families.inviteDialog");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const keys = [...useGuardianKeys(studentId), sk.studentGuardians(school.id, studentId)];
  const relationships = useRelationshipOptions();
  const [contact, setContact] = useState<ContactFormValues>({ via: "SMS", phone: "", email: "" });
  const [relationship, setRelationship] = useState<string>("MOTHER");
  const [name, setName] = useState("");
  const [nameError, setNameError] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<"phone" | "email", ValidationKey>>>({});

  const add = useSubmit({
    mutationFn: async (body: { phone?: string; email?: string }, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/students/{studentId}/guardians", {
          params: { path: { schoolId: school.id, studentId } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: { ...body, name: name.trim(), relationshipType: relationship as (typeof RELATIONSHIP_TYPES)[number] },
        }),
      ),
    invalidate: keys,
    successMessage: t("added"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  return (
    <FormDialog
      title={t("title", { name: studentName })}
      description={t("body")}
      submitLabel={t("submit")}
      pending={add.isPending}
      error={add.isError ? errorMessage(add.error, t("failed")) : null}
      onSubmit={() => {
        const read = readContact(contact);
        setNameError(!name.trim());
        if (!read.ok) {
          setErrors({ [read.field]: read.key });
          return;
        }
        setErrors({});
        if (!name.trim()) return;
        add.mutate(read.contact);
      }}
      onClose={onClose}
    >
      <FormField label={t("name")} error={nameError ? tv("required") : null}>
        {(props) => <Input {...props} maxLength={100} autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />}
      </FormField>
      <ContactFields
        values={contact}
        onChange={setContact}
        errors={errors}
        t={(key) => (key === "phone" ? t("phone") : key === "emailLabel" ? t("email") : tInvite(key))}
      />
      <SelectField label={t("relationship")} value={relationship} onChange={setRelationship} options={relationships} />
    </FormDialog>
  );
}

/** Flags, relationship, main contact and custody restriction of one link (administrators only). */
export function EditLinkDialog({ link, onClose }: { link: GuardianLink; onClose: () => void }) {
  const t = useTranslations("school.families.editDialog");
  const tFlags = useTranslations("school.families.editDialog.flags");
  const { school } = useSchool();
  const keys = [...useGuardianKeys(link.studentId), sk.studentGuardians(school.id, link.studentId)];
  const relationships = useRelationshipOptions();
  const [draft, setDraft] = useState<LinkDraft>(() => draftOf(link));
  const [conflict, setConflict] = useState(false);

  const save = useSubmit({
    mutationFn: async () => {
      const patch = linkPatch(link, draft);
      if (isEmptyPatch(patch)) return link;
      return unwrap(
        await getBrowserApi().PATCH("/api/v1/schools/{schoolId}/guardian-links/{linkId}", {
          params: { path: { schoolId: school.id, linkId: link.id } },
          body: patch as never,
        }),
      );
    },
    invalidate: keys,
    successMessage: t("saved"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  return (
    <FormDialog
      title={t("title", { name: link.guardianName })}
      submitLabel={undefined}
      pending={save.isPending}
      error={conflict ? t("restrictedPrimary") : save.isError ? errorMessage(save.error, t("failed")) : null}
      onSubmit={() => {
        const clash = primaryConflict(draft);
        setConflict(clash);
        if (!clash) save.mutate();
      }}
      onClose={onClose}
    >
      <SelectField
        label={t("relationship")}
        value={draft.relationshipType}
        onChange={(v) => setDraft({ ...draft, relationshipType: v })}
        options={relationships}
      />
      <CheckboxField label={t("primary")} checked={draft.primary} onChange={(v) => setDraft({ ...draft, primary: v })} />
      <fieldset className="flex flex-col gap-1">
        <legend className="pb-1 text-sm font-medium">{t("permissions")}</legend>
        {PERMISSION_FLAGS.map((flag) => (
          <CheckboxField
            key={flag}
            label={tFlags(flag)}
            checked={draft.permissions[flag]}
            onChange={(v) => setDraft({ ...draft, permissions: { ...draft.permissions, [flag]: v } })}
          />
        ))}
      </fieldset>
      <CheckboxField
        label={t("custody")}
        hint={t("custodyHint")}
        checked={draft.custodyRestricted}
        onChange={(v) => setDraft({ ...draft, custodyRestricted: v })}
      />
      {draft.custodyRestricted ? (
        <FormField label={t("note")}>
          {(props) => (
            <Textarea
              {...props}
              rows={3}
              maxLength={1000}
              value={draft.restrictionNote}
              onChange={(e) => setDraft({ ...draft, restrictionNote: e.target.value })}
            />
          )}
        </FormField>
      ) : null}
    </FormDialog>
  );
}

export function RevokeLinkDialog({ link, onClose }: { link: GuardianLink; onClose: () => void }) {
  const t = useTranslations("school.families.revokeDialog");
  const { school } = useSchool();
  const keys = [...useGuardianKeys(link.studentId), sk.studentGuardians(school.id, link.studentId)];
  const revoke = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/guardian-links/{linkId}/revoke", {
          params: { path: { schoolId: school.id, linkId: link.id } },
        }),
      ),
    invalidate: keys,
    successMessage: t("done"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  return (
    <ConfirmDialog
      title={t("title", { name: link.guardianName })}
      description={t("body", { child: link.studentName })}
      confirmLabel={t("confirm")}
      pending={revoke.isPending}
      error={revoke.isError ? errorMessage(revoke.error, t("failed")) : null}
      onConfirm={() => revoke.mutate()}
      onClose={onClose}
    />
  );
}

/** Approve or reject a waiting link, with an optional note only the school sees. */
export function DecisionDialog({
  link,
  decision,
  onClose,
}: {
  link: GuardianLink;
  decision: "approve" | "reject";
  onClose: () => void;
}) {
  const t = useTranslations("school.families.queue");
  const { school } = useSchool();
  const [note, setNote] = useState("");
  const keys = [...useGuardianKeys(link.studentId), sk.studentGuardians(school.id, link.studentId)];
  const decide = useSubmit({
    mutationFn: async () => {
      const params = { path: { schoolId: school.id, linkId: link.id } };
      const body = { note: note.trim() || undefined };
      return decision === "approve"
        ? unwrap(await getBrowserApi().POST("/api/v1/schools/{schoolId}/guardian-links/{linkId}/approve", { params, body }))
        : unwrap(await getBrowserApi().POST("/api/v1/schools/{schoolId}/guardian-links/{linkId}/reject", { params, body }));
    },
    invalidate: keys,
    successMessage: decision === "approve" ? t("approved") : t("rejected"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const values = { guardian: link.guardianName, child: link.studentName };
  return (
    <FormDialog
      title={decision === "approve" ? t("approveTitle", values) : t("rejectTitle", values)}
      description={decision === "approve" ? t("approveBody", values) : t("rejectBody", values)}
      submitLabel={decision === "approve" ? t("approve") : t("reject")}
      pending={decide.isPending}
      error={decide.isError ? errorMessage(decide.error, t("failed")) : null}
      onSubmit={() => decide.mutate()}
      onClose={onClose}
    >
      <FormField label={t("noteLabel")} hint={t("noteHint")}>
        {(props) => <Textarea {...props} rows={2} maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />}
      </FormField>
    </FormDialog>
  );
}
