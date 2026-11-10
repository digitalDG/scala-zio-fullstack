import { describe, expect, it } from "vitest";
import { defaultFilters } from "../api/todos";
import { formatDue, isOverdue, todayIso } from "./dates";
import { filtersFromParams, filtersToParams } from "./filters";

describe("dates", () => {
  it("formats today's local date as YYYY-MM-DD", () => {
    expect(todayIso(new Date(2026, 0, 5))).toBe("2026-01-05");
  });

  it("treats only open todos with a past due date as overdue", () => {
    expect(isOverdue("2026-01-04", false, "2026-01-05")).toBe(true);
    expect(isOverdue("2026-01-05", false, "2026-01-05")).toBe(false);
    expect(isOverdue("2026-01-04", true, "2026-01-05")).toBe(false);
    expect(isOverdue(undefined, false, "2026-01-05")).toBe(false);
  });

  it("labels today's date as Today", () => {
    expect(formatDue("2026-01-05", "2026-01-05")).toBe("Today");
  });
});

describe("filters in the URL", () => {
  it("round-trip through query parameters", () => {
    const filters = { ...defaultFilters, status: "active" as const, priority: "high" as const, tag: "work", search: "milk", sort: "dueDate" as const, order: "asc" as const, page: 3 };
    expect(filtersFromParams(filtersToParams(filters))).toEqual(filters);
  });

  it("leave defaults out of the URL", () => {
    expect(filtersToParams(defaultFilters).toString()).toBe("");
  });

  it("ignore invalid values instead of sending them to the API", () => {
    const parsed = filtersFromParams(new URLSearchParams("status=bogus&priority=urgent&sort=nope&page=-2"));
    expect(parsed).toEqual(defaultFilters);
  });
});
