/** Joins setting definitions (what can be set, and where) with a school's effective values. */

export interface DefinitionLike {
  key: string;
  module: string;
  description?: string | null;
  valueSchema: unknown;
  defaultValue: unknown;
  allowedScopes: readonly string[];
}

export interface EffectiveLike {
  key: string;
  value: unknown;
  source: string;
}

export interface SchoolSetting {
  key: string;
  module: string;
  description?: string | null;
  valueSchema: unknown;
  defaultValue: unknown;
  /** The effective value for the school. */
  value: unknown;
  /** Where the effective value comes from: SCHOOL, ORGANIZATION, COUNTRY, PLATFORM or DEFAULT. */
  source: string;
}

export interface SettingGroup {
  module: string;
  settings: SchoolSetting[];
}

/**
 * The settings a school may change (their definition allows the SCHOOL level), with the
 * effective value, grouped by module. Modules and keys are sorted so the page is stable.
 */
export function groupSettings(
  definitions: readonly DefinitionLike[],
  effective: readonly EffectiveLike[],
): SettingGroup[] {
  const byKey = new Map(effective.map((e) => [e.key, e]));
  const groups = new Map<string, SchoolSetting[]>();
  for (const d of definitions) {
    if (!d.allowedScopes.includes("SCHOOL")) continue;
    const e = byKey.get(d.key);
    const setting: SchoolSetting = {
      key: d.key,
      module: d.module,
      description: d.description,
      valueSchema: d.valueSchema,
      defaultValue: d.defaultValue,
      value: e ? e.value : d.defaultValue,
      source: e ? e.source : "DEFAULT",
    };
    const list = groups.get(d.module) ?? [];
    list.push(setting);
    groups.set(d.module, list);
  }
  return [...groups.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([module, settings]) => ({ module, settings: settings.sort((x, y) => x.key.localeCompare(y.key)) }));
}
