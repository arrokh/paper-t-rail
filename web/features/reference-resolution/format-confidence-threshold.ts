export function formatConfidenceThreshold(value: number | null): string {
  if (value === null) return "Not configured";
  return String(value);
}
