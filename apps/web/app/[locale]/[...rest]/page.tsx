import { notFound } from "next/navigation";

// This route only ever renders the localized not-found page, so there is no
// UI to validate for instant navigation.
export const instant = false;

/** Unknown paths under a locale render the localized not-found page. */
export default function CatchAll() {
  notFound();
}
