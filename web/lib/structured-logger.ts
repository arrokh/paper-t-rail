export type LogLevel = "INFO" | "WARN" | "ERROR";

export type StructuredLogEntry = {
  level: LogLevel;
  event: string;
  requestId: string;
  httpMethod: string;
  httpPath: string;
  httpStatus: number;
  durationMs: number;
  errorType?: string;
};

export function logStructured(entry: StructuredLogEntry): void {
  const { level, event, ...fields } = entry;
  const line = JSON.stringify({
    "@timestamp": new Date().toISOString(),
    log: { level, logger: "paper-t-rail.web.api-proxy" },
    service: { name: "paper-t-rail-web" },
    message: event === "api_proxy_request_completed" ? "API proxy request completed" : "API proxy request failed",
    ecs: { version: "8.11" },
    eventName: event,
    ...fields,
  });
  if (level === "ERROR") {
    console.error(line);
  } else {
    console.info(line);
  }
}
