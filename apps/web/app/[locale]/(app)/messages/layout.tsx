import { MessagesShell } from "@/components/messaging/messages-shell";

export default function MessagesLayout({ children }: LayoutProps<"/[locale]/messages">) {
  return <MessagesShell>{children}</MessagesShell>;
}
