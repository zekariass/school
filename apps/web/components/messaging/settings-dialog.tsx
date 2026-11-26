"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { SelectField } from "@/components/school/fields";
import { FormDialog } from "@/components/school/form-dialog";
import { Input } from "@/components/ui/input";
import { errorMessage } from "@/lib/console/errors";
import { mk } from "@/lib/messaging/keys";
import { updateConversation } from "@/lib/messaging/queries";
import { CONVERSATION_STATUSES, POSTING_POLICIES, type ConversationSummary } from "@/lib/messaging/types";

/** A group's title, who may post, and lock or archive: for owners and moderators (the API checks; direct conversations have none). */
export function SettingsDialog({ conversation, onClose }: { conversation: Pick<ConversationSummary, "id" | "title" | "postingPolicy" | "status">; onClose: () => void }) {
  const t = useTranslations("school.messaging.settings");
  const queryClient = useQueryClient();
  const [title, setTitle] = useState(conversation.title ?? "");
  const [postingPolicy, setPostingPolicy] = useState(conversation.postingPolicy);
  const [status, setStatus] = useState(conversation.status);

  const save = useMutation({
    mutationFn: () => updateConversation(conversation.id, { title: title.trim() || undefined, postingPolicy, status }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: mk.all });
      toast.success(t("saved"));
      onClose();
    },
  });

  return (
    <FormDialog title={t("title")} description={t("description")} pending={save.isPending} error={save.error ? errorMessage(save.error, t("failed")) : null} onClose={onClose} onSubmit={() => save.mutate()}>
      <FormField label={t("name")}>{(props) => <Input {...props} value={title} maxLength={120} onChange={(e) => setTitle(e.target.value)} />}</FormField>
      <SelectField label={t("posting")} value={postingPolicy} onChange={setPostingPolicy} options={POSTING_POLICIES.map((value) => ({ value, label: t(`postingPolicy.${value}`) }))} />
      <SelectField label={t("status")} value={status} onChange={setStatus} options={CONVERSATION_STATUSES.map((value) => ({ value, label: t(`statuses.${value}`) }))} hint={t("statusHint")} />
    </FormDialog>
  );
}
