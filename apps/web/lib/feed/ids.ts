const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** True for a well-formed UUID: ids from a URL go into API paths only after this check. */
export function isUuid(value: unknown): value is string {
  return typeof value === "string" && UUID.test(value);
}
