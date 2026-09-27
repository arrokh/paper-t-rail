"use client";

import { useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Field } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { useRecordHumanReview } from "@/features/analysis-runs/queries/analysis-run-queries";
import type {
  HumanReview,
  HumanReviewAction,
  HumanReviewStatus,
} from "@/features/analysis-runs/types";

const REVIEW_STATUSES: Array<{ value: HumanReviewStatus; label: string }> = [
  { value: "SUPPORTED", label: "Supported" },
  { value: "PARTIALLY_SUPPORTED", label: "Partially supported" },
  { value: "CONTRADICTED", label: "Contradicted" },
  { value: "INSUFFICIENT_EVIDENCE", label: "Insufficient evidence" },
  { value: "INACCESSIBLE", label: "Inaccessible" },
  { value: "UNRESOLVED", label: "Unresolved" },
  { value: "UNSUPPORTED_REFERENCE_TYPE", label: "Unsupported reference type" },
];

function actionLabel(review: HumanReview): string {
  if (review.action === "AGREE") return "Agreed with machine result";
  if (review.action === "DISAGREE") return "Disagreed with machine result";
  return `Human assessment: ${review.overrideStatus?.replaceAll("_", " ").toLowerCase() ?? "not recorded"}`;
}

export function HumanReviewPanel({
  analysisRunId,
  verificationId,
  machineStatus,
  reviews,
}: {
  analysisRunId: string;
  verificationId: string;
  machineStatus: HumanReviewStatus | null;
  reviews: HumanReview[];
}) {
  const [action, setAction] = useState<HumanReviewAction>("AGREE");
  const [overrideStatus, setOverrideStatus] = useState<HumanReviewStatus | "">("");
  const [note, setNote] = useState("");
  const mutation = useRecordHumanReview(analysisRunId);

  function submitReview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (action === "OVERRIDE" && !overrideStatus) return;

    mutation.mutate({
      verificationId,
      action,
      ...(action === "OVERRIDE" && overrideStatus ? { overrideStatus } : {}),
      ...(note ? { note } : {}),
    }, {
      onSuccess: () => {
        setAction("AGREE");
        setOverrideStatus("");
        setNote("");
      },
    });
  }

  return (
    <section className="space-y-3 border-t border-border pt-3" aria-labelledby={`human-review-heading-${verificationId}`}>
      <div className="space-y-1">
        <h4 id={`human-review-heading-${verificationId}`} className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Human review history</h4>
        <p className="m-0 text-xs text-muted-foreground">
          Machine result: {machineStatus?.replaceAll("_", " ").toLowerCase() ?? "not complete"}. Human assessments are separate and do not change it.
        </p>
      </div>

      {reviews.length === 0 ? (
        <p className="m-0 text-sm text-muted-foreground">No human reviews recorded.</p>
      ) : (
        <ol className="space-y-2" aria-label={`Human review history for verification ${verificationId}`}>
          {reviews.map((review) => (
            <li key={review.id} className="space-y-1 rounded-md border border-border bg-muted/20 p-3">
              <p className="m-0 text-sm font-medium">{actionLabel(review)}</p>
              <time className="block text-xs text-muted-foreground" dateTime={review.createdAt}>{review.createdAt}</time>
              {review.note && <p className="m-0 break-words text-sm leading-relaxed">{review.note}</p>}
            </li>
          ))}
        </ol>
      )}

      {machineStatus === null ? (
        <p className="m-0 text-xs text-muted-foreground">A Human Review can be added after the machine result is complete.</p>
      ) : (
        <form className="space-y-3 rounded-md border border-border bg-background p-3" onSubmit={submitReview}>
          <h5 className="m-0 text-sm font-medium">Add a human assessment</h5>
          <Field>
            <Label htmlFor={`review-action-${verificationId}`}>Review action</Label>
            <NativeSelect
              id={`review-action-${verificationId}`}
              className="w-full"
              value={action}
              disabled={mutation.isPending}
              onChange={(event) => {
                const nextAction = event.target.value as HumanReviewAction;
                setAction(nextAction);
                if (nextAction !== "OVERRIDE") setOverrideStatus("");
              }}
            >
              <NativeSelectOption value="AGREE">Agree with machine result</NativeSelectOption>
              <NativeSelectOption value="DISAGREE">Disagree with machine result</NativeSelectOption>
              <NativeSelectOption value="OVERRIDE">Record a human assessment</NativeSelectOption>
            </NativeSelect>
          </Field>

          {action === "OVERRIDE" && (
            <Field>
              <Label htmlFor={`review-status-${verificationId}`}>Human assessment status</Label>
              <NativeSelect
                id={`review-status-${verificationId}`}
                className="w-full"
                value={overrideStatus}
                required
                disabled={mutation.isPending}
                onChange={(event) => setOverrideStatus(event.target.value as HumanReviewStatus | "")}
              >
                <NativeSelectOption value="" disabled>Choose a status</NativeSelectOption>
                {REVIEW_STATUSES.map((status) => (
                  <NativeSelectOption key={status.value} value={status.value}>{status.label}</NativeSelectOption>
                ))}
              </NativeSelect>
            </Field>
          )}

          <Field>
            <Label htmlFor={`review-note-${verificationId}`}>Note <span className="font-normal text-muted-foreground">(optional)</span></Label>
            <Input
              id={`review-note-${verificationId}`}
              value={note}
              maxLength={2000}
              disabled={mutation.isPending}
              onChange={(event) => setNote(event.target.value)}
            />
          </Field>

          {mutation.isError && (
            <p className="m-0 text-sm text-destructive" role="alert">
              {mutation.error instanceof Error ? mutation.error.message : "The Human Review could not be saved."}
            </p>
          )}
          <Button type="submit" disabled={mutation.isPending || (action === "OVERRIDE" && !overrideStatus)}>
            {mutation.isPending ? "Saving review…" : "Record Human Review"}
          </Button>
        </form>
      )}
    </section>
  );
}
