/** Missing or non-finite measurements must never be displayed as measured zero. */
export function formatMetric(value: unknown, digits: number, unit = ""): string {
  return typeof value === "number" && Number.isFinite(value)
    ? `${value.toFixed(digits)}${unit}`
    : "N/A";
}
