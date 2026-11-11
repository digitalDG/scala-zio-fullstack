import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import type { Priority, SortField, Todo } from "../api/client";
import { useDeleteTodo, useTags, useTodoList, useTodoStats, useToggleTodo, type TodoFilters } from "../api/todos";
import { useAuth } from "../auth/AuthContext";
import { TodoDialog } from "../components/TodoDialog";
import { TodoItem } from "../components/TodoItem";
import { UserMenu } from "../components/UserMenu";
import { useDemoInfo } from "../api/demo";
import { Alert, Button, cx, Input, Select, Spinner } from "../components/ui";
import { filtersFromParams, filtersToParams } from "../lib/filters";

export function TodosPage() {
  const { user, logout, logoutEverywhere } = useAuth();
  const [params, setParams] = useSearchParams();
  const filters = filtersFromParams(params);

  /** Any filter change except paging itself goes back to page 1. */
  const setFilters = (patch: Partial<TodoFilters>) =>
    setParams(filtersToParams({ ...filters, page: 1, ...patch }), { replace: true });

  const list = useTodoList(filters);
  const toggle = useToggleTodo();
  const remove = useDeleteTodo();
  const [editing, setEditing] = useState<Todo | null | undefined>(undefined);

  function confirmDelete(todo: Todo) {
    if (window.confirm(`Delete "${todo.title}"?`)) remove.mutate(todo.id);
  }

  const filtered = filters.status !== "all" || !!filters.priority || !!filters.tag || !!filters.search;

  return (
    <div className="min-h-full">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-4xl items-center justify-between px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">✓</span>
            <span className="font-semibold text-slate-900">Todo</span>
          </div>
          {user && <UserMenu user={user} onSignOut={() => void logout()} onSignOutEverywhere={() => void logoutEverywhere()} />}
        </div>
      </header>

      {user?.demo && <DemoBanner />}

      <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Your todos</h1>
            <p className="mt-1 text-sm text-slate-600">Plan it, tag it, get it done.</p>
          </div>
          <Button onClick={() => setEditing(null)}>+ New todo</Button>
        </div>

        <StatsBar />

        <Toolbar filters={filters} onChange={setFilters} />

        <section className="overflow-hidden rounded-xl bg-white shadow-sm ring-1 ring-slate-200">
          {list.isPending ? (
            <div className="flex justify-center py-16">
              <Spinner />
            </div>
          ) : list.isError ? (
            <div className="p-4">
              <Alert>Couldn't load your todos: {list.error.message}</Alert>
            </div>
          ) : list.data.items.length === 0 ? (
            <EmptyState filtered={filtered} onClear={() => setParams({}, { replace: true })} onCreate={() => setEditing(null)} />
          ) : (
            <ul className={cx("divide-y divide-slate-100", list.isPlaceholderData && "opacity-60")}>
              {list.data.items.map((todo) => (
                <TodoItem
                  key={todo.id}
                  todo={todo}
                  onToggle={(t) => toggle.mutate(t)}
                  onEdit={setEditing}
                  onDelete={confirmDelete}
                  onTagClick={(tag) => setFilters({ tag })}
                />
              ))}
            </ul>
          )}
        </section>

        {list.data && list.data.totalPages > 1 && (
          <Pagination
            page={list.data.page}
            totalPages={list.data.totalPages}
            total={list.data.total}
            onPage={(page) => setParams(filtersToParams({ ...filters, page }), { replace: true })}
          />
        )}
      </main>

      {editing !== undefined && <TodoDialog todo={editing} onClose={() => setEditing(undefined)} />}
    </div>
  );
}

function DemoBanner() {
  const { data: demo } = useDemoInfo();
  return (
    <div className="border-b border-amber-200 bg-amber-50">
      <p className="mx-auto max-w-4xl px-4 py-2 text-sm text-amber-900">
        You're exploring the <span className="font-semibold">shared demo account</span>. Other visitors see the same todos, and
        everything resets {demo ? `every ${demo.resetIntervalHours} hours` : "regularly"}.
      </p>
    </div>
  );
}

function StatsBar() {
  const { data } = useTodoStats();
  const stats = [
    { label: "Total", value: data?.total, tone: "text-slate-900" },
    { label: "Active", value: data?.active, tone: "text-indigo-600" },
    { label: "Done", value: data?.completed, tone: "text-emerald-600" },
    { label: "Overdue", value: data?.overdue, tone: data?.overdue ? "text-red-600" : "text-slate-900" },
  ];
  return (
    <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      {stats.map((s) => (
        <div key={s.label} className="rounded-xl bg-white px-4 py-3 shadow-sm ring-1 ring-slate-200">
          <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{s.label}</dt>
          <dd className={cx("mt-1 text-2xl font-semibold tabular-nums", s.tone)}>{s.value ?? "–"}</dd>
        </div>
      ))}
    </dl>
  );
}

