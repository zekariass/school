import { WorkspaceMessagesFrame } from "@/components/messaging/messages-frame";

export default function WorkspaceMessagesLayout({ children }: LayoutProps<"/[locale]/s/[school]/messages">) {
  return <WorkspaceMessagesFrame>{children}</WorkspaceMessagesFrame>;
}
