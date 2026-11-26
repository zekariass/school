"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeftIcon, CheckIcon, XIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { EmptyNotice, ErrorNotice, LoadMore, LoadingRows } from "@/components/console/states";
import { FormDialog } from "@/components/school/form-dialog";
import { PageHeader } from "@/components/school/page-header";
import { useSchool } from "@/components/school/school-context";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Textarea } from "@/components/ui/textarea";
import { Link } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { MAX_TAGS } from "@/lib/feed/composer";
import { parseGateError, type GateError } from "@/lib/feed/consent-error";
import { approveContribution, fetchPending, fk, rejectContribution, type Post } from "@/lib/feed/queries";
import { isWithheldWarning } from "@/lib/feed/view";
import { canOpenApprovals } from "@/lib/school/access";
import { fetchRoster, sk } from "@/lib/school/queries";
import { AuthorAvatar, AuthorName } from "./author";
import { GateNotice } from "./gate-notice";
import { MediaGrid } from "./media-grid";
import { RelativeTime } from "./relative-time";
import { StudentTags } from "./student-tags";
import { useFeedPages, useRefreshFeed } from "./use-feed";

/**
 * The approval queue of family and student contributions: the class's lead teachers and managers see what is waiting,
 * with the photos and the consent status, and approve (adjusting the tags if needed) or reject with a reason the
 * contributor sees. 403, 404, 409 and 422 answers appear inline with the API's own words.
 */
export function ApprovalsView() {
  const t = useTranslations("school.feed.approvals");
  const { school, permissions, isTeacher, isStaff } = useSchool();
  const allowed = canOpenApprovals({ permissions, isTeacher, isStaff, isStudent: false });
  const { query, posts } = useFeedPages(fk.pending(school.id), (cursor) => fetchPending(school.id, undefined, cursor), allowed);

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
        <Link
          href={`/s/${encodeURIComponent(school.slug)}/feed`}
          className="inline-flex min-h-11 items-center gap-2 self-start text-sm font-semibold text-primary hover:underline"
        >
          <ArrowLeftIcon className="size-4 rtl:rotate-180" aria-hidden />
          {t("back")}
        </Link>
        <PageHeader title={t("title")} description={t("subtitle")} />
        {!allowed ? (
          <EmptyNotice>{t("notForYou")}</EmptyNotice>
        ) : query.isPending ? (
          <LoadingRows rows={3} />
        ) : query.isError ? (
          <ErrorNotice message={errorMessage(query.error, t("loadFailed"))} onRetry={() => void query.refetch()} />
        ) : posts.length === 0 ? (
          <EmptyNotice>{t("empty")}</EmptyNotice>
        ) : (
          <ul className="flex flex-col gap-4" data-testid="pending-list" aria-live="polite">
            {posts.map((post) => (
              <li key={post.id}>
                <PendingCard post={post} />
              </li>
            ))}
          </ul>
        )}
        <LoadMore hasMore={!!query.hasNextPage} loading={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()} />
    </div>
  );
}

function PendingCard({ post }: { post: Post }) {
  const t = useTranslations("school.feed.approvals");
  const [dialog, setDialog] = useState<"approve" | "reject" | null>(null);
  const noTags = post.media.length > 0 && post.tags.length === 0;
  return (
    <Card data-testid="pending-card">
      <CardContent className="flex flex-col gap-3">
        <header className="flex items-center gap-3">
          <AuthorAvatar author={post.author} />
          <div className="min-w-0 flex-1">
            <AuthorName author={post.author} className="block font-semibold" />
            <RelativeTime instant={post.createdAt} className="text-sm text-muted-foreground" />
          </div>
        </header>
        {post.body ? <p className="break-words whitespace-pre-wrap">{post.body}</p> : null}
        <MediaGrid items={post.media} />
        {post.tags.length > 0 ? (
          <p className="text-sm text-muted-foreground">
            <span className="font-medium">{t("tagged")}</span> {post.tags.map((tag) => tag.displayName).join(", ")}
          </p>
        ) : null}
        <ConsentStatus post={post} noTags={noTags} />
        <div className="flex flex-wrap gap-2">
          <Button type="button" onClick={() => setDialog("approve")} data-testid="approve">
            <CheckIcon aria-hidden />
            {t("approve")}
          </Button>
          <Button type="button" variant="outline" onClick={() => setDialog("reject")} data-testid="reject">
            <XIcon aria-hidden />
            {t("reject")}
          </Button>
        </div>
      </CardContent>
      {dialog === "approve" ? <ApproveDialog post={post} onClose={() => setDialog(null)} /> : null}
      {dialog === "reject" ? <RejectDialog post={post} onClose={() => setDialog(null)} /> : null}
    </Card>
  );
}

