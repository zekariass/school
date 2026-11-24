/**
 * Accepts only same-origin relative paths ("/en/s/demo?tab=1"). Anything that a
 * browser could resolve to another origin ("//evil.com", "/\evil.com",
 * "https://evil.com", "javascript:…") is rejected, as are control characters
 * (browsers strip tabs/newlines, turning "/\t/evil.com" into "//evil.com").
 */
export function sanitizeNextPath(value: string | null | undefined): string | null {
  if (!value || value.length > 2048) return null;
  if (!value.startsWith("/") || value.startsWith("//")) return null;
  if (value.includes("\\")) return null;
  if (/[\u0000-\u001f\u007f]/.test(value)) return null;
  return value;
}

export function safeNextPathOr(value: string | null | undefined, fallback: string): string {
  return sanitizeNextPath(value) ?? fallback;
}
