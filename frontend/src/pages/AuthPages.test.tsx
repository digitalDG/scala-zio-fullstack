import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../auth/AuthContext";
import { LoginPage } from "./AuthPages";

const demoUser = { id: 7, username: "demo", email: "demo@example.com", createdAt: "2026-01-01T00:00:00Z", demo: true };

function json(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** A fake API: not signed in, with the demo either enabled or not. */
function fakeApi(demoEnabled: boolean) {
  return vi.fn(async (request: Request) => {
    const path = new URL(request.url).pathname;
    if (path === "/api/auth/demo" && request.method === "GET") return json(200, { enabled: demoEnabled, resetIntervalHours: 6 });
    if (path === "/api/auth/demo" && request.method === "POST") return json(200, demoUser);
    return json(401, { code: "unauthorized", message: "Not signed in", fieldErrors: [] });
  });
}

function renderLogin() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={["/login"]}>
        <AuthProvider>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/" element={<p>todos page</p>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("LoginPage", () => {
  it("offers the demo when it's enabled, and signs straight in", async () => {
    const api = fakeApi(true);
    vi.stubGlobal("fetch", api);
    renderLogin();

    await userEvent.click(await screen.findByRole("button", { name: /try the demo/i }));

    expect(await screen.findByText("todos page")).toBeInTheDocument();
    expect(api.mock.calls.some(([r]) => r.method === "POST" && new URL(r.url).pathname === "/api/auth/demo")).toBe(true);
  });

  it("hides the demo option when it's disabled", async () => {
    vi.stubGlobal("fetch", fakeApi(false));
    renderLogin();

    expect(await screen.findByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /try the demo/i })).not.toBeInTheDocument();
  });
});
