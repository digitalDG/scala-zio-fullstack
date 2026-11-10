import type { Priority, SortField, SortOrder } from "../api/client";
import { defaultFilters, type TodoFilters } from "../api/todos";

const priorities: Priority[] = ["low", "medium", "high"];
const sortFields: SortField[] = ["createdAt", "dueDate", "priority", "title"];
const statuses: TodoFilters["status"][] = ["all", "active", "completed"];

function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | undefined {
  return allowed.find((a) => a === value);
}

/**
 * Filters live in the URL (`?status=active&tag=work`) so a filtered view can be bookmarked,
 * shared, and survives a page reload. Unknown or invalid values fall back to the defaults.
 */
export function filtersFromParams(params: URLSearchParams): TodoFilters {
  const page = Number(params.get("page"));
  return {
    status: oneOf(params.get("status"), statuses) ?? defaultFilters.status,
    priority: oneOf(params.get("priority"), priorities),
    tag: params.get("tag") || undefined,
    search: params.get("q") ?? "",
    sort: oneOf(params.get("sort"), sortFields) ?? defaultFilters.sort,
    order: oneOf<SortOrder>(params.get("order"), ["asc", "desc"]) ?? defaultFilters.order,
    page: Number.isInteger(page) && page > 0 ? page : 1,
    pageSize: defaultFilters.pageSize,
  };
}

/** Only non-default values are written, keeping URLs short. */
export function filtersToParams(f: TodoFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (f.status !== defaultFilters.status) params.set("status", f.status);
  if (f.priority) params.set("priority", f.priority);
  if (f.tag) params.set("tag", f.tag);
  if (f.search) params.set("q", f.search);
  if (f.sort !== defaultFilters.sort) params.set("sort", f.sort);
  if (f.order !== defaultFilters.order) params.set("order", f.order);
  if (f.page !== 1) params.set("page", String(f.page));
  return params;
}
