import { useEffect, useRef, useState } from "react";
import type { User } from "../api/client";

interface Props {
  user: User;
  onSignOut: () => void;
  onSignOutEverywhere: () => void;
}

/** The signed-in user's name, opening a small menu of account actions. */
export function UserMenu({ user, onSignOut, onSignOutEverywhere }: Props) {
  const [open, setOpen] = useState(false);
  const container = useRef<HTMLDivElement>(null);

  // Close on a click outside the menu or on Escape.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent) => {
      if (!container.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKeyDown = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  const item = "block w-full px-3 py-2 text-left text-sm text-slate-700 hover:bg-slate-50";

  return (
    <div ref={container} className="relative">
      <button
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Account menu for ${user.username}`}
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm hover:bg-slate-100"
      >
        <span className="flex h-7 w-7 items-center justify-center rounded-full bg-indigo-100 text-xs font-semibold uppercase text-indigo-700">
          {user.username.slice(0, 1)}
        </span>
        <span className="hidden font-medium text-slate-900 sm:inline">{user.username}</span>
        <span aria-hidden className="text-xs text-slate-400">
          ▾
        </span>
      </button>

      {open && (
        <div role="menu" className="absolute right-0 z-30 mt-1 w-60 overflow-hidden rounded-lg bg-white py-1 shadow-lg ring-1 ring-slate-200">
          <div className="border-b border-slate-100 px-3 py-2 text-xs text-slate-500">
            Signed in as <span className="font-medium text-slate-900">{user.username}</span>
            {user.demo && <span className="ml-1 rounded bg-amber-100 px-1.5 py-0.5 font-medium text-amber-800">Demo</span>}
          </div>
          <button type="button" role="menuitem" className={item} onClick={() => { setOpen(false); onSignOut(); }}>
            Sign out
          </button>
          {/* The shared demo account can't end other visitors' sessions (the API refuses it too). */}
          {!user.demo && (
            <button
              type="button"
              role="menuitem"
              className={item}
              onClick={() => {
                setOpen(false);
                if (window.confirm("Sign out on all your devices?")) onSignOutEverywhere();
              }}
            >
              Sign out of all devices
            </button>
          )}
        </div>
      )}
    </div>
  );
}
