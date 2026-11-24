export type ThemePreference = "light" | "dark" | "system";

export const THEME_STORAGE_KEY = "school.theme";

export function isThemePreference(value: unknown): value is ThemePreference {
  return value === "light" || value === "dark" || value === "system";
}

/**
 * Runs synchronously from <head> before first paint so there's no flash of the
 * wrong theme. Keep in sync with ThemeProvider (same key, same rule).
 */
export const THEME_INIT_SCRIPT = `(function(){try{var t=localStorage.getItem(${JSON.stringify(
  THEME_STORAGE_KEY,
)});var d=t==="dark"||((t!=="light")&&window.matchMedia("(prefers-color-scheme: dark)").matches);document.documentElement.classList.toggle("dark",d)}catch(e){}})()`;