const sortLabels: Record<SortField, string> = {
  createdAt: "Date added",
  dueDate: "Due date",
  priority: "Priority",
  title: "Title",
};

function Toolbar({ filters, onChange }: { filters: TodoFilters; onChange: (patch: Partial<TodoFilters>) => void }) {
  const { data: tags = [] } = useTags();

  // Debounce typing so each keystroke doesn't fire a request.
  const [search, setSearch] = useState(filters.search);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;
  useEffect(() => setSearch(filters.search), [filters.search]);
  useEffect(() => {
    if (search === filters.search) return;
    const timer = setTimeout(() => onChangeRef.current({ search }), 300);
    return () => clearTimeout(timer);
  }, [search, filters.search]);

  return (
    <div className="flex flex-wrap items-center gap-3">
      <Input
        type="search"
        placeholder="Search titles…"
        aria-label="Search titles"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full sm:w-auto sm:min-w-[12rem] sm:flex-1"
      />
      <div role="group" aria-label="Status" className="inline-flex rounded-md shadow-sm">
          {(["all", "active", "completed"] as const).map((status, i) => (
            <button
              key={status}
              type="button"
              aria-pressed={filters.status === status}
              onClick={() => onChange({ status })}
              className={cx(
                "px-3 py-2 text-sm font-medium capitalize ring-1 ring-inset ring-slate-300",
                i === 0 && "rounded-l-md",
                i === 2 && "rounded-r-md",
                i > 0 && "-ml-px",
                filters.status === status ? "z-10 bg-indigo-600 text-white ring-indigo-600" : "bg-white text-slate-700 hover:bg-slate-50",
              )}
            >
              {status}
            </button>
          ))}
      </div>
        <Select
          aria-label="Priority"
          value={filters.priority ?? ""}
          onChange={(e) => onChange({ priority: (e.target.value || undefined) as Priority | undefined })}
          className="w-auto"
        >
          <option value="">Any priority</option>
          <option value="high">High</option>
          <option value="medium">Medium</option>
          <option value="low">Low</option>
        </Select>
        <Select aria-label="Tag" value={filters.tag ?? ""} onChange={(e) => onChange({ tag: e.target.value || undefined })} className="w-auto">
          <option value="">Any tag</option>
          {filters.tag && !tags.includes(filters.tag) && <option value={filters.tag}>#{filters.tag}</option>}
          {tags.map((tag) => (
            <option key={tag} value={tag}>
              #{tag}
            </option>
          ))}
        </Select>
        <div className="flex">
          <Select
            aria-label="Sort by"
            value={filters.sort}
            onChange={(e) => onChange({ sort: e.target.value as SortField })}
            className="w-auto rounded-r-none"
          >
            {Object.entries(sortLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
          <button
            type="button"
            onClick={() => onChange({ order: filters.order === "asc" ? "desc" : "asc" })}
            aria-label={filters.order === "asc" ? "Ascending; switch to descending" : "Descending; switch to ascending"}
            className="-ml-px rounded-r-md bg-white px-3 text-sm text-slate-700 shadow-sm ring-1 ring-inset ring-slate-300 hover:bg-slate-50"
          >
            {filters.order === "asc" ? "↑" : "↓"}
          </button>
        </div>
    </div>
  );
}

function EmptyState({ filtered, onClear, onCreate }: { filtered: boolean; onClear: () => void; onCreate: () => void }) {
  return (
    <div className="px-6 py-16 text-center">
      <p className="text-sm font-medium text-slate-900">{filtered ? "No todos match these filters" : "No todos yet"}</p>
      <p className="mt-1 text-sm text-slate-500">
        {filtered ? "Try a different search or clear the filters." : "Add your first one to get started."}
      </p>
      <div className="mt-4">
        {filtered ? (
          <Button variant="secondary" onClick={onClear}>
            Clear filters
          </Button>
        ) : (
          <Button onClick={onCreate}>+ New todo</Button>
        )}
      </div>
    </div>
  );
}

function Pagination({ page, totalPages, total, onPage }: { page: number; totalPages: number; total: number; onPage: (page: number) => void }) {
  return (
    <nav className="flex items-center justify-between text-sm text-slate-600" aria-label="Pagination">
      <span>
        Page {page} of {totalPages} · {total} todos
      </span>
      <div className="flex gap-2">
        <Button variant="secondary" disabled={page <= 1} onClick={() => onPage(page - 1)}>
          Previous
        </Button>
        <Button variant="secondary" disabled={page >= totalPages} onClick={() => onPage(page + 1)}>
          Next
        </Button>
      </div>
    </nav>
  );
}
