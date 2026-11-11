import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import type { User } from "../api/client";
import { UserMenu } from "./UserMenu";

const alice: User = { id: 1, username: "alice", email: "alice@example.com", createdAt: "2026-01-01T00:00:00Z", demo: false };
const demo: User = { ...alice, id: 2, username: "demo", demo: true };

function open(user: User, handlers = { onSignOut: vi.fn(), onSignOutEverywhere: vi.fn() }) {
  render(<UserMenu user={user} {...handlers} />);
  return { handlers, toggle: screen.getByRole("button", { name: /account menu/i }) };
}

describe("UserMenu", () => {
  it("keeps account actions hidden until opened", async () => {
    const { toggle } = open(alice);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await userEvent.click(toggle);
    expect(screen.getByRole("menuitem", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Sign out of all devices" })).toBeInTheDocument();
  });

  it("signs out and closes", async () => {
    const { toggle, handlers } = open(alice);
    await userEvent.click(toggle);
    await userEvent.click(screen.getByRole("menuitem", { name: "Sign out" }));
    expect(handlers.onSignOut).toHaveBeenCalled();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("closes on Escape", async () => {
    const { toggle } = open(alice);
    await userEvent.click(toggle);
    await userEvent.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("doesn't offer the shared demo account a way to sign out other visitors", async () => {
    const { toggle } = open(demo);
    await userEvent.click(toggle);
    expect(screen.getByText("Demo")).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Sign out of all devices" })).not.toBeInTheDocument();
  });
});
