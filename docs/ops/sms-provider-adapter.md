# SMS provider adapter: what to implement

No SMS provider is chosen yet (the owner decides, Implementation Plan "Provider decisions"). The announcement channel is
built so that choosing one is a small, contained piece of work: one class per provider (or per provider and country).
Today only the `system` adapter exists: it hands the text to `system.SmsSender` (`school.sms.provider=console`: the log).
It is for development; it has no sender id, templates, receipts or opt-out handling.

Announcement SMS is **off in every school** (`announcements.channel.sms.enabled=false`) and, once a school switches it
on, limited to URGENT and SAFETY announcements (`announcements.sms.types`), at most 500 per announcement and 1000 per
school and day (`announcements.sms.max_per_announcement`, `announcements.sms.max_per_day`). Nothing below changes that.

## The contract

Implement `com.nemaz.school.communications.SmsAdapter` and register it as a Spring bean (module `communications`,
package `infrastructure`, next to `SystemSmsAdapter`):

| Method | Must do |
|---|---|
| `id()` | A short stable name, stored with every message (`sms_adapter`), e.g. `africastalking`. |
| `countries()` | ISO country codes it serves (`ET`, `KE`...). `Set.of("*")` serves every country that has no adapter of its own; at most one adapter per country (two stop the application at startup). Remove or narrow `SystemSmsAdapter` (`*`) when the first real adapter arrives. |
| `send(SmsMessage)` | Send one text; return `SmsResult.accepted(providerRef)` when the provider took it, `SmsResult.failed(reason)` for any ordinary failure. Do not throw for provider errors, do not retry (the platform decides: a failed SMS is recorded and never resent automatically), never log or store the number or the text. |

`SmsMessage` carries `phoneE164`, `text` (already rendered in the recipient's language, school name plus announcement
title, never the body), `languageCode`, `senderId` (currently always null) and `clientReference` (the recipient row id).

## Checklist for a provider integration

1. **Account, billing and limits.** Per-country sender capability, the provider's per-second throughput (the platform sends
   a batch of 100 rows at a time, one call per message; add a client-side throttle in the adapter), cost per message, and a
   spending alert at the provider. The platform caps are a second line of defence, not the first.
2. **Sender ids.** Most countries need a registered alphanumeric sender id or a short code, often per use case.
   Ethiopia and Kenya differ; record per country what is registered. Put the id in configuration
   (`school.communications.sms.<provider>.sender-id`), not in code; pass `senderId` through `SmsMessage` when a school gets
   its own.
3. **Templates / pre-approval.** Where the country or provider requires pre-approved templates (DLT in India, some
   Ethiopian operators), the template is `announcement.sms.text` (`{0}: {1}. Open the app for details.`, in `en` and `am`,
   `messages*.properties`): get exactly that text approved, in both languages, and keep the length within a single segment
   (the title is cut to 80 characters; Amharic is UCS-2 so 70 characters per segment: decide whether the title must be
   shortened further for `am`).
4. **Delivery receipts.** Today the status stops at SENT (accepted by the provider). Add a webhook (a Route Handler or a
   controller in `communications.api`, authenticated with the provider's signature) that finds the row by
   `sms_provider_ref` (or `clientReference`) and moves `sms_status` to DELIVERED or FAILED. That needs two additions the
   code does not have yet: a `DELIVERED` value in the `sms_status` check constraint (a new migration) and the counts in
   `AnnouncementReports`.
5. **Opt-outs.** The provider's STOP/UNSUBSCRIBE keyword must reach `SmsOptOuts.optOut(phoneE164)` from the inbound webhook:
   the number is then never texted again, urgent or not (`sms_opt_outs`, source `PROVIDER`; the person cannot switch it
   back on from the app, only the provider's START keyword can, which the webhook should answer with a removal: add
   `SmsOptOuts.optIn` when needed). Also honour the person's own choice in the app (`communication_preferences`), which the
   platform already checks before calling the adapter.
6. **Number formats and routing.** Numbers are E.164. The registry chooses the adapter by the number's country from
   libphonenumber (`Contacts.region`), not by the school's country: a Kenyan parent of an Ethiopian school gets the Kenyan
   adapter.
7. **Secrets.** API keys through environment variables validated at startup (`DeploymentConfigValidator`), never in the
   repository; the dev credentials rule of `CLAUDE.md` applies.
8. **Errors and observability.** Map provider error codes to short stable strings (`insufficient_balance`,
   `invalid_sender_id`, `number_unreachable`); they are stored in `sms_reason` and shown to school administrators in the
   report. Never put the number or the text in a log line or an error string. Add a metric for accepted/failed per adapter
   and an alert on `insufficient_balance`.
9. **Tests.** The `RecordingSmsAdapter` in the test sources is the pattern (a Kenyan adapter used by
   `AnnouncementChannelsTest`). Add a contract test against the provider's sandbox if it has one, and a test that a
   failing adapter is recorded as FAILED and not retried.
10. **Legal and consent.** Transactional SMS to parents needs a lawful basis and, in several countries, registration with
    the regulator or operator; the content is limited to the school's name and the title. Confirm with counsel whether
    parents must opt in to SMS (today everybody is opted in until they opt out, and only urgent and safety texts are sent).
11. **Rollout.** Switch on per school (`announcements.channel.sms.enabled`), starting with one pilot school and a low
    `announcements.sms.max_per_day`; the report of each announcement shows sent, failed and skipped (opted out, no number,
    caps) so the cost is visible.

## What the platform already does for you

- Opt-outs (the person's and the provider's) are checked before every SMS, urgent or not.
- Caps per announcement and per school day are counted under a lock, so concurrent announcements cannot overshoot them.
- A non-urgent text waits for the end of the recipient's quiet hours; urgent and safety texts do not.
- A crash never sends twice: rows are claimed (`SENDING`) before the call; one still claimed after 15 minutes becomes
  FAILED `interrupted` and is never retried.
- Withdrawing an announcement cancels texts that have not been sent.
