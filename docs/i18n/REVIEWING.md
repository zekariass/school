# Reviewing the Amharic text of the School app

Thank you for helping. You do not need any technical knowledge. You only need a spreadsheet program (Excel, Google Sheets or LibreOffice) and your knowledge of Amharic.

## 1. What is the file?

`docs/i18n/review/amharic-review.csv` lists **every piece of text** the app shows in Amharic: buttons, titles, error messages, notifications, emails, the names of months and days, and the names of roles, regions and plans. There is one row per text, with the English meaning next to the Amharic that is used today. The Amharic was written as a first draft and **has not yet been checked by a native speaker**. That is your job.

The file is a "CSV" (a plain table). It is saved in a way that lets Excel show Ethiopic letters correctly.

## 2. How to open it

**Excel**
1. Open Excel, choose *Data > From Text/CSV*, select the file.
2. Make sure *File origin* says **65001: Unicode (UTF-8)** and the delimiter is **Comma**. Click *Load*.
3. Immediately use *File > Save As* and save as an **Excel Workbook (.xlsx)** so you do not lose formatting while you work.
4. Tip: select all columns and choose *Wrap Text*, and widen the `english`, `amharic_current` and `amharic_reviewed` columns. Freeze the top row (*View > Freeze Panes*).

**Google Sheets**
1. Create a new sheet, choose *File > Import > Upload*, select the file.
2. Import location: *Replace spreadsheet*. Separator: *Comma*. Untick "Convert text to numbers, dates and formulas".

**LibreOffice Calc**: open the file, choose *Unicode (UTF-8)* and separator *Comma*.

Do **not** edit the file in Notepad or a word processor.

## 3. The columns

| Column | What it is | What you do |
| --- | --- | --- |
| `id` | A fixed code for the text (for example `web:auth.signIn`). | Never change it. |
| `source` | Where the text lives: `web` (website and phone app), `server` (messages sent by the server, errors, notifications, emails), `calendar` (month and day names), `seed` (names of roles, regions, plans). | Nothing. |
| `area` | The part of the app (for example `auth` = signing in, `console` = admin screens, `notification`). | Use it to review one topic at a time. |
| `english` | The English text. This is the meaning to keep. | Read only. |
| `amharic_current` | The Amharic used in the app today. | Read; judge it. |
| `placeholders` | Words in `{curly brackets}` that the program fills in (see section 5). | Keep them in your text. |
| `context_note` | A hint: where the text appears, whether it is a button, title or error, and how special plural forms work. Lines starting with `CHECK:` mark texts that look unfinished. | Read it before judging. |
| `amharic_reviewed` | **Your corrected Amharic.** | Fill only if the current text needs a change. |
| `reviewer_comment` | Any note for the team. | Optional. |
| `status` | **Your decision.** | Fill for every row you looked at. |

## 4. How to mark a row

- `OK`: the current Amharic is good. Leave `amharic_reviewed` empty.
- `CHANGED`: you want a different text. Type the **complete** new Amharic in `amharic_reviewed` (not only the changed word).
- `UNSURE`: you cannot decide or need to ask someone. Add a note in `reviewer_comment`; leave `amharic_reviewed` empty or put your suggestion there.

Rows with an empty `status` are treated as "not reviewed yet". Only rows with `CHANGED` are applied. You do not have to review everything at once; you can send the file back in parts.

## 5. Placeholders and special forms: keep them exactly

Some texts contain parts in `{curly brackets}`, for example:

- `Enter the {length}-digit code we sent to {destination}`: the program replaces `{length}` with a number (6) and `{destination}` with a phone number or email. **Copy `{length}` and `{destination}` exactly**, with the same spelling and the same brackets. You may move them to where they fit best in an Amharic sentence. Do not translate them, and do not add spaces inside.
- Server messages use numbers: `{0}`, `{1}`, `{2}`. Same rule: keep each one, once, exactly.
- **Plural texts** look like `{count, plural, one {# point} other {# points}}`. The program picks `one` when the number is 1 and `other` for any other number; `#` shows the number. Keep the words `plural`, `one`, `other`, the commas and all brackets exactly; translate only the text inside the inner brackets. The `context_note` explains each case.
- **Choice texts** look like `{gender, select, female {...} other {...}}`. Keep the choice words (`female`, `other`...); translate only what is inside the inner brackets.
- A text may contain `\n`. It means "new line"; keep it.
- Do not use straight quotes or apostrophes inside Amharic text unless needed (the program handles them).

If you break a placeholder, the tool that applies your changes will refuse the row and tell the team which row to fix, so you cannot damage the app, but please be careful.

## 6. Tone: a suggestion list (you can override all of it)

The app is used by teachers, parents, school administrators and children. Suggestions:

