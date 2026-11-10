import type { Priority, Todo } from "../api/client";
import { formatDue, isOverdue } from "../lib/dates";
import { cx } from "./ui";

const priorityStyles: Record<Priority, string> = {
  high: "bg-red-50 text-red-700 ring-red-200",
  medium: "bg-amber-50 text-amber-700 ring-amber-200",
  low: "bg-slate-50 text-slate-600 ring-slate-200",
};

interface Props {
  todo: Todo;
  onToggle: (todo: Todo) => void;
  onEdit: (todo: Todo) => void;
  onDelete: (todo: Todo) => void;
  onTagClick: (tag: string) => void;
}

export function TodoItem({ todo, onToggle, onEdit, onDelete, onTagClick }: Props) {
  const overdue = isOverdue(todo.dueDate, todo.completed);

  return (
    <li className="group flex items-start gap-3 px-4 py-3 hover:bg-slate-50/70">
      <input
        type="checkbox"
        checked={todo.completed}
        onChange={() => onToggle(todo)}
        aria-label={`Mark "${todo.title}" as ${todo.completed ? "not done" : "done"}`}
        className="mt-0.5 h-4 w-4 shrink-0 cursor-pointer accent-indigo-600"
      />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={cx("text-sm font-medium", todo.completed ? "text-slate-400 line-through" : "text-slate-900")}>
            {todo.title}
          </span>
          <span className={cx("rounded-full px-2 py-0.5 text-xs font-medium capitalize ring-1 ring-inset", priorityStyles[todo.priority])}>
            {todo.priority}
          </span>
        </div>
        {todo.description && <p className="mt-0.5 line-clamp-2 text-sm text-slate-500">{todo.description}</p>}
        {(todo.dueDate || todo.tags.length > 0) && (
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5 text-xs">
            {todo.dueDate && (
              <span className={cx("font-medium", overdue ? "text-red-600" : "text-slate-500")}>
                {overdue ? "Overdue · " : "Due "}
                {formatDue(todo.dueDate)}
              </span>
            )}
            {todo.tags.map((tag) => (
              <button
                key={tag}
                type="button"
                onClick={() => onTagClick(tag)}
                className="rounded bg-indigo-50 px-1.5 py-0.5 font-medium text-indigo-700 hover:bg-indigo-100"
              >
                #{tag}
              </button>
            ))}
          </div>
        )}
      </div>
      <div className="flex shrink-0 gap-1 opacity-100 transition-opacity sm:opacity-0 sm:group-hover:opacity-100 sm:group-focus-within:opacity-100">
        <button
          type="button"
          onClick={() => onEdit(todo)}
          className="rounded px-2 py-1 text-xs font-medium text-slate-600 hover:bg-slate-200"
          aria-label={`Edit "${todo.title}"`}
        >
          Edit
        </button>
        <button
          type="button"
          onClick={() => onDelete(todo)}
          className="rounded px-2 py-1 text-xs font-medium text-red-600 hover:bg-red-50"
          aria-label={`Delete "${todo.title}"`}
        >
          Delete
        </button>
      </div>
    </li>
  );
}
