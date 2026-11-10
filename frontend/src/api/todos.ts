import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { client, unwrap, type Priority, type SortField, type SortOrder, type Todo, type TodoPage, type TodoRequest } from "./client";
import { todayIso } from "../lib/dates";

export interface TodoFilters {
  status: "all" | "active" | "completed";
  priority?: Priority;
  tag?: string;
  search: string;
  sort: SortField;
  order: SortOrder;
  page: number;
  pageSize: number;
}

export const defaultFilters: TodoFilters = {
  status: "all",
  search: "",
  sort: "createdAt",
  order: "desc",
  page: 1,
  pageSize: 10,
};

const keys = {
  all: ["todos"] as const,
  list: (f: TodoFilters) => ["todos", "list", f] as const,
  stats: ["todos", "stats"] as const,
  tags: ["todos", "tags"] as const,
};

export function useTodoList(filters: TodoFilters) {
  return useQuery({
    queryKey: keys.list(filters),
    queryFn: () =>
      unwrap(
        client.GET("/api/todos", {
          params: {
            query: {
              completed: filters.status === "all" ? undefined : filters.status === "completed",
              priority: filters.priority,
              tag: filters.tag,
              q: filters.search.trim() || undefined,
              sort: filters.sort,
              order: filters.order,
              page: filters.page,
              pageSize: filters.pageSize,
            },
          },
        }),
      ),
    // Keep showing the current page while the next one loads, so the list doesn't flash empty.
    placeholderData: keepPreviousData,
  });
}

export function useTodoStats() {
  return useQuery({
    queryKey: keys.stats,
    queryFn: () => unwrap(client.GET("/api/todos/stats", { params: { query: { today: todayIso() } } })),
  });
}

export function useTags() {
  return useQuery({
    queryKey: keys.tags,
    queryFn: () => unwrap(client.GET("/api/tags")),
  });
}

/** After any change, refetch lists, stats and tags. */
function useInvalidateTodos() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: keys.all });
}

export function useCreateTodo() {
  const invalidate = useInvalidateTodos();
  return useMutation({
    mutationFn: (body: TodoRequest) => unwrap(client.POST("/api/todos", { body })),
    onSuccess: invalidate,
  });
}

export function useUpdateTodo() {
  const invalidate = useInvalidateTodos();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: TodoRequest }) =>
      unwrap(client.PUT("/api/todos/{id}", { params: { path: { id } }, body })),
    onSuccess: invalidate,
  });
}

export function useDeleteTodo() {
  const invalidate = useInvalidateTodos();
  return useMutation({
    mutationFn: (id: number) => unwrap(client.DELETE("/api/todos/{id}", { params: { path: { id } } })),
    onSuccess: invalidate,
  });
}

/**
 * Toggling completion updates the cached list immediately (an optimistic update), then rolls
 * back if the server rejects the change.
 */
export function useToggleTodo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (todo: Todo) =>
      unwrap(
        client.PATCH("/api/todos/{id}/status", {
          params: { path: { id: todo.id } },
          body: { completed: !todo.completed },
        }),
      ),
    onMutate: async (todo) => {
      await queryClient.cancelQueries({ queryKey: keys.all });
      const snapshot = queryClient.getQueriesData<TodoPage>({ queryKey: ["todos", "list"] });
      queryClient.setQueriesData<TodoPage>({ queryKey: ["todos", "list"] }, (page) =>
        page && {
          ...page,
          items: page.items.map((t) => (t.id === todo.id ? { ...t, completed: !t.completed } : t)),
        },
      );
      return { snapshot };
    },
    onError: (_error, _todo, context) => {
      context?.snapshot.forEach(([key, data]) => queryClient.setQueryData<TodoPage>(key, data));
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: keys.all }),
  });
}
