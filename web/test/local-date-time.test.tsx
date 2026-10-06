import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { LocalDateTime } from "@/components/local-date-time";
import { formatLocalDateTime } from "@/lib/format-local-date-time";

vi.mock("@/lib/use-client-time-zone", () => ({
  useClientTimeZone: () => "Asia/Jakarta",
}));

describe("local date-time presentation", () => {
  it("uses the shared 24-hour date and time format in the selected timezone", () => {
    const timestamp = "2026-10-05T11:47:00Z";
    render(<LocalDateTime value={timestamp} />);

    const time = screen.getByText("5 Oct 2026, 18:47");
    expect(time.tagName).toBe("TIME");
    expect(time.getAttribute("dateTime")).toBe(timestamp);
  });

  it("formats the same instant in different timezones", () => {
    const timestamp = "2026-10-05T11:47:00Z";

    expect(formatLocalDateTime(timestamp, "Asia/Jakarta")).toBe("5 Oct 2026, 18:47");
    expect(formatLocalDateTime(timestamp, "UTC")).toBe("5 Oct 2026, 11:47");
  });

  it("uses consistent text for missing and invalid values", () => {
    const { rerender } = render(<LocalDateTime value={null} />);
    expect(screen.getByText("Not recorded")).toBeTruthy();

    rerender(<LocalDateTime value="not-a-timestamp" />);
    expect(screen.getByText("Unknown time")).toBeTruthy();
    expect(document.querySelector("time")).toBeNull();

    expect(formatLocalDateTime(undefined, "UTC")).toBe("Not recorded");
    expect(formatLocalDateTime("not-a-timestamp", "UTC")).toBe("Unknown time");
  });
});
