/** Today's date in the user's local time zone, as `YYYY-MM-DD`. */
export function todayIso(now: Date = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, "0");
  const d = String(now.getDate()).padStart(2, "0");
  return `${y}-${m}-${d}`;
}

/** ISO dates compare correctly as strings, so no date parsing is needed. */
export function isOverdue(dueDate: string | undefined, completed: boolean, today = todayIso()): boolean {
  return !completed && dueDate !== undefined && dueDate < today;
}

export function formatDue(dueDate: string, today = todayIso()): string {
  if (dueDate === today) return "Today";
  const [y, m, d] = dueDate.split("-").map(Number);
  const date = new Date(y, m - 1, d);
  const sameYear = y === Number(today.slice(0, 4));
  return date.toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    ...(sameYear ? {} : { year: "numeric" }),
  });
}
