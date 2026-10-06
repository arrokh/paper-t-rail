"use client";

import { formatLocalDateTime } from "@/lib/format-local-date-time";
import { useClientTimeZone } from "@/lib/use-client-time-zone";

type LocalDateTimeProps = {
  value: string | null;
  className?: string;
};

export function LocalDateTime({ value, className }: LocalDateTimeProps) {
  const timeZone = useClientTimeZone();
  const formatted = formatLocalDateTime(value, timeZone);

  if (!value || !Number.isFinite(Date.parse(value))) {
    return <span className={className}>{formatted}</span>;
  }

  return <time className={className} dateTime={value}>{formatted}</time>;
}
