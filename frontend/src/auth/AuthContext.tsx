import { createContext, useCallback, useContext, useEffect, useMemo, type ReactNode } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { client, setUnauthorizedHandler, unwrap, type Schemas, type User } from "../api/client";

interface AuthState {
  user: User | null;
  /** True while the initial session check is running. */
  loading: boolean;
  login: (body: Schemas["LoginRequest"]) => Promise<void>;
  register: (body: Schemas["RegisterRequest"]) => Promise<void>;
  /** Signs in to the shared demo account, no password needed. */
  tryDemo: () => Promise<void>;
  logout: () => Promise<void>;
  /** Ends the user's sessions on every device, not just this one. */
  logoutEverywhere: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);
const meKey = ["me"] as const;

/**
 * The session token is in an HttpOnly cookie the app can't see, so "am I signed in?" is answered
 * by asking the server: `/api/auth/me` returns the user, or 401 when there's no valid session.
 */
async function fetchCurrentUser(): Promise<User | null> {
  const result = await client.GET("/api/auth/me");
  if (result.response.status === 401) return null;
  return unwrap(Promise.resolve(result));
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();

  const me = useQuery({ queryKey: meKey, queryFn: fetchCurrentUser, retry: false, staleTime: Infinity });

  /** Forget the user and every cached todo, so nothing leaks to the next person to sign in. */
  const signedOut = useCallback(() => {
    queryClient.removeQueries({ queryKey: ["todos"] });
    queryClient.setQueryData(meKey, null);
  }, [queryClient]);

  // A 401 from any API call means the session expired.
  useEffect(() => setUnauthorizedHandler(signedOut), [signedOut]);

  const value = useMemo<AuthState>(
    () => ({
      user: me.data ?? null,
      loading: me.isPending,
      login: async (body) => {
        queryClient.setQueryData(meKey, await unwrap(client.POST("/api/auth/login", { body })));
      },
      register: async (body) => {
        queryClient.setQueryData(meKey, await unwrap(client.POST("/api/auth/register", { body })));
      },
      tryDemo: async () => {
        queryClient.setQueryData(meKey, await unwrap(client.POST("/api/auth/demo")));
      },
      logout: async () => {
        await client.POST("/api/auth/logout");
        signedOut();
      },
      logoutEverywhere: async () => {
        await client.POST("/api/auth/logout-all");
        signedOut();
      },
    }),
    [me.data, me.isPending, queryClient, signedOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside <AuthProvider>");
  return context;
}
