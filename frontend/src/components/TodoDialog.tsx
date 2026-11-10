import { useEffect, useRef, useState, type FormEvent } from "react";
import { ApiError, type Priority, type Todo, type TodoRequest } from "../api/client";
import { useCreateTodo, useUpdateTodo } from "../api/todos";
import { Alert, Button, Field, Input, Select } from "./ui";

interface Props {
  /** The todo being edited, or `null` to create a new one. */
  todo: Todo | null;
  onClose: () => void;
}

function toForm(todo: Todo | null) {
  return {
    title: todo?.title ?? "",
    description: todo?.description ?? "",
    priority: todo?.priority ?? ("medium" as Priority),
    dueDate: todo?.dueDate ?? "",
    tags: todo?.tags.join(", ") ?? "",
  };
}

export function TodoDialog({ todo, onClose }: Props) {
  const [form, setForm] = useState(() => toForm(todo));
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const create = useCreateTodo();
  const update = useUpdateTodo();
  const busy = create.isPending || update.isPending;
  const titleRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    titleRef.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    const body: TodoRequest = {
      title: form.title,
      description: form.description.trim() || undefined,
      priority: form.priority,
      dueDate: form.dueDate || undefined,
      tags: form.tags.split(",").map((t) => t.trim()).filter(Boolean),
      completed: todo?.completed ?? false,
    };
    try {
      if (todo) await update.mutateAsync({ id: todo.id, body });
      else await create.mutateAsync(body);
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.body.code === "validation_failed") setFieldErrors(e.fieldErrors);
      else setError(e instanceof Error ? e.message : "Could not save the todo");
    }
  }

  const set =
    (key: keyof typeof form) =>
    (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      setForm((f) => ({ ...f, [key]: e.target.value }));

  return (
    <div className="fixed inset-0 z-20 flex items-end justify-center bg-slate-900/40 p-4 sm:items-center" onMouseDown={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="todo-dialog-title"
        className="w-full max-w-lg rounded-xl bg-white shadow-xl"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <form onSubmit={onSubmit} noValidate>
          <div className="border-b border-slate-200 px-5 py-4">
            <h2 id="todo-dialog-title" className="text-base font-semibold text-slate-900">
              {todo ? "Edit todo" : "New todo"}
            </h2>
          </div>
          <div className="space-y-4 px-5 py-4">
            {error && <Alert>{error}</Alert>}
            <Field label="Title" htmlFor="title" error={fieldErrors.title}>
              <Input id="title" ref={titleRef} value={form.title} onChange={set("title")} invalid={!!fieldErrors.title} />
            </Field>
            <Field label="Notes" htmlFor="description" error={fieldErrors.description}>
              <textarea
                id="description"
                rows={3}
                value={form.description}
                onChange={set("description")}
                className="block w-full rounded-md border-0 px-3 py-2 text-sm text-slate-900 shadow-sm ring-1 ring-inset ring-slate-300 placeholder:text-slate-400 focus:ring-2 focus:ring-inset focus:ring-indigo-600"
              />
            </Field>
            <div className="grid grid-cols-2 gap-4">
              <Field label="Priority" htmlFor="priority">
                <Select id="priority" value={form.priority} onChange={set("priority")}>
                  <option value="low">Low</option>
                  <option value="medium">Medium</option>
                  <option value="high">High</option>
                </Select>
              </Field>
              <Field label="Due date" htmlFor="dueDate">
                <Input id="dueDate" type="date" value={form.dueDate} onChange={set("dueDate")} />
              </Field>
            </div>
            <Field label="Tags" htmlFor="tags" error={fieldErrors.tags} hint="Separate with commas, e.g. work, urgent">
              <Input id="tags" value={form.tags} onChange={set("tags")} invalid={!!fieldErrors.tags} />
            </Field>
          </div>
          <div className="flex justify-end gap-2 rounded-b-xl bg-slate-50 px-5 py-3">
            <Button variant="secondary" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy ? "Saving…" : todo ? "Save changes" : "Add todo"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}
