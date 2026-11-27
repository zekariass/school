"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { SelectField } from "@/components/school/fields";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { DECISIONS, MAX_NOTE, buildReviewBody, noteProblem } from "@/lib/portfolio/actions";
import { parseGateError, type PortfolioGateError } from "@/lib/portfolio/consent-error";
import { pfk, reviewItem, type PortfolioDecision, type PortfolioItem, type PortfolioVisibility } from "@/lib/portfolio/queries";
import { reviewVisibilityChoices } from "@/lib/portfolio/view";
import { cn } from "@/lib/utils";
import { GateNotice } from "./gate-notice";

/**
 * A reviewing teacher's decision on a submitted item: approve (optionally for a narrower audience), ask for changes or
 * reject, the last two with a note the student reads. When the consent gate refuses an approval nothing is changed
 * and the dialog explains why; the teacher can pick a narrower audience, ask for changes, or leave it.
 */
export function ReviewDialog({ schoolId, item, onClose }: { schoolId: string; item: PortfolioItem; onClose: () => void }) {
  const t = useTranslations("school.portfolio.review");
  const tp = useTranslations("school.portfolio.problems");
  const tv = useTranslations("school.portfolio.visibility");
  const queryClient = useQueryClient();
  const [decision, setDecision] = useState<PortfolioDecision>("APPROVE");
  const [note, setNote] = useState("");
  const [visibility, setVisibility] = useState<PortfolioVisibility>(item.visibility);
  const [gate, setGate] = useState<PortfolioGateError | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tried, setTried] = useState(false);
  const choices = reviewVisibilityChoices(item.visibility);
  const problem = noteProblem(decision, note);

  const save = useMutation({
    mutationFn: () => reviewItem(schoolId, item.id, buildReviewBody(item, decision, note, visibility)),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: pfk.all });
      toast.success(t(`done.${decision}`));
      onClose();
    },
    onError: (caught) => {
      const refused = parseGateError(caught);
      setGate(refused);
      setError(refused ? null : errorMessage(caught, t("failed")));
    },
  });

  function send() {
    setGate(null);
    setError(null);
    setTried(true);
    if (problem) return;
    save.mutate();
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !save.isPending && onClose()}>
      <DialogContent className="max-h-[92dvh] overflow-y-auto sm:max-w-lg" data-testid="portfolio-review">
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            send();
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("title")}</DialogTitle>
            <DialogDescription>{t("help")}</DialogDescription>
          </DialogHeader>

          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">{t("decision")}</legend>
            {DECISIONS.map((value) => (
              <label
                key={value}
                className={cn(
                  "flex min-h-12 cursor-pointer items-start gap-3 rounded-xl border p-3 text-sm",
                  decision === value ? "border-primary bg-primary/5" : "hover:bg-accent/50",
                )}
              >
                <input
                  type="radio"
                  name="portfolio-decision"
                  className="mt-0.5 size-5 shrink-0 accent-primary"
                  checked={decision === value}
                  onChange={() => {
                    setDecision(value);
                    setGate(null);
                  }}
                  data-testid={`portfolio-decision-${value}`}
                />
                <span>
                  <span className="block font-medium">{t(`decisions.${value}`)}</span>
                  <span className="text-muted-foreground">{t(`decisionHelp.${value}`)}</span>
                </span>
              </label>
            ))}
          </fieldset>

          {decision === "APPROVE" && choices.length > 1 ? (
            <SelectField
              label={t("audience")}
              value={visibility}
              onChange={(value) => setVisibility(value as PortfolioVisibility)}
              options={choices.map((v) => ({ value: v, label: tv(v) }))}
              hint={t("audienceHelp")}
              disabled={save.isPending}
            />
          ) : null}

          <FormField
            label={decision === "APPROVE" ? t("noteOptional") : t("note")}
            hint={t("noteHelp", { count: note.length, max: MAX_NOTE })}
            error={tried && problem ? tp(`note.${problem}`) : null}
          >
            {(props) => (
              <Textarea
                {...props}
                rows={3}
                value={note}
                maxLength={MAX_NOTE}
                onChange={(event) => setNote(event.target.value)}
                disabled={save.isPending}
                data-testid="portfolio-review-note"
              />
            )}
          </FormField>

          {gate ? <GateNotice error={gate} audience="reviewer" /> : null}
          {error ? (
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="portfolio-review-error">
              {error}
            </p>
          ) : null}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={save.isPending}>
              {t("cancel")}
            </Button>
            <Button type="submit" variant={decision === "REJECT" ? "destructive" : "default"} disabled={save.isPending} data-testid="portfolio-review-send">
              {save.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
              {t(`send.${decision}`)}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
