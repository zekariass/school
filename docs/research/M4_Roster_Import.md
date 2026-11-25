# M4 Roster import: research notes

*Researched 25 November 2026 for Implementation_Plan.md M4 and Education_Platform.md §125. Items marked (memory) were not re-verified. Re-check versions when M4 starts.*

## Decisions to make when M4 starts

| # | Decision | Recommendation |
|---|---|---|
| 1 | XLSX reader | Apache POI `poi-ooxml` 6.0.0 + `com.github.pjfanning:excel-streaming-reader` 6.0.0. `org.dhatim:fastexcel-reader` 0.20.3 is the lighter fallback |
| 2 | Ethiopian calendar on the backend | The JDK has **no** Ethiopic chronology (checked on JDK 21: only ISO, ThaiBuddhist, Minguo, Japanese, Hijrah). Use `org.threeten:threeten-extra` 1.11.0 (`EthiopicDate`) or port the ~30-line JDN algorithm from `packages/calendar/src/convert.ts`. Either way, add a parity test sweeping every day 1900–2100 against the TS fixture |
| 3 | Caps | 10 MB, 20,000 rows, 60 columns, 1,000 chars per cell; parse the header and a 20–50 row preview synchronously, run the dry run and commit as an async job |
| 4 | Raw-row retention | Purge `import_job_rows` raw payloads and the uploaded file 30 days after the job (7 days after commit), keeping counts and the audit trail |
| 5 | Duplicate thresholds | See "Duplicate detection" |
| 6 | Template languages | Machine keys in English; Amharic labels on an instructions sheet |
| 7 | XLSX error report | CSV at launch; XLSX later |

## XLSX

- POI's SXSSF is write-only. Read with the `XSSFReader` SAX API or the pjfanning wrapper. Never load `XSSFWorkbook` for uploads (about 10–20× the file size in heap).
- Stay on POI 6.x (past CVEs are mostly XXE and zip handling, e.g. CVE-2025-31672 fixed in 5.4.0 (memory)).
- **Pre-checks:** file ≤ 10 MB; magic bytes `PK\x03\x04` accepted; OLE2 `D0CF11E0` (`.xls`) and `.xlsm` rejected; reject archives containing `xl/vbaProject.bin`.
- **Zip limits** (`ZipSecureFile`): min inflate ratio 0.01, max entry size ~100 MB, max file count ~1000, max text size ~10M chars. Also check the sum of uncompressed sizes and entry count ourselves.
- **Behaviour:** never evaluate formulas; read cached values through `DataFormatter` (as displayed, which matters for phones and dates); first sheet only, or let the user pick; run on a bounded executor with a timeout. Neutralise `=+-@` prefixes in error reports (CSV/formula injection).
- Stream the object to a temp file first (the zip central directory needs random access), open with `OPCPackage.open(File)`, delete afterwards.

## CSV and encodings

- Parser: `org.apache.commons:commons-csv` 1.14.1.
- Delimiter: honour a `sep=;` first line; else sample 5–10 lines and score `, ; \t |` outside quotes; user can override.
- Encoding, in order: BOM (UTF-8, UTF-16LE/BE) → alternating `0x00` bytes means UTF-16 → strict UTF-8 decode (`CharsetDecoder` REPORT) → fall back to windows-1252. Skip detector libraries (juniversalchardet 2.5.2 is the small fallback; ICU4J is heavy and guesses poorly on short samples).
- UI: show "Detected: UTF-8, comma" with a 5-row preview and dropdowns to change either; U+FFFD or mojibake like `áˆ›` means a wrong guess. Persist the choices on the job and mapping profile.
- Excel "Unicode Text" is UTF-16LE with BOM and tab-delimited. Windows-1252 cannot hold Ethiopic.

## Normalisation