- Friendly, calm, respectful; short sentences; everyday words rather than formal office or very old-fashioned words.
- Address adults (teachers, parents, administrators) politely with the "እርስዎ" forms: እባክዎ, ይሞክሩ, ይምረጡ, ኮድዎ.
- Where the text is meant for children (for example the student sign-in with a class code, points and badges), use a gentler, encouraging tone and simple words. If you think a child-directed text should use the singular informal form, mark it `CHANGED` and say so in `reviewer_comment`.
- Buttons and menu items: very short, one verb (for example "አስቀምጥ", "ሰርዝ"). Check they fit; long buttons are hard to read on a phone.
- Error messages: explain what went wrong and what to do next, never blame the user.
- Keep names of people, schools and places as the user typed them; do not translate them.
- Use the Ethiopic full stop `።` at the end of a sentence, and Ethiopic comma `፣`. Keep titles and button labels without a full stop.
- Choose one spelling per word and use it everywhere (for example ዓይነት or አይነት; ኅዳር or ሕዳር). The file `review/consistency-findings.md` lists where the app currently mixes spellings.
- Numbers are written with ordinary digits (1, 2, 3).
- Technical words that people say in English (QR, API, SMS) may stay in Latin letters.

## 7. Glossary: please fill in the right-hand column

These words appear in many places. The third column shows what the app uses **now**, found automatically (it can be imprecise, since it is a statistical guess). In the fourth column write the word you want used **everywhere**. The team will then align all texts with your choice. A "-" means the app has no stand-alone word for that term yet (for example "badge" and "guardian"); please suggest one.

<!-- glossary:start -->
| English term | Amharic used now (auto-detected, most frequent) | Other forms seen | Your preferred Amharic | Notes |
| --- | --- | --- | --- | --- |
| school | School ("School"); ትምህርት ቤቶች ("Schools"); ትምህርት ቤት ("School") | ትምህርት (61), ትምህርት ቤት (19), የትምህርት (14) |  |  |
| class | ክፍሎች ("Classes") | ክፍል (8), የክፍል (7), ክፍሎች (4) |  |  |
| teacher | መምህራን ("Teachers"); መምህር ("Teacher") | መምህራን (3), ወላጆች (2) |  |  |
| parent | ወላጅ ("Parent") | ወላጆች (2), መምህራን (2) |  |  |
| guardian | - | - |  |  |
| student | ተማሪዎች ("Students"); ተማሪ ("Student") | ተማሪ (3), ተማሪዎችና (2), ወላጆች (2) |  |  |
| attendance | መገኘት ("Attendance") | መገኘት (2) |  |  |
| points | ነጥቦች ("Points") | ነጥቦች (2), ዝመናዎች (2), ነጥብ (2) |  |  |
| badge | - | - |  |  |
| portfolio | ስብስብ ("Portfolio") | የፖርትፎሊዮ (2) |  |  |
| announcement | ማስታወቂያዎች ("Announcements") | ማስታወቂያዎች (2) |  |  |
| tutor | አስጠኚ ("Tutor") | - |  |  |
| notification | ማሳወቂያዎች ("Notifications") | ማሳወቂያዎች (16), የማሳወቂያ (5), ማሳወቂያዎችን (5) |  |  |
| calendar | የቀን መቁጠሪያ ("Calendar") | የቀን (5), የቀን መቁጠሪያ (4) |  |  |
| sign in | ግባ ("Sign in") | መግቢያ (12), የመግቢያ (10), ይግቡ (9) |  |  |
| sign out | ውጣ ("Sign out") | ወጥተዋል (2), እንደገና ይግቡ (2), እባክዎ እንደገና (2) |  |  |
<!-- glossary:end -->

## 8. When you are done

1. Check that every row you looked at has a `status`.
2. Save as **CSV UTF-8**: Excel: *File > Save As > CSV UTF-8 (Comma delimited)*; Google Sheets: *File > Download > Comma-separated values (.csv)*.
3. Send back the CSV (and the filled glossary) to the team. Do not rename the columns or delete rows.
4. If you are not finished, send what you have. Rows without a `status` are simply skipped.

---

## For developers: applying the review

```bash
# 1. Regenerate the sheet (after text changes), summary and consistency report
node scripts/i18n/export-review.mjs --update-readme
node scripts/i18n/consistency.mjs

# 2. Check what a reviewed file would change (dry run is the default; nothing is written)
node scripts/i18n/apply-review.mjs path/to/amharic-review-reviewed.csv

# 3. Apply
node scripts/i18n/apply-review.mjs path/to/amharic-review-reviewed.csv --write

# 4. Verify
npm run test -w @school/i18n          # catalog key and placeholder parity
node --test 'scripts/i18n/*.test.mjs' # the review scripts
# backend: MessagesTest (same key set in messages.properties and messages_am.properties)
```

- Rows with `status` = `CHANGED` and a non-empty `amharic_reviewed` are applied. `web:` rows go to `packages/i18n/messages/am.json` (key order and 2-space format kept), `server:` rows to `messages_am.properties` (UTF-8, `\n`/`\\` escaped, apostrophes doubled for messages that take `{0}` arguments).
- `calendar:` and `seed:` rows are not written; a report `docs/i18n/review/manual-edits.md` lists where to edit them by hand (`packages/calendar/src/format.ts`, `R__*.sql`; remember that `on conflict ... do nothing` seeds need an update migration for existing databases).
- Validation refuses the whole file (nothing is written) if a changed row has an empty text, unbalanced braces, different placeholder names than English, a changed plural/select structure, an unknown id or an invalid status. Warnings (stale English or stale current Amharic in the CSV) do not block.
- Update the glossary table in this file with `node scripts/i18n/export-review.mjs --update-guide` (re-extracts the current terms; overwrites the table).
