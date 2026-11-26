"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BellIcon, BellOffIcon, LogOutIcon, MoreVerticalIcon, SettingsIcon, UsersIcon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { ConfirmDialog } from "@/components/school/confirm-dialog";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { useRouter } from "@/i18n/navigation";
import { errorMessage } from "@/lib/console/errors";
import { isMuted } from "@/lib/messaging/inbox";
import { mk } from "@/lib/messaging/keys";
import { leaveConversation, muteConversation, unmuteConversation } from "@/lib/messaging/queries";
import type { ConversationSummary } from "@/lib/messaging/types";
import { MembersDialog } from "./members-dialog";
import { useMessagesPlace } from "./messages-context";
import { SettingsDialog } from "./settings-dialog";

const HOUR = 3_600_000;
const MUTE_CHOICES = [
  { key: "hour", ms: HOUR },
  { key: "eightHours", ms: 8 * HOUR },
  { key: "day", ms: 24 * HOUR },
  { key: "week", ms: 7 * 24 * HOUR },
] as const;

/** Mute, people, settings and leaving. Anything that needs a right is shown only when the API says the caller has it. */
export function ConversationMenu({ conversation, canModerate }: { conversation: ConversationSummary; canModerate: boolean }) {
  const t = useTranslations("school.messaging.menu");
  const queryClient = useQueryClient();
  const router = useRouter();
  const { base } = useMessagesPlace();
  const [dialog, setDialog] = useState<"members" | "settings" | "leave" | null>(null);
  const muted = isMuted(conversation, new Date());
  const observer = conversation.myRole === "OBSERVER";
  const group = conversation.type !== "DIRECT";

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: mk.conversation(conversation.id) });
    void queryClient.invalidateQueries({ queryKey: mk.inboxAll });
  };
  const mute = useMutation({
    mutationFn: (ms: number) => muteConversation(conversation.id, new Date(Date.now() + ms).toISOString()),
    onSuccess: () => {
      refresh();
      toast.success(t("mutedToast"));
    },
    onError: (e) => toast.error(errorMessage(e, t("failed"))),
  });
  const unmute = useMutation({
    mutationFn: () => unmuteConversation(conversation.id),
    onSuccess: () => {
      refresh();
      toast.success(t("unmutedToast"));
    },
    onError: (e) => toast.error(errorMessage(e, t("failed"))),
  });
  const leave = useMutation({
    mutationFn: () => leaveConversation(conversation.id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: mk.all });
      router.push(base);
    },
  });

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button type="button" variant="ghost" size="icon" aria-label={t("label")} data-testid="conversation-menu">
            <MoreVerticalIcon aria-hidden />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-56">
          <DropdownMenuItem onSelect={() => setDialog("members")} data-testid="menu-members">
            <UsersIcon aria-hidden />
            {t("people")}
          </DropdownMenuItem>
          {canModerate && group ? (
            <DropdownMenuItem onSelect={() => setDialog("settings")} data-testid="menu-settings">
              <SettingsIcon aria-hidden />
              {t("settings")}
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuSeparator />
          {muted ? (
            <DropdownMenuItem onSelect={() => unmute.mutate()} data-testid="menu-unmute">
              <BellIcon aria-hidden />
              {t("unmute")}
            </DropdownMenuItem>
          ) : (
            <>
              <DropdownMenuLabel className="flex items-center gap-2 text-xs">
                <BellOffIcon className="size-3.5" aria-hidden />
                {t("muteFor")}
              </DropdownMenuLabel>
              {MUTE_CHOICES.map((choice) => (
                <DropdownMenuItem key={choice.key} onSelect={() => mute.mutate(choice.ms)} data-testid={`menu-mute-${choice.key}`}>
                  {t(`mute.${choice.key}`)}
                </DropdownMenuItem>
              ))}
            </>
          )}
          {group && !observer ? (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem variant="destructive" onSelect={() => setDialog("leave")} data-testid="menu-leave">
                <LogOutIcon aria-hidden />
                {t("leave")}
              </DropdownMenuItem>
            </>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>

      {dialog === "members" ? <MembersDialog conversation={conversation} canModerate={canModerate} onClose={() => setDialog(null)} /> : null}
      {dialog === "settings" ? <SettingsDialog conversation={conversation} onClose={() => setDialog(null)} /> : null}
      {dialog === "leave" ? (
        <ConfirmDialog
          title={t("leaveTitle")}
          description={t("leaveDescription")}
          confirmLabel={t("leaveConfirm")}
          pending={leave.isPending}
          error={leave.error ? errorMessage(leave.error, t("failed")) : null}
          onClose={() => {
            leave.reset();
            setDialog(null);
          }}
          onConfirm={() => leave.mutate()}
        />
      ) : null}
    </>
  );
}
