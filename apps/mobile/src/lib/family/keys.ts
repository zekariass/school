/** Query keys of the guardian screens ("My children", consent). */

export const FAMILY_ROOT = 'family';

export const myChildrenKey = [FAMILY_ROOT, 'children'] as const;
export const myConsentsKey = [FAMILY_ROOT, 'consents'] as const;

/**
 * Children's names, classes and the consent texts and decisions are fetched again every time and
 * never written to the persisted query cache (sign-out also clears memory). Nothing here is needed offline.
 */
export function isPersistableFamilyQuery(queryKey: readonly unknown[]): boolean {
  return queryKey[0] !== FAMILY_ROOT;
}
