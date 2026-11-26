import { notFound } from "next/navigation";
import { Suspense } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { isUuid } from "@/lib/feed/ids";
import { ConversationView } from "./conversation-view";

/**
 * The conversation route (`/messages/{id}` and `/s/{slug}/messages/{id}`). The id goes into API paths, so only a well-formed
 * UUID passes; it is the only thing in the URL (never a name or text), and the document title never names the conversation.
 */
export function ConversationRoute({ params }: { params: Promise<{ conversationId: string }> }) {
  return (
    <Suspense fallback={<Skeleton className="h-96 rounded-2xl" />}>
      <Opened params={params} />
    </Suspense>
  );
}

async function Opened({ params }: { params: Promise<{ conversationId: string }> }) {
  const { conversationId } = await params;
  if (!isUuid(conversationId)) notFound();
  return <ConversationView conversationId={conversationId} />;
}
