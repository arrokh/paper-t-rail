import type { Metadata } from "next";
import "./styles.css";
import { QueryProvider } from "./query-provider";

export const metadata: Metadata = {
  title: "Paper T-Rail — Upload a Source Document",
  description: "Start a traceable analysis run for an English academic PDF.",
  icons: { icon: "/rail.svg" },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <QueryProvider>{children}</QueryProvider>
      </body>
    </html>
  );
}
