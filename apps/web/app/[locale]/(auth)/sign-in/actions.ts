"use server";

import { isProblemDetail } from "@school/api-client";
import { isSupportedLocale } from "@school/i18n";
import { phoneSchema, toFieldErrors, type FieldError } from "@school/validation";
import { createServerApi } from "@/lib/server/api";

export type RequestSignInCodeResult =
  | { status: "sent"; destination: string }
  | { status: "invalid"; error: FieldError }
  /** The API answered with an error; title/detail are localized by the API. */
  | { status: "problem"; title?: string; detail?: string; code?: string }
  /** The API couldn't be reached at all. */
  | { status: "unavailable" };

/**
 * The OTP endpoints arrive with M1 and aren't in the generated OpenAPI types
 * yet, so the call goes through an untyped view of the client. Replace with the
 * typed call after `npm run codegen` once the backend exposes it.
 */
type UntypedApi = {
  POST(
    path: string,
    init: { body: unknown },
  ): Promise<{ data?: unknown; error?: unknown; response: Response }>;
};

export async function requestSignInCode(input: {
  phone: string;
  locale: string;
}): Promise<RequestSignInCodeResult> {
  // Server Actions can't read root params, so the page passes its locale.
  const locale = isSupportedLocale(input.locale) ? input.locale : "en";

  const parsed = phoneSchema("ET").safeParse(input.phone);
  if (!parsed.success) {
    const error = toFieldErrors(parsed.error)[""] ?? { key: "validation.phone", values: {} };
    return { status: "invalid", error };
  }
  const phone = parsed.data;

  let result;
  try {
    const api = (await createServerApi(locale)) as unknown as UntypedApi;
    result = await api.POST("/api/v1/auth/otp/request", { body: { phone } });
  } catch {
    return { status: "unavailable" };
  }

  if (result.response.ok) return { status: "sent", destination: phone };
  if (isProblemDetail(result.error)) {
    const { title, detail, code } = result.error;
    return { status: "problem", title, detail, code };
  }
  return { status: "problem" };
}
