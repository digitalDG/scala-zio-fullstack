import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { Todo } from "../api/client";
import { TodoDialog } from "./TodoDialog";
import { TodoItem } from "./TodoItem";

const todo: Todo = {
  id: 1,
  title: "Pay rent",
  completed: false,
  priority: "high",
  dueDate: "2000-01-01",
  tags: ["home"],
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};

function withQueryClient(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("TodoItem", () => {
  it("shows priority, tags and an overdue warning", () => {
    render(<TodoItem todo={todo} onToggle={vi.fn()} onEdit={vi.fn()} onDelete={vi.fn()} onTagClick={vi.fn()} />);
    expect(screen.getByText("Pay rent")).toBeInTheDocument();
    expect(screen.getByText("high")).toBeInTheDocument();
    expect(screen.getByText("#home")).toBeInTheDocument();
    expect(screen.getByText(/Overdue/)).toBeInTheDocument();
  });

  it("reports toggles and tag clicks", async () => {
    const onToggle = vi.fn();
    const onTagClick = vi.fn();
    render(<TodoItem todo={todo} onToggle={onToggle} onEdit={vi.fn()} onDelete={vi.fn()} onTagClick={onTagClick} />);
    await userEvent.click(screen.getByRole("checkbox"));
    await userEvent.click(screen.getByText("#home"));
    expect(onToggle).toHaveBeenCalledWith(todo);
    expect(onTagClick).toHaveBeenCalledWith("home");
  });
});

describe("TodoDialog", () => {
  it("shows the server's field errors next to the matching inputs", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(400, {
          code: "validation_failed",
          message: "The request contains invalid fields",
          fieldErrors: [{ field: "title", message: "must be 1-200 characters" }],
        }),
      ),
    );
    const onClose = vi.fn();
    withQueryClient(<TodoDialog todo={null} onClose={onClose} />);

    await userEvent.click(screen.getByRole("button", { name: "Add todo" }));

    expect(await screen.findByText("must be 1-200 characters")).toBeInTheDocument();
    expect(screen.getByLabelText("Title")).toHaveAttribute("aria-invalid", "true");
    expect(onClose).not.toHaveBeenCalled();
  });

  it("sends the form as a typed request and closes on success", async () => {
    const fetchMock = vi.fn(async (_request: Request) => jsonResponse(201, { ...todo, id: 2, title: "Buy milk" }));
    vi.stubGlobal("fetch", fetchMock);
    const onClose = vi.fn();
    withQueryClient(<TodoDialog todo={null} onClose={onClose} />);

    await userEvent.type(screen.getByLabelText("Title"), "Buy milk");
    await userEvent.type(screen.getByLabelText("Tags"), "home, errands");
    await userEvent.click(screen.getByRole("button", { name: "Add todo" }));

    await vi.waitFor(() => expect(onClose).toHaveBeenCalled());
    const request = fetchMock.mock.calls[0][0];
    expect(request.method).toBe("POST");
    expect(new URL(request.url).pathname).toBe("/api/todos");
    expect(await request.json()).toEqual({
      title: "Buy milk",
      priority: "medium",
      tags: ["home", "errands"],
      completed: false,
    });
  });
});
