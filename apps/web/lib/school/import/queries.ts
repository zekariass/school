import { ApiRequestError, ensureOk, unwrap } from "@/lib/api-error";
import { getBrowserApi } from "@/lib/api-client";
import { readProblem } from "@/lib/problem";
import type { ImportKind, ImportOptions, ImportStatus, RowStatus } from "./state";

const api = () => getBrowserApi();

/** Rows per page of the review list. */
export const ROWS_PAGE = 50;
/** Imports per page of the history. */
export const JOBS_PAGE = 20;

/** Query keys. Rows and previews hold children's data: they are kept in memory only and dropped quickly. */
export const ik = {
  all: (schoolId: string) => ["school", schoolId, "imports"] as const,
  types: (schoolId: string) => ["school", schoolId, "imports", "types"] as const,
  list: (schoolId: string, filter: object) => ["school", schoolId, "imports", "list", filter] as const,
  job: (schoolId: string, jobId: string) => ["school", schoolId, "imports", "job", jobId] as const,
  preview: (schoolId: string, jobId: string, reading: object | null) =>
    ["school", schoolId, "imports", "job", jobId, "preview", reading] as const,
  rows: (schoolId: string, jobId: string, status: string) =>
    ["school", schoolId, "imports", "job", jobId, "rows", status] as const,
  rowsOf: (schoolId: string, jobId: string) => ["school", schoolId, "imports", "job", jobId, "rows"] as const,
  profiles: (schoolId: string, kind: ImportKind) => ["school", schoolId, "imports", "profiles", kind] as const,
};

export const fetchImportTypes = async (schoolId: string) =>
  unwrap(await api().GET("/api/v1/schools/{schoolId}/imports/types", { params: { path: { schoolId } } }));

export interface ImportFilters {
  status?: ImportStatus;
  importType?: ImportKind;
}

export const fetchImports = async (schoolId: string, filters: ImportFilters, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/imports", {
      params: { path: { schoolId }, query: { ...filters, cursor, limit: JOBS_PAGE } },
    }),
  );

export const fetchImport = async (schoolId: string, importId: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/imports/{importId}", { params: { path: { schoolId, importId } } }),
  );

export const fetchPreview = async (
  schoolId: string,
  importId: string,
  reading: { encoding: string; delimiter: string; sheet: string } | null,
) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/imports/{importId}/preview", {
      params: { path: { schoolId, importId }, query: reading ?? {} },
    }),
  );

export const fetchImportRows = async (schoolId: string, importId: string, status: RowStatus | undefined, cursor?: string) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/imports/{importId}/rows", {
      params: { path: { schoolId, importId }, query: { status, cursor, limit: ROWS_PAGE } },
    }),
  );

export const fetchProfiles = async (schoolId: string, importType: ImportKind) =>
  unwrap(
    await api().GET("/api/v1/schools/{schoolId}/imports/mapping-profiles", {
      params: { path: { schoolId }, query: { importType } },
    }),
  );

// --- writes ---------------------------------------------------------------------------------

export const createImport = async (
  schoolId: string,
  body: { importType: ImportKind; mediaId: string; options?: ImportOptions },
  idempotencyKey: string,
) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/imports", {
      params: { path: { schoolId } },
      headers: { "Idempotency-Key": idempotencyKey },
      body,
    }),
  );

export const saveMapping = async (
  schoolId: string,
  importId: string,
  body: { columnMapping: Record<string, number>; options?: ImportOptions; saveAsProfile?: string },
) =>
  unwrap(
    await api().PUT("/api/v1/schools/{schoolId}/imports/{importId}/mapping", {
      params: { path: { schoolId, importId } },
      body,
    }),
  );

export const startDryRun = async (schoolId: string, importId: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/imports/{importId}/dry-run", {
      params: { path: { schoolId, importId } },
    }),
  );

export const startCommit = async (schoolId: string, importId: string, skipInvalidRows: boolean) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/imports/{importId}/commit", {
      params: { path: { schoolId, importId } },
      body: { skipInvalidRows },
    }),
  );

export const resolveRow = async (
  schoolId: string,
  importId: string,
  rowId: string,
  body: { resolution: "LINK" | "CREATE_NEW" | "SKIP"; studentId?: string },
) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/imports/{importId}/rows/{rowId}/resolve", {
      params: { path: { schoolId, importId, rowId } },
      body,
    }),
  );

export const cancelImport = async (schoolId: string, importId: string) =>
  unwrap(
    await api().POST("/api/v1/schools/{schoolId}/imports/{importId}/cancel", {
      params: { path: { schoolId, importId } },
    }),
  );

export const deleteImport = async (schoolId: string, importId: string) =>
  ensureOk(
    await api().DELETE("/api/v1/schools/{schoolId}/imports/{importId}", { params: { path: { schoolId, importId } } }),
  );

export const deleteProfile = async (schoolId: string, profileId: string) =>
  ensureOk(
    await api().DELETE("/api/v1/schools/{schoolId}/imports/mapping-profiles/{profileId}", {
      params: { path: { schoolId, profileId } },
    }),
  );

// --- file downloads ---------------------------------------------------------------------------

/**
 * Fetches a file through the BFF (the session cookie is attached server-side) and hands it to the browser as a
 * download. The bytes live in memory only for the moment of the download; nothing is stored or cached
 * (`cache: "no-store"`; the API sends `no-store` as well).
 */
export async function downloadFile(path: string, filename: string): Promise<void> {
  const response = await fetch(`${window.location.origin}/api/bff${path}`, {
    method: "GET",
    cache: "no-store",
    headers: { "Accept-Language": document.documentElement.lang || "en" },
  });
  if (!response.ok) throw new ApiRequestError(response.status, (await readProblem(response)) ?? undefined);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  try {
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
    link.rel = "noopener";
    link.hidden = true;
    document.body.appendChild(link);
    link.click();
    link.remove();
  } finally {
    // The click has started the save; the link is not needed any more.
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }
}

export const templatePath = (schoolId: string, kind: ImportKind, format: "csv" | "xlsx") =>
  `/api/v1/schools/${schoolId}/imports/templates/${kind}?format=${format}`;

export const errorReportPath = (schoolId: string, importId: string) =>
  `/api/v1/schools/${schoolId}/imports/${importId}/error-report`;
