import { useSyncExternalStore } from "react";

function getClientTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
}

function subscribe(): () => void {
  return () => {};
}

export function useClientTimeZone(): string {
  return useSyncExternalStore(subscribe, getClientTimeZone, () => "UTC");
}
