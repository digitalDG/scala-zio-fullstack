import { useQuery } from "@tanstack/react-query";
import { client, unwrap } from "./client";

/** Whether the shared demo account is available, and how often it resets. Rarely changes. */
export function useDemoInfo() {
  return useQuery({
    queryKey: ["demo-info"],
    queryFn: () => unwrap(client.GET("/api/auth/demo")),
    staleTime: Infinity,
  });
}
