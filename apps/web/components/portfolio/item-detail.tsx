"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, Loader2Icon, StarIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { useFormatDate, useFormatInstant } from "@/components/calendar-preference";
import { EmptyNotice, LoadingRows } from "@/components/console/states";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { StudentAvatar } from "@/components/school/student-avatar";
import { Button } from "@/components/ui/button";
import { useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { itemActions } from "@/lib/portfolio/actions";
import {
  archiveItem,
  deleteItem,
  fetchItem,
  PORTFOLIO_GC_MS,
  pfk,
  setFeatured,
  submitItem,
  unarchiveItem,
  type PortfolioItem,
} from "@/lib/portfolio/queries";
import { isCapped, mayReadHistory } from "@/lib/portfolio/view";
import { fetchSubjects, sk } from "@/lib/school/queries";
import { StatusBadge, VisibilityBadge } from "./badges";
import { CommentsPanel } from "./comments-panel";
import { HistoryPanel } from "./history-panel";
import { ItemFormDialog } from "./item-form";
import { ItemMedia } from "./media-view";
import { ReviewDialog } from "./review-dialog";
import { GateNotice } from "./gate-notice";
import { parseGateError, type PortfolioGateError } from "@/lib/portfolio/consent-error";

/**
 * One item with everything the API allows the caller to do with it. Buttons come from the item's `capabilities`
 * (`lib/portfolio/actions.ts`); texts, notes and comments are shown as plain text and kept in memory only. `selfStudentId`
 * is set when the viewer is the student (the edit form excludes them from the tag list).
 */
export function ItemDetail({
  schoolId,
  itemId,
  selfStudentId,
  fallbackHref = "/",
}: {
  schoolId: string;
  itemId: string;
  selfStudentId?: string;
  /** Where "Back" goes when the page was opened directly (a notification, a bookmark). */
  fallbackHref?: string;
}) {
  const t = useTranslations("school.portfolio.item");
  const router = useRouter();
  const item = useQuery({
    queryKey: pfk.item(schoolId, itemId),
    queryFn: () => fetchItem(schoolId, itemId),
    staleTime: 10_000,
    gcTime: PORTFOLIO_GC_MS,
    retry: false,
  });

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6" data-testid="portfolio-item">
      <Button type="button" variant="ghost" className="self-start" onClick={() => (window.history.length > 1 ? router.back() : router.push(fallbackHref))} data-testid="portfolio-back">
        <ArrowLeftIcon className="rtl:rotate-180" aria-hidden />
        {t("back")}
      </Button>
      {item.isPending ? (
        <LoadingRows rows={4} />
      ) : item.isError ? (
        <EmptyNotice>{errorMessage(item.error, t("gone"))}</EmptyNotice>
      ) : (
        <ItemBody item={item.data} selfStudentId={selfStudentId} />
      )}
    </div>
  );
}

