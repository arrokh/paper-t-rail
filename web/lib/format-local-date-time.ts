const FORMAT_OPTIONS: Omit<Intl.DateTimeFormatOptions, "timeZone"> = {
  dateStyle: "medium",
  timeStyle: "short",
};

export function formatLocalDateTime(value: string | null | undefined, timeZone: string): string {
  if (!value) return "Not recorded";

  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) return "Unknown time";

  return new Intl.DateTimeFormat("en-GB", { ...FORMAT_OPTIONS, timeZone }).format(timestamp);
}
