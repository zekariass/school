"use client";

import { useMutation } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { FormDialog } from "@/components/school/form-dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { MAX_BODY, buildContributionBody, submissionFingerprint, type ComposerPhoto } from "@/lib/feed/composer";
import { parseGateError, type GateError } from "@/lib/feed/consent-error";
import { taggableChildren, type ClassTarget } from "@/lib/feed/family";
import { createContribution, type FeedCapabilities } from "@/lib/feed/queries";
import { GateNotice } from "./gate-notice";
import { PhotoField } from "./photo-field";
import { useRefreshFeed } from "./use-feed";
import { cn } from "@/lib/utils";

/**
 * A guardian shares text and photos of their own child with the class. Whether a teacher approves first comes from
 * the capabilities: the dialog says so before and after sending. A contributor never confirms "no other child
 * appears": the approver does; so the photo must tag the guardian's own consented child or wait for approval.
 */
export function ContributionDialog({
  targets,
  capabilities,
  onClose,
}: {
  targets: readonly ClassTarget[];
  /** Capabilities per class id; only classes where the caller may contribute are offered. */
  capabilities: Readonly<Record<string, FeedCapabilities | undefined>>;
  onClose: () => void;
}) {
  const t = useTranslations("family.feed.contribute");
  const tp = useTranslations("school.feed.problems");
  const refresh = useRefreshFeed();
  const offered = targets.filter((target) => capabilities[target.classId]?.canContribute);
  const [classId, setClassId] = useState(offered[0]?.classId ?? "");
  const [body, setBody] = useState("");
  const [photos, setPhotos] = useState<ComposerPhoto[]>([]);
  const [studentIds, setStudentIds] = useState<string[]>([]);
  const [gate, setGate] = useState<GateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<"waiting" | "shared" | null>(null);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);

  const target = offered.find((o) => o.classId === classId);
  const caps = target ? capabilities[target.classId] : undefined;
  const children = target && caps ? taggableChildren(target, caps.contributionStudentIds) : [];

  const send = useMutation({
    mutationFn: async () => {
      if (!target) throw new Error("class");
      const request = buildContributionBody({ body, photos, studentIds });
      const fingerprint = submissionFingerprint({ classId: target.classId, request });
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      return createContribution(target.schoolId, target.classId, request, key.current.key);
    },
    onSuccess: async (post) => {
      await refresh();
      setDone(post.status === "PENDING_APPROVAL" ? "waiting" : "shared");
      toast.success(post.status === "PENDING_APPROVAL" ? t("waitingToast") : t("sharedToast"));
    },
    onError: (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  if (done) {
    return (
      <FormDialog title={done === "waiting" ? t("waitingTitle") : t("sharedTitle")} description={done === "waiting" ? t("waitingBody") : t("sharedBody")} submitLabel={t("close")} onSubmit={onClose} onClose={onClose}>
        <span />
      </FormDialog>
    );
  }

  return (
    <FormDialog
      title={t("title")}
      description={caps?.contributionNeedsApproval ? t("needsApproval") : t("noApproval")}
      submitLabel={t("send")}
      pending={send.isPending}
      error={error}
      onSubmit={() => {
        setGate(null);
        setError(null);
        if (!body.trim() && photos.length === 0) {
          setError(tp("empty"));
          return;
        }
        send.mutate();
      }}
      onClose={onClose}
    >
      {offered.length > 1 ? (
        <div className="flex flex-col gap-2">
          <label htmlFor="contribute-class" className="text-sm font-medium">
            {t("class")}
          </label>
          <select
            id="contribute-class"
            className="h-11 rounded-md border border-input bg-background px-3"
            value={classId}
            onChange={(event) => {
              setClassId(event.target.value);
              setStudentIds([]);
              setPhotos([]);
            }}
          >
            {offered.map((o) => (
              <option key={o.classId} value={o.classId}>
                {o.className} · {o.schoolName}
              </option>
            ))}
          </select>
        </div>
      ) : target ? (
        <p className="text-sm font-medium">
          {target.className} · {target.schoolName}
        </p>
      ) : null}

      <div className="flex flex-col gap-2">
        <label htmlFor="contribute-body" className="text-sm font-medium">
          {t("body")}
        </label>
        <Textarea id="contribute-body" rows={4} maxLength={MAX_BODY} value={body} onChange={(e) => setBody(e.target.value)} data-testid="contribute-body" />
      </div>

      {target ? <PhotoField photos={photos} onChange={setPhotos} context={{ type: "CLASS", id: target.classId }} disabled={send.isPending} /> : null}

      {children.length > 0 ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t("whoIsIn")}</legend>
          <p className="text-sm text-muted-foreground">{t("whoHelp")}</p>
          <ul className="flex flex-wrap gap-2">
            {children.map((child) => {
              const checked = studentIds.includes(child.studentId);
              return (
                <li key={child.studentId}>
                  <label className={cn("flex min-h-10 cursor-pointer items-center gap-2 rounded-full border px-4 text-sm font-medium", checked ? "border-primary bg-primary/10" : "hover:bg-accent")}>
                    <input
                      type="checkbox"
                      className="size-4 accent-primary"
                      checked={checked}
                      onChange={() => setStudentIds(checked ? studentIds.filter((id) => id !== child.studentId) : [...studentIds, child.studentId])}
                      data-testid="contribute-child"
                    />
                    {child.name}
                  </label>
                </li>
              );
            })}
          </ul>
        </fieldset>
      ) : null}

      {gate ? (
        <GateNotice
          error={gate}
          disabled={send.isPending}
          onUntag={(ids) => {
            setStudentIds((current) => current.filter((id) => !ids.includes(id)));
            setGate(null);
          }}
        />
      ) : null}
    </FormDialog>
  );
}

