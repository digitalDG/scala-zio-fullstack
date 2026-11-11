import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./AuthContext";

const user = { id: 1, username: "alice", email: "alice@example.com", createdAt: "2026-01-01T00:00:00Z", demo: false };

function json(status: number, body?: unknown) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function Probe() {
  const { user, loading, login } = useAuth();
  if (loading) return <p>loading</p>;
  return user ? (
    <p>signed in as {user.username}</p>
  ) : (
    <button onClick={() => login({ username: "alice", password: "password123" })}>sign in</button>
  );
}

function renderWithAuth() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Probe />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("AuthProvider", () => {
  it("treats a 401 from /api/auth/me as signed out", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json(401, { code: "unauthorized", message: "Not signed in", fieldErrors: [] })));
    renderWithAuth();
    expect(await screen.findByRole("button", { name: "sign in" })).toBeInTheDocument();
  });

  it("signs in without storing any credentials in the browser", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (request: Request) =>
        new URL(request.url).pathname === "/api/auth/login"
          ? json(200, user)
          : json(401, { code: "unauthorized", message: "Not signed in", fieldErrors: [] }),
      ),
    );
    renderWithAuth();

    await userEvent.click(await screen.findByRole("button", { name: "sign in" }));

    expect(await screen.findByText("signed in as alice")).toBeInTheDocument();
    // The session is an HttpOnly cookie set by the server; the app itself keeps nothing.
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });
});