function ItemBody({ item, selfStudentId }: { item: PortfolioItem; selfStudentId?: string }) {
  const t = useTranslations("school.portfolio.item");
  const tt = useTranslations("school.portfolio.types");
  const tv = useTranslations("school.portfolio.visibility");
  const formatDate = useFormatDate();
  const formatInstant = useFormatInstant();
  const schoolId = item.schoolId;
  const actions = itemActions(item);
  const [editing, setEditing] = useState(false);
  const [reviewing, setReviewing] = useState(false);
  const [confirm, setConfirm] = useState<"delete" | "archive" | null>(null);
  // Subjects are listed to the school's members; the student and the reviewers are the ones this page can name them for.
  const subjects = useQuery({ queryKey: sk.subjects(schoolId), queryFn: () => fetchSubjects(schoolId), staleTime: 5 * 60_000, enabled: !!item.subjectId && mayReadHistory(item) });
  const subject = item.subjectId ? subjects.data?.find((s) => s.id === item.subjectId)?.name : undefined;
  const name = item.student.displayName;
  const readsComments = !!item.capabilities.canComment || (item.commentCount ?? 0) > 0;

  return (
    <>
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <StatusBadge status={item.status} />
          <VisibilityBadge visibility={item.effectiveVisibility} />
          {item.featured ? (
            <span className="inline-flex items-center gap-1 text-sm font-medium">
              <StarIcon className="size-4" aria-hidden />
              {t("featured")}
            </span>
          ) : null}
        </div>
        <h1 className="text-3xl font-bold break-words" data-testid="portfolio-item-title">
          {item.title?.trim() || t("untitled")}
        </h1>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
          {name ? (
            <span className="flex items-center gap-2">
              <StudentAvatar id={item.student.studentId} name={name} mediaId={item.student.avatar?.id} config={item.student.avatarConfig} size={24} />
              {name}
            </span>
          ) : null}
          <span>{tt(item.itemType)}</span>
          {subject ? <span>{subject}</span> : null}
          {item.workDate ? <span>{formatDate(item.workDate)}</span> : null}
          {item.createdByRole === "TEACHER" ? <span>{t("byTeacher")}</span> : null}
        </div>
        {isCapped(item) ? (
          <p className="text-sm text-muted-foreground" data-testid="portfolio-capped">
            {t("capped", { chosen: tv(item.visibility), shown: tv(item.effectiveVisibility) })}
          </p>
        ) : null}
      </header>

      {item.reviewNote && (item.status === "CHANGES_REQUESTED" || item.status === "REJECTED" || item.status === "APPROVED") ? (
        <section
          aria-labelledby="portfolio-note-title"
          className="flex flex-col gap-1 rounded-xl border border-warning/50 bg-warning/10 p-4 text-sm"
          data-testid="portfolio-review-note-shown"
        >
          <h2 id="portfolio-note-title" className="font-bold">
            {t(`note.${item.status}`)}
          </h2>
          <p className="break-words whitespace-pre-wrap">{item.reviewNote}</p>
          {item.reviewedAt ? <p className="text-xs text-muted-foreground">{formatInstant(item.reviewedAt)}</p> : null}
        </section>
      ) : null}

      <ActionBar
        item={item}
        onEdit={() => setEditing(true)}
        onReview={() => setReviewing(true)}
        onDelete={() => setConfirm("delete")}
        onArchive={() => setConfirm("archive")}
      />

      {item.textContent ? (
        <p className="rounded-xl bg-muted/40 p-4 text-base leading-relaxed break-words whitespace-pre-wrap" data-testid="portfolio-item-text">
          {item.textContent}
        </p>
      ) : null}
      <ItemMedia item={item} />
      {item.description ? (
        <section className="flex flex-col gap-1">
          <h2 className="text-lg font-bold">{t("description")}</h2>
          <p className="break-words whitespace-pre-wrap">{item.description}</p>
        </section>
      ) : null}
      {item.reflection ? (
        <section className="flex flex-col gap-1">
          <h2 className="text-lg font-bold">{t("reflection")}</h2>
          <p className="break-words whitespace-pre-wrap" data-testid="portfolio-item-reflection">
            {item.reflection}
          </p>
        </section>
      ) : null}
      {item.taggedStudents && item.taggedStudents.length > 0 ? (
        <section className="flex flex-col gap-1">
          <h2 className="text-lg font-bold">{t("tagged")}</h2>
          <ul className="flex flex-wrap gap-1.5">
            {item.taggedStudents.map((tag) => (
              <li key={tag.studentId} className="rounded-full bg-muted px-3 py-1 text-sm font-medium">
                {tag.displayName ?? t("taggedUnnamed")}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {readsComments ? <CommentsPanel schoolId={schoolId} itemId={item.id} canComment={actions.comment} /> : null}
      {mayReadHistory(item) ? <HistoryPanel schoolId={schoolId} itemId={item.id} /> : null}

      {editing ? <ItemFormDialog schoolId={schoolId} mode={{ kind: "edit", item, selfStudentId: selfStudentId ?? item.student.studentId }} onClose={() => setEditing(false)} /> : null}
      {reviewing ? <ReviewDialog schoolId={schoolId} item={item} onClose={() => setReviewing(false)} /> : null}
      {confirm ? <ConfirmItemDialog item={item} kind={confirm} onClose={() => setConfirm(null)} /> : null}
    </>
  );
}

function ActionBar({
  item,
  onEdit,
  onReview,
  onDelete,
  onArchive,
}: {
  item: PortfolioItem;
  onEdit: () => void;
  onReview: () => void;
  onDelete: () => void;
  onArchive: () => void;
}) {
  const t = useTranslations("school.portfolio.actions");
  const queryClient = useQueryClient();
  const actions = itemActions(item);
  const schoolId = item.schoolId;
  const [gate, setGate] = useState<PortfolioGateError | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  const refresh = () => queryClient.invalidateQueries({ queryKey: pfk.all });
  const run = useMutation({
    mutationFn: async (action: "submit" | "feature" | "unfeature" | "unarchive") => {
      switch (action) {
        case "submit":
          return submitItem(schoolId, item.id);
        case "feature":
          return setFeatured(schoolId, item.id, true);
        case "unfeature":
          return setFeatured(schoolId, item.id, false);
        case "unarchive":
          return unarchiveItem(schoolId, item.id);
      }
    },
    onSuccess: async (_saved, action) => {
      await refresh();
      toast.success(t(`done.${action}`));
    },
    onError: (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setFailure(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  const any = actions.edit || actions.submit || actions.delete || actions.review || actions.archive || actions.unarchive || actions.feature || actions.unfeature;
  if (!any) return null;
  const pending = run.isPending;
  const go = (action: "submit" | "feature" | "unfeature" | "unarchive") => {
    setGate(null);
    setFailure(null);
    run.mutate(action);
  };
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap gap-2" role="group" aria-label={t("label")} data-testid="portfolio-actions">
        {actions.review ? (
          <Button type="button" onClick={onReview} data-testid="portfolio-action-review">
            {t("review")}
          </Button>
        ) : null}
        {actions.edit ? (
          <Button type="button" variant="outline" onClick={onEdit} data-testid="portfolio-action-edit">
            {t("edit")}
          </Button>
        ) : null}
        {actions.submit ? (
          <Button type="button" onClick={() => go("submit")} disabled={pending} data-testid="portfolio-action-submit">
            {pending && run.variables === "submit" ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
            {actions.resubmit ? t("resubmit") : t("submit")}
          </Button>
        ) : null}
        {actions.feature ? (
          <Button type="button" variant="outline" onClick={() => go("feature")} disabled={pending} data-testid="portfolio-action-feature">
            {t("feature")}
          </Button>
        ) : null}
        {actions.unfeature ? (
          <Button type="button" variant="outline" onClick={() => go("unfeature")} disabled={pending} data-testid="portfolio-action-unfeature">
            {t("unfeature")}
          </Button>
        ) : null}
        {actions.archive ? (
          <Button type="button" variant="outline" onClick={onArchive} disabled={pending} data-testid="portfolio-action-archive">
            {t("archive")}
          </Button>
        ) : null}
        {actions.unarchive ? (
          <Button type="button" variant="outline" onClick={() => go("unarchive")} disabled={pending} data-testid="portfolio-action-unarchive">
            {t("unarchive")}
          </Button>
        ) : null}
        {actions.delete ? (
          <Button type="button" variant="ghost" onClick={onDelete} disabled={pending} data-testid="portfolio-action-delete">
            {t("delete")}
          </Button>
        ) : null}
      </div>
      {gate ? <GateNotice error={gate} audience="reviewer" /> : null}
      {failure ? (
        <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="portfolio-action-error">
          {failure}
        </p>
      ) : null}
    </div>
  );
}

function ConfirmItemDialog({ item, kind, onClose }: { item: PortfolioItem; kind: "delete" | "archive"; onClose: () => void }) {
  const t = useTranslations("school.portfolio.actions");
  const router = useRouter();
  const queryClient = useQueryClient();
  const run = useMutation({
    mutationFn: async () => {
      if (kind === "delete") await deleteItem(item.schoolId, item.id);
      else await archiveItem(item.schoolId, item.id);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: pfk.all });
      toast.success(t(`done.${kind}`));
      onClose();
      if (kind === "delete") router.back();
    },
  });
  return (
    <ConfirmDialog
      title={t(`confirm.${kind}.title`)}
      description={t(`confirm.${kind}.body`)}
      confirmLabel={t(kind)}
      destructive={kind === "delete"}
      pending={run.isPending}
      error={run.isError ? errorMessage(run.error, t("failed")) : null}
      onConfirm={() => run.mutate()}
      onClose={onClose}
    />
  );
}
