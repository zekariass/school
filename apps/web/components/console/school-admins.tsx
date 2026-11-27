"use client";

import { useQueries, useQuery } from "@tanstack/react-query";
import { PlusIcon, Trash2Icon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
import { phoneSchema, toFieldErrors, z } from "@school/validation";
import { asCountryCode } from "@school/validation/country";
import { useFormatInstant } from "@/components/calendar-preference";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { ApiRequestError, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors, hasProblemCode } from "@/lib/console/errors";
import {
  fetchSchoolAdmins,
  fetchUser,
  queryKeys,
  type RoleAssignment,
  type School,
} from "@/lib/console/queries";
import { FormField } from "./form-field";
import { EmptyNotice, ErrorNotice, LoadingRows } from "./states";
import { useSubmit } from "./use-submit";

const SCHOOL_ADMIN = "SCHOOL_ADMIN";

export function SchoolAdmins({ school }: { school: School }) {
  const t = useTranslations("console");
  const formatInstant = useFormatInstant();
  const [adding, setAdding] = useState(false);
  const [revoking, setRevoking] = useState<RoleAssignment | null>(null);

  const admins = useQuery({
    queryKey: queryKeys.admins(school.id),
    queryFn: () => fetchSchoolAdmins(school.id),
  });
  const rows = admins.data?.items ?? [];
  const users = useQueries({
    queries: rows.map((a) => ({
      queryKey: queryKeys.user(a.userId),
      queryFn: () => fetchUser(a.userId),
      staleTime: 300_000,
    })),
  });

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">{t("admins.title")}</h2>
          <p className="text-sm text-muted-foreground">{t("admins.hint")}</p>
        </div>
        <Button onClick={() => setAdding(true)}>
          <PlusIcon className="size-4" aria-hidden />
          {t("admins.add")}
        </Button>
      </div>

      {admins.isPending ? (
        <LoadingRows rows={2} />
      ) : admins.isError ? (
        <ErrorNotice message={errorMessage(admins.error, t("loadFailed"))} onRetry={() => void admins.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("admins.empty")}</EmptyNotice>
      ) : (
        <div className="rounded-2xl border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t("admins.person")}</TableHead>
                <TableHead>{t("admins.role")}</TableHead>
                <TableHead>{t("admins.since")}</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((a, i) => {
                const user = users[i]?.data;
                return (
                  <TableRow key={a.id}>
                    <TableCell>
                      <div className="font-semibold">{user?.displayName ?? "…"}</div>
                      <div className="text-sm text-muted-foreground">{user?.maskedDestination ?? ""}</div>
                    </TableCell>
                    <TableCell>{a.roleCode === SCHOOL_ADMIN ? t("admins.schoolAdmin") : a.roleCode}</TableCell>
                    <TableCell>{formatInstant(a.grantedAt)}</TableCell>
                    <TableCell>
                      <Button
                        size="icon"
                        variant="ghost"
                        aria-label={t("admins.revoke")}
                        onClick={() => setRevoking(a)}
                      >
                        <Trash2Icon className="size-4" aria-hidden />
                      </Button>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      )}

      {adding ? <AddAdminDialog school={school} onClose={() => setAdding(false)} /> : null}
      {revoking ? (
        <RevokeDialog
          school={school}
          assignment={revoking}
          name={users[rows.findIndex((r) => r.id === revoking.id)]?.data?.displayName}
          onClose={() => setRevoking(null)}
        />
      ) : null}
    </div>
  );
}

function RevokeDialog({
  school,
  assignment,
  name,
  onClose,
}: {
  school: School;
  assignment: RoleAssignment;
  name?: string;
  onClose: () => void;
}) {
  const t = useTranslations("console");
  const revoke = useSubmit({
    mutationFn: async () =>
      unwrap(
        await getBrowserApi().DELETE("/api/v1/platform/role-assignments/{id}", {
          params: { path: { id: assignment.id } },
        }),
      ),
    invalidate: [queryKeys.admins(school.id)],
    successMessage: t("admins.revoked"),
    errorFallback: t("saveFailed"),
    onSuccess: onClose,
  });
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("admins.revokeTitle")}</DialogTitle>
          <DialogDescription>{t("admins.revokeBody", { name: name ?? "", school: school.name })}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("cancel")}
          </Button>
          <Button variant="destructive" disabled={revoke.isPending} onClick={() => revoke.mutate()}>
            {t("admins.revoke")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

type Person = { id: string; displayName: string; maskedDestination: string };

/**
 * Add a school admin: look the person up by phone; if there is no such user, offer to
 * create one (the administrator vouches for the number), then assign SCHOOL_ADMIN here.
 */
function AddAdminDialog({ school, onClose }: { school: School; onClose: () => void }) {
  const t = useTranslations("console");
  const translate = t as unknown as (key: string, values?: Record<string, string | number>) => string;
  const locale = useLocale();
  const [phoneText, setPhoneText] = useState("");
  const [phone, setPhone] = useState<string | null>(null);
  const [phoneError, setPhoneError] = useState<string | null>(null);
  const [person, setPerson] = useState<Person | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [givenName, setGivenName] = useState("");
  const [familyName, setFamilyName] = useState("");
  const [nameError, setNameError] = useState<string | null>(null);

  const lookup = useSubmit({
    mutationFn: async (normalized: string) => {
      try {
        return unwrap(
          await getBrowserApi().GET("/api/v1/platform/users/lookup", { params: { query: { phone: normalized } } }),
        );
      } catch (error) {
        if (error instanceof ApiRequestError && error.status === 404) return null;
        throw error;
      }
    },
    errorFallback: t("saveFailed"),
    onSuccess: (found, normalized) => {
      setPhone(normalized);
      setPerson(found);
      setNotFound(found === null);
    },
  });

  const create = useSubmit({
    mutationFn: async (_: void, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/platform/users", {
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            phone: phone ?? "",
            givenName: givenName.trim(),
            ...(familyName.trim() ? { familyName: familyName.trim() } : {}),
            preferredLanguage: locale,
          },
        }),
      ),
    successMessage: t("admins.userCreated"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: (created) => {
      setPerson({ id: created.id, displayName: created.displayName, maskedDestination: created.maskedPhone ?? "" });
      setNotFound(false);
    },
  });

  const assign = useSubmit({
    mutationFn: async (userId: string, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/platform/role-assignments", {
          headers: { "Idempotency-Key": idempotencyKey },
          body: { userId, roleCode: SCHOOL_ADMIN, scope: { type: "SCHOOL", schoolId: school.id } },
        }),
      ),
    invalidate: [queryKeys.admins(school.id)],
    successMessage: t("admins.assigned"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });

  function onLookup(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const parsed = phoneSchema(asCountryCode(school.countryCode)).safeParse(phoneText);
    if (!parsed.success) {
      const error = toFieldErrors(parsed.error as z.ZodError)[""];
      setPhoneError(translate(error?.key ?? "validation.phone", error?.values));
      return;
    }
    setPhoneError(null);
    setPerson(null);
    setNotFound(false);
    lookup.mutate(parsed.data);
  }

  const createErrors = fieldErrors(create.error);
  const taken = hasProblemCode(create.error, "user.phone_taken");

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{t("admins.add")}</DialogTitle>
          <DialogDescription>{t("admins.addHint", { school: school.name })}</DialogDescription>
        </DialogHeader>

        <form noValidate onSubmit={onLookup} className="flex items-end gap-3">
          <FormField label={t("admins.phone")} hint={t("admins.phoneHint")} error={phoneError} className="flex-1">
            {(props) => (
              <Input
                {...props}
                type="tel"
                inputMode="tel"
                autoComplete="off"
                value={phoneText}
                onChange={(e) => {
                  setPhoneText(e.target.value);
                  setPerson(null);
                  setNotFound(false);
                }}
              />
            )}
          </FormField>
          <Button type="submit" variant="outline" disabled={lookup.isPending} className="mb-[1.625rem]">
            {t("admins.find")}
          </Button>
        </form>

        {person ? (
          <div className="flex flex-col gap-3 rounded-xl border bg-muted/40 p-4">
            <div>
              <p className="font-semibold">{person.displayName}</p>
              <p className="text-sm text-muted-foreground">{person.maskedDestination}</p>
            </div>
            {assign.isError ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {errorMessage(assign.error, t("saveFailed"))}
              </p>
            ) : null}
            <Button disabled={assign.isPending} onClick={() => assign.mutate(person.id)}>
              {assign.isPending ? t("saving") : t("admins.makeAdmin", { school: school.name })}
            </Button>
          </div>
        ) : null}

        {notFound ? (
          <form
            noValidate
            className="flex flex-col gap-3 rounded-xl border bg-muted/40 p-4"
            onSubmit={(event) => {
              event.preventDefault();
              if (!givenName.trim()) {
                setNameError(t("validation.required"));
                return;
              }
              setNameError(null);
              create.mutate();
            }}
          >
            <p className="text-sm">{t("admins.notFound")}</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label={t("admins.givenName")} error={nameError ?? createErrors.givenName}>
                {(props) => (
                  <Input {...props} value={givenName} maxLength={100} onChange={(e) => setGivenName(e.target.value)} />
                )}
              </FormField>
              <FormField label={t("admins.familyName")} error={createErrors.familyName}>
                {(props) => (
                  <Input {...props} value={familyName} maxLength={100} onChange={(e) => setFamilyName(e.target.value)} />
                )}
              </FormField>
            </div>
            <p className="text-xs text-muted-foreground">{t("admins.vouch")}</p>
            {create.isError ? (
              <p role="alert" className="text-sm font-medium text-destructive">
                {taken ? t("admins.phoneTaken") : errorMessage(create.error, t("saveFailed"))}
              </p>
            ) : null}
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? t("saving") : t("admins.createUser")}
            </Button>
          </form>
        ) : null}

        <DialogFooter>
          <Button variant="outline" onClick={onClose}>
            {t("cancel")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