function ConsentStatus({ post, noTags }: { post: Post; noTags: boolean }) {
  const t = useTranslations("school.feed.approvals");
  if (post.media.length === 0) return <p className="text-sm text-muted-foreground">{t("textOnly")}</p>;
  if (isWithheldWarning(post)) {
    return (
      <p role="note" className="rounded-xl bg-warning/10 p-3 text-sm font-medium" data-testid="consent-status">
        {t("consentMissing")}
      </p>
    );
  }
  if (noTags) {
    return (
      <p role="note" className="rounded-xl bg-muted/60 p-3 text-sm" data-testid="consent-status">
        {t("noTags")}
      </p>
    );
  }
  return (
    <p role="note" className="rounded-xl bg-success/10 p-3 text-sm" data-testid="consent-status">
      {t("consentOk")}
    </p>
  );
}

function ApproveDialog({ post, onClose }: { post: Post; onClose: () => void }) {
  const t = useTranslations("school.feed.approvals");
  const tp = useTranslations("school.feed.problems");
  const { school } = useSchool();
  const refresh = useRefreshFeed();
  const original = post.tags.map((tag) => tag.studentId);
  const [studentIds, setStudentIds] = useState<string[]>(original);
  const [confirm, setConfirm] = useState(false);
  const [gate, setGate] = useState<GateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const roster = useQuery({
    queryKey: sk.classRoster(school.id, post.classId ?? "none"),
    queryFn: () => fetchRoster(school.id, post.classId!),
    enabled: !!post.classId,
    staleTime: 60_000,
    retry: false,
  });
  const changed = studentIds.length !== original.length || studentIds.some((id) => !original.includes(id));
  const needsConfirm = post.media.length > 0 && studentIds.length === 0;

  const approve = useMutation({
    mutationFn: () =>
      approveContribution(post.schoolId, post.id, {
        confirmNoUnconsentedStudents: needsConfirm && confirm ? true : undefined,
        studentIds: changed ? studentIds : undefined,
      }),
    onSuccess: async () => {
      await refresh();
      toast.success(t("approved"));
      onClose();
    },
    onError: async (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("actionFailed")));
      // 404 and 409 mean somebody else already decided: show the answer, then refresh the queue.
      if (!refused) await refresh();
    },
  });

  return (
    <FormDialog
      title={t("approveTitle")}
      description={t("approveBody")}
      submitLabel={t("approve")}
      pending={approve.isPending}
      error={error}
      onSubmit={() => {
        setGate(null);
        setError(null);
        if (needsConfirm && !confirm) {
          setError(tp("confirmRequired"));
          return;
        }
        approve.mutate();
      }}
      onClose={onClose}
    >
      {roster.data && roster.data.length > 0 ? (
        <StudentTags
          students={roster.data}
          value={studentIds}
          onChange={(ids) => {
            setStudentIds(ids);
            setConfirm(false);
          }}
          max={MAX_TAGS}
          label={t("tags")}
          hint={t("tagsHelp")}
        />
      ) : null}
      {needsConfirm ? (
        <label className="flex cursor-pointer items-start gap-3 rounded-xl border bg-muted/40 p-3 text-sm" htmlFor="approve-confirm">
          <input
            id="approve-confirm"
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={confirm}
            onChange={(e) => setConfirm(e.target.checked)}
            data-testid="approve-confirm"
          />
          <span className="font-medium">{t("confirmLabel")}</span>
        </label>
      ) : null}
      {gate ? (
        <GateNotice
          error={gate}
          disabled={approve.isPending}
          confirmed={confirm}
          onConfirmChange={setConfirm}
          onUntag={(ids) => {
            setStudentIds((current) => current.filter((id) => !ids.includes(id)));
            setConfirm(false);
            setGate(null);
          }}
        />
      ) : null}
    </FormDialog>
  );
}

function RejectDialog({ post, onClose }: { post: Post; onClose: () => void }) {
  const t = useTranslations("school.feed.approvals");
  const tv = useTranslations("school.feed.problems");
  const refresh = useRefreshFeed();
  const [reason, setReason] = useState("");
  const [error, setError] = useState<string | null>(null);
  const reject = useMutation({
    mutationFn: () => rejectContribution(post.schoolId, post.id, reason.trim()),
    onSuccess: async () => {
      await refresh();
      toast.success(t("rejected"));
      onClose();
    },
    onError: async (caught) => {
      setError(errorMessage(caught, t("actionFailed")));
      await refresh();
    },
  });
  return (
    <FormDialog
      title={t("rejectTitle")}
      description={t("rejectBody")}
      submitLabel={t("reject")}
      pending={reject.isPending}
      error={error}
      onSubmit={() => {
        if (!reason.trim()) {
          setError(tv("reasonRequired"));
          return;
        }
        setError(null);
        reject.mutate();
      }}
      onClose={onClose}
    >
      <div className="flex flex-col gap-2">
        <label htmlFor="reject-reason" className="text-sm font-medium">
          {t("reason")}
        </label>
        <Textarea id="reject-reason" rows={3} maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} data-testid="reject-reason" />
        <p className="text-xs text-muted-foreground">{t("reasonHelp")}</p>
      </div>
    </FormDialog>
  );
}
