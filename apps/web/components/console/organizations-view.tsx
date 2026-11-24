"use client";

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { PencilIcon, PlusIcon } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { useState } from "react";
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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { errorMessage, fieldErrors, hasProblemCode } from "@/lib/console/errors";
import { P } from "@/lib/console/permissions";
import {
  fetchCountries,
  fetchOrganizations,
  queryKeys,
  type Organization,
} from "@/lib/console/queries";
import { isValidSlug, slugify, SLUG_MAX, SLUG_MIN } from "@/lib/console/slug";
import { useCan } from "./console-context";
import { FormField } from "./form-field";
import { EmptyNotice, ErrorNotice, LoadingRows, LoadMore } from "./states";
import { StatusBadge } from "./status-badge";
import { useSubmit } from "./use-submit";

export function OrganizationsView() {
  const t = useTranslations("console");
  const canManage = useCan(P.organizationsManage);
  const [editing, setEditing] = useState<Organization | "new" | null>(null);

  const organizations = useInfiniteQuery({
    queryKey: [...queryKeys.organizations, "paged"],
    queryFn: ({ pageParam }) => fetchOrganizations(pageParam, 25),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
  });
  const rows = organizations.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">{t("organizations.title")}</h1>
          <p className="text-muted-foreground">{t("organizations.subtitle")}</p>
        </div>
        {canManage ? (
          <Button size="lg" onClick={() => setEditing("new")}>
            <PlusIcon className="size-4" aria-hidden />
            {t("organizations.create")}
          </Button>
        ) : null}
      </div>

      {organizations.isPending ? (
        <LoadingRows />
      ) : organizations.isError ? (
        <ErrorNotice
          message={errorMessage(organizations.error, t("loadFailed"))}
          onRetry={() => void organizations.refetch()}
        />
      ) : rows.length === 0 ? (
        <EmptyNotice>{t("organizations.empty")}</EmptyNotice>
      ) : (
        <>
          <div className="rounded-2xl border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("fields.name")}</TableHead>
                  <TableHead>{t("fields.slug")}</TableHead>
                  <TableHead>{t("fields.country")}</TableHead>
                  <TableHead>{t("fields.status")}</TableHead>
                  <TableHead className="w-12" />
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((org) => (
                  <TableRow key={org.id}>
                    <TableCell>
                      <div className="font-semibold">{org.name}</div>
                      {org.legalName ? <div className="text-sm text-muted-foreground">{org.legalName}</div> : null}
                    </TableCell>
                    <TableCell className="font-mono text-sm">{org.slug}</TableCell>
                    <TableCell>{org.countryCode}</TableCell>
                    <TableCell>
                      <StatusBadge status={org.status} />
                    </TableCell>
                    <TableCell>
                      {canManage ? (
                        <Button
                          size="icon"
                          variant="ghost"
                          aria-label={t("organizations.edit", { name: org.name })}
                          onClick={() => setEditing(org)}
                        >
                          <PencilIcon className="size-4" aria-hidden />
                        </Button>
                      ) : null}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <LoadMore
            hasMore={!!organizations.hasNextPage}
            loading={organizations.isFetchingNextPage}
            onClick={() => void organizations.fetchNextPage()}
          />
        </>
      )}

      {editing !== null ? (
        <OrganizationDialog organization={editing === "new" ? null : editing} onClose={() => setEditing(null)} />
      ) : null}
    </div>
  );
}

function OrganizationDialog({ organization, onClose }: { organization: Organization | null; onClose: () => void }) {
  const t = useTranslations("console");
  const locale = useLocale();
  const countries = useQuery({ queryKey: queryKeys.countries, queryFn: fetchCountries, staleTime: 600_000 });

  const [name, setName] = useState(organization?.name ?? "");
  const [legalName, setLegalName] = useState(organization?.legalName ?? "");
  const [slug, setSlug] = useState(organization?.slug ?? "");
  const [slugTouched, setSlugTouched] = useState(false);
  const [country, setCountry] = useState<string | null>(null);
  const [status, setStatus] = useState<Organization["status"]>(organization?.status ?? "ACTIVE");
  const [errors, setErrors] = useState<Record<string, string>>({});

  const list = countries.data ?? [];
  const countryCode = organization?.countryCode ?? country ?? list[0]?.code ?? "";

  const save = useSubmit({
    mutationFn: async (_: void, idempotencyKey) => {
      const api = getBrowserApi();
      if (organization) {
        return unwrap(
          await api.PUT("/api/v1/platform/organizations/{id}", {
            params: { path: { id: organization.id } },
            body: { name: name.trim(), ...(legalName.trim() ? { legalName: legalName.trim() } : {}), status },
          }),
        );
      }
      return unwrap(
        await api.POST("/api/v1/platform/organizations", {
          headers: { "Idempotency-Key": idempotencyKey },
          body: {
            name: name.trim(),
            slug,
            countryCode,
            ...(legalName.trim() ? { legalName: legalName.trim() } : {}),
          },
        }),
      );
    },
    invalidate: [queryKeys.organizations],
    successMessage: t("organizations.saved"),
    errorFallback: t("saveFailed"),
    toastOnError: false,
    onSuccess: onClose,
  });
  const serverErrors = fieldErrors(save.error);
  const slugTaken = hasProblemCode(save.error, "organization.slug_taken");

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            const next: Record<string, string> = {};
            if (!name.trim()) next.name = t("validation.required");
            if (!organization && !isValidSlug(slug)) next.slug = t("schools.slugRule", { min: SLUG_MIN, max: SLUG_MAX });
            if (!countryCode) next.country = t("validation.required");
            setErrors(next);
            if (Object.keys(next).length === 0) save.mutate();
          }}
        >
          <DialogHeader>
            <DialogTitle>{organization ? t("organizations.editTitle") : t("organizations.create")}</DialogTitle>
            <DialogDescription>{t("organizations.dialogHint")}</DialogDescription>
          </DialogHeader>
          <FormField label={t("fields.name")} error={errors.name ?? serverErrors.name}>
            {(props) => (
              <Input
                {...props}
                value={name}
                maxLength={200}
                onChange={(e) => {
                  setName(e.target.value);
                  if (!organization && !slugTouched) setSlug(slugify(e.target.value));
                }}
              />
            )}
          </FormField>
          <FormField label={t("fields.legalName")} error={serverErrors.legalName}>
            {(props) => <Input {...props} value={legalName} maxLength={200} onChange={(e) => setLegalName(e.target.value)} />}
          </FormField>
          {organization ? (
            <FormField label={t("fields.status")}>
              {(props) => (
                <Select value={status} onValueChange={(v) => setStatus(v as Organization["status"])}>
                  <SelectTrigger {...props} className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(["ACTIVE", "SUSPENDED", "ARCHIVED"] as const).map((s) => (
                      <SelectItem key={s} value={s}>
                        {t(`status.${s}`)}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              )}
            </FormField>
          ) : (
            <>
              <FormField
                label={t("fields.slug")}
                hint={t("schools.slugRule", { min: SLUG_MIN, max: SLUG_MAX })}
                error={errors.slug ?? serverErrors.slug ?? (slugTaken ? t("organizations.slugTaken") : undefined)}
              >
                {(props) => (
                  <Input
                    {...props}
                    value={slug}
                    maxLength={SLUG_MAX}
                    spellCheck={false}
                    className="font-mono"
                    onChange={(e) => {
                      setSlugTouched(true);
                      setSlug(e.target.value.toLowerCase());
                    }}
                  />
                )}
              </FormField>
              <FormField label={t("fields.country")} error={errors.country ?? serverErrors.countryCode}>
                {(props) => (
                  <Select value={countryCode} onValueChange={setCountry}>
                    <SelectTrigger {...props} className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {list.map((c) => (
                        <SelectItem key={c.code} value={c.code}>
                          {c.nameI18n[locale] ?? c.nameI18n.en ?? c.code}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                )}
              </FormField>
            </>
          )}
          {save.isError && Object.keys(serverErrors).length === 0 && !slugTaken ? (
            <p role="alert" className="text-sm font-medium text-destructive">
              {errorMessage(save.error, t("saveFailed"))}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("cancel")}
            </Button>
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? t("saving") : t("save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
