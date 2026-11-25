"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { PlusIcon, SearchIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { FormField } from "@/components/console/form-field";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "@/components/console/states";
import { useSubmit } from "@/components/console/use-submit";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors, hasProblemCode } from "@/lib/console/errors";
import { SP } from "@/lib/school/access";
import { contact, required, type FieldError } from "@/lib/school/forms";
import { fetchCampuses, fetchStaff, sk, type StaffFilters, type StaffView } from "@/lib/school/queries";
import { useDebounced } from "@/lib/school/use-debounced";
import { FormDialog } from "./form-dialog";
import { SelectField } from "./fields";
import { PageHeader } from "./page-header";
import { RequirePermission } from "./require-permission";
import { useSchool } from "./school-context";
import { StaffDetailDialog } from "./staff-detail";
import { StudentAvatar } from "./student-avatar";

const TYPES = ["TEACHER", "ADMINISTRATOR", "SUPPORT_STAFF"] as const;
const STATUSES = ["ACTIVE", "INVITED", "SUSPENDED", "FORMER"] as const;
const ADMIN_ROLES = ["SCHOOL_ADMIN", "CAMPUS_ADMIN", "SCHOOL_STAFF"] as const;

export function TeachersView() {
  const t = useTranslations("school.teachers");
  const { school } = useSchool();
  const [search, setSearch] = useState("");
  const [type, setType] = useState("");
  const [status, setStatus] = useState("ACTIVE");
  const [adding, setAdding] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const q = useDebounced(search.trim());

  const filters: StaffFilters = {
    ...(type ? { staffType: type as StaffView["staffType"] } : {}),
    ...(status ? { status: status as StaffView["status"] } : {}),
    q,
  };
  const list = useInfiniteQuery({
    queryKey: sk.staffList(school.id, filters),
    queryFn: ({ pageParam }) => fetchStaff(school.id, filters, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  });
  const rows = list.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <RequirePermission anyOf={[SP.staff]}>
      <div className="mx-auto flex max-w-5xl flex-col gap-6">
        <PageHeader
          title={t("title")}
          description={t("subtitle")}
          actions={
            <Button onClick={() => setAdding(true)}>
              <PlusIcon className="size-4" aria-hidden />
              {t("add")}
            </Button>
          }
        />

        <div className="grid gap-3 sm:grid-cols-[1fr_12rem_12rem]">
          <div className="relative self-end">
            <SearchIcon className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden />
            <Input
              type="search"
              aria-label={t("search")}
              placeholder={t("search")}
              className="ps-9"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
          </div>
          <SelectField
            label={t("filters.type")}
            value={type}
            onChange={setType}
            noneLabel={t("filters.allTypes")}
            options={TYPES.map((v) => ({ value: v, label: t(`type.${v}`) }))}
          />
          <SelectField
            label={t("filters.status")}
            value={status}
            onChange={setStatus}
            noneLabel={t("filters.allStatuses")}
            options={STATUSES.map((v) => ({ value: v, label: t(`status.${v}`) }))}
          />
        </div>

        {list.isPending ? (
          <LoadingRows rows={5} />
        ) : list.isError ? (
          <ErrorNotice message={errorMessage(list.error, t("loadFailed"))} onRetry={() => void list.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyNotice>{q || type || status !== "ACTIVE" ? t("emptyFiltered") : t("empty")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-2">
            {rows.map((staff) => (
              <li key={staff.id}>
                <button
                  type="button"
                  onClick={() => setOpenId(staff.id)}
                  className="flex min-h-16 w-full items-center gap-3 rounded-2xl border bg-card p-3 text-start transition-colors outline-none hover:bg-accent focus-visible:ring-[3px] focus-visible:ring-ring/50"
                >
                  <StudentAvatar id={staff.userId} name={staff.displayName} mediaId={staff.avatar?.id} size={44} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate font-semibold">{staff.displayName}</span>
                    <span className="block truncate text-sm text-muted-foreground">
                      {[staff.jobTitle, staff.maskedPhone ?? staff.maskedEmail].filter(Boolean).join(" · ")}
                    </span>
                  </span>
                  <Badge variant="secondary">{t(`type.${staff.staffType}`)}</Badge>
                  {staff.status !== "ACTIVE" ? <Badge variant="outline">{t(`status.${staff.status}`)}</Badge> : null}
                </button>
              </li>
            ))}
          </ul>
        )}
        <LoadMore hasMore={!!list.hasNextPage} loading={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()} />

        {adding ? <AddStaffDialog onClose={() => setAdding(false)} /> : null}
        {openId ? <StaffDetailDialog staffId={openId} onClose={() => setOpenId(null)} /> : null}
      </div>
    </RequirePermission>
  );
}

function AddStaffDialog({ onClose }: { onClose: () => void }) {
  const t = useTranslations("school.teachers.addDialog");
  const tType = useTranslations("school.teachers.type");
  const tRole = useTranslations("school.teachers.adminRole");
  const tv = useTranslations("school.validation");
  const { school } = useSchool();
  const [givenName, setGivenName] = useState("");
  const [familyName, setFamilyName] = useState("");
  const [phone, setPhone] = useState("");
  const [email, setEmail] = useState("");
  const [staffType, setStaffType] = useState<string>("TEACHER");
  const [jobTitle, setJobTitle] = useState("");
  const [campusId, setCampusId] = useState("");
  const [adminRole, setAdminRole] = useState("");
  const [errors, setErrors] = useState<Record<string, FieldError>>({});
  const campuses = useQuery({
    queryKey: sk.campuses(school.id),
    queryFn: () => fetchCampuses(school.id),
    staleTime: 300_000,
    retry: false,
  });

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) =>
      unwrap(
        await getBrowserApi().POST("/api/v1/schools/{schoolId}/staff", {
          params: { path: { schoolId: school.id } },
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            givenName: givenName.trim(),
            familyName: familyName.trim() || undefined,
            staffType: staffType as StaffView["staffType"],
            ...(phone.trim() ? { phone: phone.trim() } : {}),
            ...(email.trim() ? { email: email.trim() } : {}),
            jobTitle: jobTitle.trim() || undefined,
            campusId: campusId || undefined,
            ...(staffType === "ADMINISTRATOR" && adminRole
              ? { adminRole: adminRole as (typeof ADMIN_ROLES)[number] }
              : {}),
          },
        }),
      ),
    invalidate: [sk.staff(school.id), sk.summary(school.id)],
    successMessage: t("added"),
    errorFallback: t("failed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const server = fieldErrors(save.error);
  const duplicate = hasProblemCode(save.error, "staff.already_member");
  const message = (key: string, local?: FieldError) => (local ? tv(local) : (server[key] ?? null));

  function submit() {
    const c = contact(phone, email);
    const next = { givenName: required(givenName), phone: c.phone, email: c.email };
    setErrors(next);
    if (Object.values(next).some(Boolean)) return;
    save.mutate();
  }

  return (
    <FormDialog
      title={t("title")}
      description={t("description")}
      submitLabel={t("submit")}
      pending={save.isPending}
      error={
        save.isError && Object.keys(server).length === 0
          ? duplicate
            ? t("alreadyMember")
            : errorMessage(save.error, t("failed"))
          : null
      }
      onSubmit={submit}
      onClose={onClose}
    >
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("givenName")} error={message("givenName", errors.givenName)}>
          {(props) => <Input {...props} value={givenName} maxLength={100} onChange={(e) => setGivenName(e.target.value)} />}
        </FormField>
        <FormField label={t("familyName")} error={message("familyName")}>
          {(props) => <Input {...props} value={familyName} maxLength={100} onChange={(e) => setFamilyName(e.target.value)} />}
        </FormField>
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        <FormField label={t("phone")} hint={t("phoneHint")} error={message("phone", errors.phone)}>
          {(props) => <Input {...props} type="tel" inputMode="tel" autoComplete="off" value={phone} onChange={(e) => setPhone(e.target.value)} />}
        </FormField>
        <FormField label={t("email")} error={message("email", errors.email)}>
          {(props) => <Input {...props} type="email" autoComplete="off" value={email} onChange={(e) => setEmail(e.target.value)} />}
        </FormField>
      </div>
      <p className="rounded-xl bg-muted/60 p-3 text-sm text-muted-foreground">{t("smsNote")}</p>
      <SelectField
        label={t("staffType")}
        value={staffType}
        onChange={setStaffType}
        options={TYPES.map((v) => ({ value: v, label: tType(v) }))}
      />
      {staffType === "ADMINISTRATOR" ? (
        <SelectField
          label={t("adminRole")}
          hint={t("adminRoleHint")}
          value={adminRole}
          onChange={setAdminRole}
          noneLabel={t("noAdminRole")}
          options={ADMIN_ROLES.map((v) => ({ value: v, label: tRole(v) }))}
          error={server.adminRole ?? null}
        />
      ) : null}
      <FormField label={t("jobTitle")} error={message("jobTitle")}>
        {(props) => <Input {...props} value={jobTitle} maxLength={100} onChange={(e) => setJobTitle(e.target.value)} />}
      </FormField>
      {campuses.data && campuses.data.items.length > 0 ? (
        <SelectField
          label={t("campus")}
          value={campusId}
          onChange={setCampusId}
          noneLabel={t("noCampus")}
          options={campuses.data.items.map((c) => ({ value: c.id, label: c.name }))}
        />
      ) : null}
    </FormDialog>
  );
}