- **Phones:** strip spaces/dashes/dots/parentheses, convert Ethiopic/Arabic digits, parse with region ET. Number-typed Excel cells lose the leading zero (`911234567`, `9.11234567E8`): read via `DataFormatter`/`BigDecimal.toPlainString()`; 9 digits starting with 9 or 7 → prepend `+251`; remove a stray zero in `+2510911…`. Both `09…` (Ethio Telecom) and `07…` (Safaricom Ethiopia) are valid; libphonenumber may lag on new `07` ranges, so use `isPossibleNumber` plus a `+2519`/`+2517` 9-digit shape check and flag a warning instead of strict `isValidNumber`. Store E.164; keep the raw value in the row.
- **Names:** NFC, trim, collapse whitespace (treat U+1361 Ethiopic word space as a space), strip zero-width/bidi characters and punctuation inside names; keep the three parts as given. For **comparison only** build a fold key: casefold Latin; fold Ethiopic homophones (ሀ/ሐ/ኀ, ሰ/ሠ, አ/ዐ, ጸ/ፀ), optionally collapse vowel orders. Never alter the stored value. Latin vs Ethiopic spellings of one name can't be matched without transliteration; match those through DOB and phone only, and allow a school-supplied Latin-name column.
- **Dates:** Excel serial epoch 1899-12-30 for serials ≥ 61 (the 1900 leap bug shifts earlier ones; POI's `DateUtil` handles it; check the 1904 date-system flag). Text: ISO, `dd/MM/yyyy`, `d.M.yyyy`; never guess `MM/dd`. Ask once per import: "Dates are Gregorian / Ethiopian". Month 13 and years ~1990–2020 are strong Ethiopian signals; warn when a date gives an implausible age.

## Duplicate detection (students)

1. `external_entity_mappings` by external id → UPDATE.
2. Student number within the school → exact match.
3. Name + DOB: require equal DOB (±1 year with a warning), trigram similarity on the fold key. ≥ 0.9 with the same DOB and a guardian phone or class match → auto-match; 0.6–0.9 → "possible duplicate" the user resolves; < 0.6 → new.
4. Duplicates inside the file are errors.
5. `pg_trgm` is installed. Add a GIN trigram index on a stored fold key; compute the key in the application (`unaccent` does nothing for Ethiopic). Never match across schools. Commits are idempotent via row hash.

## Processing model

- Synchronous: header + preview only. Async: dry run and commit as an `import_jobs` job (Modulith event or ShedLock worker), progress by polling counters, notification on completion. Optional sync shortcut for ≤ 500 rows if validation finishes in ~5 s.
- A realistic school roster is 500–3,000 rows.
- Error report: original columns plus `row_number`, `severity`, `field`, `message`; UTF-8 **with BOM** and `\r\n` so Excel shows Amharic.

## Templates

- Stable English machine keys in the header (`given_name`, `father_name`, `grandfather_name`, `dob`, `gender`, `phone`, `class`, `external_id`). Put instructions on a separate sheet (an "instructions row" gets deleted or edited); mark an example row that the importer detects and skips.
- Text format for phone/id columns; dropdowns for gender, class names, date system; CSV template UTF-8 with BOM, comma-delimited.
- Guard against: merged cells, multiple header rows, blank rows and totals rows, hidden or wrong sheets, trailing spaces, duplicate headers, ids losing leading zeros or turned into scientific notation, auto-formatted dates and two-digit years, inconsistent name splits, a guardian phone shared by several students (valid), mixed import types in one file.

## Suggested steps

1. File sniffing and limits (size, zip, encoding, delimiter) with real Excel, WPS, LibreOffice and Google Sheets fixtures.
2. `RowSource` interface: CSV (commons-csv) and XLSX (POI streaming) returning string cells.
3. Normaliser package (phone, name + fold key, date incl. Ethiopian) with the TS/Java parity test.
4. Upload, preview and mapping endpoints with `import_mapping_profiles`.
5. Dry-run job: validation, duplicate matching, counts, error report.
6. Idempotent commit in plan order (years → subjects → classes → staff → students → enrollments → guardian contacts); guardians produce invitations only.
7. Templates and the retention purge job.
8. Try real pilot-school files.
