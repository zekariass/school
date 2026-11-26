"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { ErrorNotice } from "@/components/console/states";
import { ImageUploader } from "@/components/media/image-uploader";
import { MediaImage } from "@/components/media/media-image";
import { FormDialog } from "@/components/school/form-dialog";
import { StudentPicker } from "@/components/school/student-picker";
import { Textarea } from "@/components/ui/textarea";
import { newIdempotencyKey } from "@/lib/console/idempotency";
import { errorMessage } from "@/lib/console/errors";
import { MAX_STORY_CAPTION, MAX_TAGS, buildStoryBody, submissionFingerprint } from "@/lib/feed/composer";
import { parseGateError, type GateError } from "@/lib/feed/consent-error";
import { createClassStory, createSchoolStory } from "@/lib/feed/queries";
import { fetchRoster, sk } from "@/lib/school/queries";
import { GateNotice } from "./gate-notice";
import { StudentTags } from "./student-tags";
import { useRefreshFeed } from "./use-feed";

/** A story lasts 24 hours: a photo and/or a short caption, with the same consent gate as posts. */
export function StoryComposer({ schoolId, classId, onClose }: { schoolId: string; classId?: string; onClose: () => void }) {
  const t = useTranslations("school.feed.storyComposer");
  const tp = useTranslations("school.feed.problems");
  const refresh = useRefreshFeed();
  const [caption, setCaption] = useState("");
  const [mediaId, setMediaId] = useState<string | null>(null);
  const [studentIds, setStudentIds] = useState<string[]>([]);
  const [confirm, setConfirm] = useState(false);
  const [gate, setGate] = useState<GateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const key = useRef<{ fingerprint: string; key: string } | null>(null);

  const roster = useQuery({
    queryKey: sk.classRoster(schoolId, classId ?? "none"),
    queryFn: () => fetchRoster(schoolId, classId!),
    enabled: !!classId,
    staleTime: 60_000,
  });

  const create = useMutation({
    mutationFn: async () => {
      const body = buildStoryBody({ caption, mediaId, studentIds, confirmNoUnconsented: confirm });
      if (!body) throw new Error("empty");
      const fingerprint = submissionFingerprint(body);
      if (key.current?.fingerprint !== fingerprint) key.current = { fingerprint, key: newIdempotencyKey() };
      return classId ? createClassStory(schoolId, classId, body, key.current.key) : createSchoolStory(schoolId, body, key.current.key);
    },
    onSuccess: async () => {
      await refresh();
      toast.success(t("shared"));
      onClose();
    },
    onError: (caught) => {
      if (caught instanceof Error && caught.message === "empty") {
        setError(tp("storyEmpty"));
        return;
      }
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  const needsConfirm = !!mediaId && studentIds.length === 0;

  return (
    <FormDialog
      title={t("title")}
      description={t("help")}
      submitLabel={t("share")}
      pending={create.isPending}
      error={error}
      onSubmit={() => {
        setGate(null);
        setError(null);
        if (needsConfirm && !confirm) {
          setError(tp("confirmRequired"));
          return;
        }
        create.mutate();
      }}
      onClose={onClose}
    >
      <div className="flex flex-col gap-2">
        <label htmlFor="story-caption" className="text-sm font-medium">
          {t("caption")}
        </label>
        <Textarea id="story-caption" rows={3} maxLength={MAX_STORY_CAPTION} value={caption} onChange={(e) => setCaption(e.target.value)} data-testid="story-caption-input" />
        <p className="text-xs text-muted-foreground">{t("captionCount", { count: caption.length, max: MAX_STORY_CAPTION })}</p>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-sm font-medium">{t("photo")}</span>
        {mediaId ? <MediaImage mediaId={mediaId} variant="SMALL" alt={t("photoPreview")} width={240} height={180} className="max-h-48 w-auto rounded-xl object-cover" /> : null}
        <ImageUploader
          purpose="STORY"
          context={{ type: classId ? "CLASS" : "SCHOOL", id: classId ?? schoolId }}
          hasImage={!!mediaId}
          labels={{ choose: t("addPhoto"), change: t("changePhoto"), remove: t("removePhoto") }}
          onUploaded={async (id) => {
            setMediaId(id);
            setConfirm(false);
            setGate(null);
          }}
          onRemove={async () => {
            setMediaId(null);
            setConfirm(false);
          }}
        />
      </div>

      {classId ? (
        roster.isError ? (
          <ErrorNotice message={errorMessage(roster.error, t("rosterFailed"))} onRetry={() => void roster.refetch()} />
        ) : (
          <StudentTags
            students={roster.data ?? []}
            value={studentIds}
            onChange={(ids) => {
              setStudentIds(ids);
              setConfirm(false);
            }}
            max={MAX_TAGS}
            label={t("tags")}
            hint={t("tagsHelp")}
          />
        )
      ) : (
        <StudentPicker
          value={studentIds}
          onChange={(ids) => {
            setStudentIds(ids);
            setConfirm(false);
          }}
          label={t("tags")}
        />
      )}

      {needsConfirm ? (
        <label className="flex cursor-pointer items-start gap-3 rounded-xl border bg-muted/40 p-3 text-sm" htmlFor="story-confirm">
          <input
            id="story-confirm"
            type="checkbox"
            className="mt-0.5 size-5 shrink-0 accent-primary"
            checked={confirm}
            onChange={(e) => setConfirm(e.target.checked)}
            data-testid="story-confirm"
          />
          <span className="font-medium">{t("confirmLabel")}</span>
        </label>
      ) : null}

      {gate ? (
        <GateNotice
          error={gate}
          disabled={create.isPending}
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
