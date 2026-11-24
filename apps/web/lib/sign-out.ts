/** Client side of sign-out: the BFF revokes the session and clears the cookies. */
export async function signOut(locale: string): Promise<void> {
  try {
    await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
  } catch {
    // Offline: still leave the page; the cookies expire on their own.
  }
  // A full navigation (not the client router): the next page must render without the old session.
  const destination = `/${locale}/sign-in`;
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(destination);
}
