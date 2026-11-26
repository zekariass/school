import { linkify } from "@/lib/announcements/text";
import { cn } from "@/lib/utils";

/**
 * Announcement text, rendered strictly as text: React escapes every character, so markup in a title or body is just
 * characters. The only thing made interactive is an http(s) link (see `linkify`), opened in a new tab without
 * referrer or opener.
 */
export function PlainText({ text, className }: { text: string; className?: string }) {
  return (
    <p dir="auto" className={cn("whitespace-pre-wrap [overflow-wrap:anywhere]", className)}>
      {linkify(text).map((segment, index) =>
        segment.kind === "link" ? (
          <a
            key={index}
            href={segment.href}
            target="_blank"
            rel="noopener noreferrer nofollow ugc"
            className="font-medium text-primary underline underline-offset-2 hover:no-underline"
          >
            {segment.text}
          </a>
        ) : (
          <span key={index}>{segment.text}</span>
        ),
      )}
    </p>
  );
}
