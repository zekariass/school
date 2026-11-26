"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon } from "lucide-react";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { toast } from "sonner";
import { FormField } from "@/components/console/form-field";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Textarea } from "@/components/ui/textarea";
import { errorMessage } from "@/lib/console/errors";
import { buildOccurrenceBody, occurrenceForm, occurrenceProblems, DESCRIPTION_MAX, LOCATION_MAX, TITLE_MAX, type OccurrenceFormState, type OccurrenceValues } from "@/lib/calendar/form";
import { ck, updateOccurrence } from "@/lib/calendar/queries";

/**
 * "Only this event" of a repeating series: the title, place, text and time of one occurrence (moved by at most 7 days, in
 * the same kind, timed or all-day). The series and its other events stay as they are; a time or place change tells the
 * audience. The form keeps its values when the API refuses.
 */
export function OccurrenceFormDialog({ eventId, occurrenceId, values, onClose }: { eventId: string; occurrenceId: string; values: OccurrenceValues; onClose: () => void }) {
  const t = useTranslations("school.calendar");
  const tp = useTranslations("school.calendar.problems");
  const tc = useTranslations("console");
  const queryClient = useQueryClient();
  const [state, setState] = useState<OccurrenceFormState>(() => occurrenceForm(values));
  const [showProblems, setShowProblems] = useState(false);
  const problems = occurrenceProblems(state, values);
  const problem = (field: string) => (showProblems ? (problems.find((p) => p.field === field)?.code ?? null) : null);
  const set = (patch: Partial<OccurrenceFormState>) => setState((s) => ({ ...s, ...patch }));

  const save = useMutation({
    mutationFn: async () => updateOccurrence(eventId, occurrenceId, buildOccurrenceBody(state, values)),
    onSuccess: async () => {
      toast.success(t("occurrence.saved"));
      await queryClient.invalidateQueries({ queryKey: ck.all });
      onClose();
    },
  });

  function submit() {
    setShowProblems(true);
    if (problems.length > 0) return;
    if (Object.keys(buildOccurrenceBody(state, values)).length === 0) {
      onClose();
      return;
    }
    save.mutate();
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !save.isPending && onClose()}>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-lg" data-testid="occurrence-form">
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <DialogHeader>
            <DialogTitle>{t("occurrence.title")}</DialogTitle>
            <DialogDescription>{t("occurrence.help")}</DialogDescription>
          </DialogHeader>
          <FormField label={t("fields.title")} error={problem("title") ? tp(problem("title")!) : null}>
            {(props) => <Input {...props} value={state.title} maxLength={TITLE_MAX + 50} onChange={(e) => set({ title: e.target.value })} data-testid="occ-title" />}
          </FormField>
          <FormField label={t("fields.location")} error={problem("location") ? tp(problem("location")!) : null}>
            {(props) => <Input {...props} value={state.location} maxLength={LOCATION_MAX + 50} onChange={(e) => set({ location: e.target.value })} data-testid="occ-location" />}
          </FormField>
          {state.allDay ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label={t("fields.startDate")} error={problem("start") ? tp(problem("start")!) : null}>
                {(props) => <Input {...props} type="date" value={state.startDate} onChange={(e) => set({ startDate: e.target.value })} data-testid="occ-start" />}
              </FormField>
              <FormField label={t("fields.endDate")} error={problem("end") ? tp(problem("end")!) : null}>
                {(props) => <Input {...props} type="date" value={state.endDate} onChange={(e) => set({ endDate: e.target.value })} data-testid="occ-end" />}
              </FormField>
            </div>
          ) : (
            <div className="grid gap-3 sm:grid-cols-2">
              <FormField label={t("fields.start")} error={problem("start") ? tp(problem("start")!) : null}>
                {(props) => <Input {...props} type="datetime-local" value={state.startLocal} onChange={(e) => set({ startLocal: e.target.value })} data-testid="occ-start" />}
              </FormField>
              <FormField label={t("fields.end")} error={problem("end") ? tp(problem("end")!) : null}>
                {(props) => <Input {...props} type="datetime-local" value={state.endLocal} onChange={(e) => set({ endLocal: e.target.value })} data-testid="occ-end" />}
              </FormField>
            </div>
          )}
          {!state.allDay ? <p className="text-sm text-muted-foreground">{t("fields.inZone", { zone: state.timeZone })}</p> : null}
          <FormField label={t("fields.description")} error={problem("description") ? tp(problem("description")!) : null}>
            {(props) => <Textarea {...props} value={state.description} maxLength={DESCRIPTION_MAX + 200} rows={3} onChange={(e) => set({ description: e.target.value })} />}
          </FormField>
          <p className="rounded-xl bg-muted/60 p-3 text-sm">{t("occurrence.notice")}</p>
          {save.isError ? (
            <p role="alert" className="rounded-xl bg-destructive/10 p-3 text-sm font-medium text-destructive" data-testid="occ-error">
              {errorMessage(save.error, t("saveFailed"))}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={save.isPending}>
              {tc("cancel")}
            </Button>
            <Button type="submit" disabled={save.isPending} data-testid="occ-save">
              {save.isPending ? <Loader2Icon className="size-4 animate-spin" aria-hidden /> : null}
              {save.isPending ? tc("saving") : t("occurrence.save")}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
