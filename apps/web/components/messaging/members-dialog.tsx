"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { EyeIcon, UserMinusIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ErrorNotice, LoadingRows } from "@/components/console/states";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { errorMessage } from "@/lib/console/errors";
import { mk } from "@/lib/messaging/keys";
import { addMembers, removeMember } from "@/lib/messaging/queries";
import { personName } from "@/lib/messaging/participants";
import type { ConversationSummary, Participant } from "@/lib/messaging/types";
import { MemberPicker } from "./member-picker";
import { ReasonDialog } from "./message-dialogs";
import { PersonBadge, usePersonText } from "./person-name";
import { useMembers } from "./use-messaging";

/**
 * Who is in a conversation, as the caller may see it: teachers, staff and students by name, guardians only as role labels,
 * observers marked. Moderators can remove a member (a reason is required and audited) and add members to groups that do not
 * follow the class list. The API checks every one of these.
 */
export function MembersDialog({ conversation, canModerate, onClose }: { conversation: ConversationSummary; canModerate: boolean; onClose: () => void }) {
  const t = useTranslations("school.messaging.members");
  const tRole = useTranslations("school.messaging.role");
  const queryClient = useQueryClient();
  const members = useMembers(conversation.id, true);
  const { participant } = usePersonText();
  const [removing, setRemoving] = useState<Participant | null>(null);
  const [adding, setAdding] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const rosterManaged = conversation.groupKind === "CLASS_STUDENTS" || conversation.groupKind === "CLASS_PARENTS";
  const canAdd = canModerate && conversation.type !== "DIRECT" && !rosterManaged && !!conversation.groupKind;

  const remove = useMutation({
    mutationFn: ({ memberId, reason }: { memberId: string; reason: string }) => removeMember(conversation.id, memberId, reason),
    onSuccess: (list) => {
      queryClient.setQueryData(mk.members(conversation.id), list);
      void queryClient.invalidateQueries({ queryKey: mk.conversation(conversation.id) });
      setRemoving(null);
      toast.success(t("removed"));
    },
  });
  const add = useMutation({
    mutationFn: (userIds: string[]) => addMembers(conversation.id, userIds),
    onSuccess: (list) => {
      queryClient.setQueryData(mk.members(conversation.id), list);
      setAdding(false);
      setPicked([]);
      toast.success(t("added"));
    },
  });

  const existing = new Set<string>();
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("title")}</DialogTitle>
          <DialogDescription>{t("description")}</DialogDescription>
        </DialogHeader>
        {members.isPending ? <LoadingRows rows={3} /> : null}
        {members.isError ? <ErrorNotice message={errorMessage(members.error, t("loadFailed"))} onRetry={() => void members.refetch()} /> : null}
        {members.data ? (
          <>
            <ul className="flex flex-col gap-2" data-testid="member-list">
              {members.data.members.map((p) => (
                <li key={p.memberId} className="flex items-center gap-3 rounded-xl border p-2" data-testid="member">
                  <PersonBadge name={personName(p)} />
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-semibold">
                      {participant(p)}
                      {p.self ? <span className="font-normal text-muted-foreground"> ({t("you")})</span> : null}
                    </p>
                    <p className="flex items-center gap-1 text-xs text-muted-foreground">
                      {p.role === "OBSERVER" ? <EyeIcon className="size-3" aria-hidden /> : null}
                      {tRole(p.role)}
                    </p>
                  </div>
                  {canModerate && !p.self && p.role !== "OBSERVER" && conversation.type !== "DIRECT" ? (
                    <Button type="button" variant="ghost" size="sm" data-testid="member-remove" onClick={() => setRemoving(p)} aria-label={t("removeNamed", { name: participant(p) })}>
                      <UserMinusIcon aria-hidden />
                      {t("remove")}
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
            {members.data.observerCount > 0 ? <p className="text-sm text-muted-foreground">{t("observers", { count: members.data.observerCount })}</p> : null}
          </>
        ) : null}

        {canAdd && conversation.groupKind ? (
          adding ? (
            <div className="flex flex-col gap-3 rounded-xl border p-3">
              <MemberPicker
                schoolId={conversation.schoolId}
                kind={conversation.groupKind}
                classId={conversation.classId ?? null}
                value={picked}
                onChange={(ids) => setPicked(ids)}
                exclude={existing}
                label={t("addLabel")}
              />
              {add.error ? (
                <p role="alert" className="text-sm font-medium text-destructive">
                  {errorMessage(add.error, t("addFailed"))}
                </p>
              ) : null}
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" onClick={() => setAdding(false)}>
                  {t("cancel")}
                </Button>
                <Button type="button" disabled={picked.length === 0 || add.isPending} onClick={() => add.mutate(picked)}>
                  {t("addSubmit")}
                </Button>
              </div>
            </div>
          ) : (
            <Button type="button" variant="outline" onClick={() => setAdding(true)}>
              {t("add")}
            </Button>
          )
        ) : null}

        <DialogFooter>
          <Button type="button" onClick={onClose}>
            {t("close")}
          </Button>
        </DialogFooter>
      </DialogContent>

      {removing ? (
        <ReasonDialog
          title={t("removeTitle", { name: participant(removing) })}
          description={t("removeDescription")}
          label={t("removeReason")}
          submitLabel={t("removeConfirm")}
          maxLength={200}
          pending={remove.isPending}
          error={remove.error}
          onClose={() => {
            remove.reset();
            setRemoving(null);
          }}
          onSubmit={(reason) => remove.mutate({ memberId: removing.memberId, reason })}
        />
      ) : null}
    </Dialog>
  );
}
